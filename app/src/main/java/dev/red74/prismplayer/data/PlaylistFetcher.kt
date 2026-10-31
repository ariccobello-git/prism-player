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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Download failure. The message never contains the URL (it may embed credentials or tokens). */
class FetchException(val kind: Kind, val httpCode: Int? = null) : IOException(kind.name) {
    enum class Kind { NETWORK, HTTP, TOO_LARGE, REDIRECT, TIMEOUT, DNS, REFUSED, TLS }
}

/**
 * The one line to the IPTV panel: list downloads, guide downloads and plot requests take turns on it, so the app
 * never holds two connections to the provider at once (many panels refuse the second one, or the whole account).
 */
object PanelLine {
    val mutex = Mutex()
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
    ): Result<T> = PanelLine.mutex.withLock { withContext(Dispatchers.IO) {
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
