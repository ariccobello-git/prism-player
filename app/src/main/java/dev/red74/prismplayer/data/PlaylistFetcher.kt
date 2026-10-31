package dev.red74.prismplayer.data

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Download failure. The message never contains the URL (it may embed credentials or tokens). */
class FetchException(val kind: Kind, val httpCode: Int? = null) : IOException(kind.name) {
    enum class Kind { NETWORK, HTTP, TOO_LARGE, REDIRECT, TIMEOUT, DNS, REFUSED, TLS }
}

/**
 * The one line to the IPTV panel. The provider allows a single connection per account: two at once can get the
 * account blocked for good. So list downloads, guide downloads and plot requests run one at a time ([request]), and
 * never while a stream is open: a player first calls [beginStream], which cuts any request still running and waits
 * until its connection is closed; the request starts over by itself once every stream has ended ([endStream]).
 */
object PanelLine {
    private val mutex = Mutex()
    private val streams = MutableStateFlow(0)
    @Volatile private var open: HttpURLConnection? = null
    @Volatile private var preempted = false

    /** True when the running request was cut by a stream: its failure must not count as a real one. */
    val cut: Boolean get() = preempted

    suspend fun <T> request(block: suspend () -> T): T {
        while (true) {
            streams.first { it == 0 }
            mutex.lock()
            try {
                if (streams.value != 0) continue
                preempted = false
                try {
                    return block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!preempted) throw e
                }
            } finally {
                open = null
                mutex.unlock()
            }
        }
    }

    /** Every panel connection is announced here, so a stream that starts can close it at once. */
    fun opened(connection: HttpURLConnection) {
        open = connection
        if (preempted || streams.value > 0) {
            preempted = true
            connection.disconnect()
        }
    }

    suspend fun beginStream() {
        streams.update { it + 1 }
        preempted = true
        open?.disconnect()
        // Waits for a request still running to let go of its connection.
        mutex.withLock { }
    }

    fun endStream() {
        streams.update { (it - 1).coerceAtLeast(0) }
    }
}

/** One player's share of [PanelLine]: taken before the player opens a stream, given back when it stops. Main thread. */
class StreamHold {
    private var held = false

    suspend fun acquire() {
        if (held) return
        held = true
        PanelLine.beginStream()
    }

    fun release() {
        if (!held) return
        held = false
        PanelLine.endStream()
    }
}

class PlaylistFetcher {

    /**
     * Streams the playlist into [consume] line by line, so a list of any size never has to fit in memory
     * (and there is no byte limit). The result is whatever [consume] returns.
     */
    suspend fun <T> fetch(
        url: String,
        agents: List<String> = HttpAgent.LIST_AGENTS,
        consume: (BufferedReader) -> T,
    ): Result<T> =
        fetchBytes(url, agents) { input ->
            consume(BufferedReader(InputStreamReader(input, Charsets.UTF_8), READ_BUFFER_CHARS))
        }

    /**
     * Same download, handing over the raw bytes. A gzip-compressed body is unpacked, whether or not the server
     * declared it, because guide files are often served as `.xml.gz`.
     */
    suspend fun <T> fetchBytes(
        url: String,
        agents: List<String> = HttpAgent.LIST_AGENTS,
        consume: (InputStream) -> T,
    ): Result<T> = PanelLine.request { withContext(Dispatchers.IO) {
        var last: FetchException? = null
        // A refusal (HTTP error) is retried with the next identity; other failures use the first one twice.
        var agentIndex = 0
        var attempt = 0
        while (attempt < ATTEMPTS + agents.size - 1) {
            val agent = agents[agentIndex.coerceAtMost(agents.lastIndex)]
            attempt++
            try {
                return@withContext Result.success(download(url, agent, consume))
            } catch (e: FetchException) {
                if (PanelLine.cut) throw e
                last = e
                if (e.kind == FetchException.Kind.HTTP && agentIndex < agents.lastIndex) {
                    agentIndex++
                    continue
                }
                if (e.kind == FetchException.Kind.HTTP || e.kind == FetchException.Kind.REDIRECT ||
                    e.kind == FetchException.Kind.DNS || e.kind == FetchException.Kind.TLS
                ) {
                    return@withContext Result.failure(e)
                }
            } catch (e: Exception) {
                if (PanelLine.cut) throw e
                // Network exceptions often carry the host name; normalise to a URL-free error.
                last = FetchException(classify(e))
            }
            if (attempt >= ATTEMPTS) break
            delay(RETRY_DELAY_MS)
        }
        Result.failure(last ?: FetchException(FetchException.Kind.NETWORK))
    } }

    private fun classify(e: Exception): FetchException.Kind = when (e) {
        is SocketTimeoutException -> FetchException.Kind.TIMEOUT
        is UnknownHostException -> FetchException.Kind.DNS
        is ConnectException -> FetchException.Kind.REFUSED
        is SSLException -> FetchException.Kind.TLS
        else -> FetchException.Kind.NETWORK
    }

    private fun <T> download(startUrl: String, agent: String, consume: (InputStream) -> T): T {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            val connection = URL(current).openConnection() as HttpURLConnection
            PanelLine.opened(connection)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                if (agent.isNotEmpty()) connection.setRequestProperty("User-Agent", agent)
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Connection", "close")

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw FetchException(FetchException.Kind.REDIRECT)
                    val next = URL(URL(current), location)
                    val protocol = next.protocol.lowercase()
                    if (protocol != "http" && protocol != "https") {
                        throw FetchException(FetchException.Kind.REDIRECT)
                    }
                    current = next.toString()
                } else if (code in 200..299) {
                    return connection.inputStream.use { raw ->
                        val buffered = BufferedInputStream(raw, 64 * 1024)
                        buffered.mark(2)
                        val first = buffered.read()
                        val second = buffered.read()
                        buffered.reset()
                        val gzipped = first == 0x1f && second == 0x8b
                        consume(if (gzipped) GZIPInputStream(buffered, 64 * 1024) else buffered)
                    }
                } else {
                    throw FetchException(FetchException.Kind.HTTP, code)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw FetchException(FetchException.Kind.REDIRECT)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val READ_BUFFER_CHARS = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 120_000
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_000L
    }
}
