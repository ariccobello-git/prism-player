package dev.red74.prismplayer.data

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Download failure. The message never contains the URL (it may embed credentials or tokens). */
class FetchException(val kind: Kind, val httpCode: Int? = null) : IOException(kind.name) {
    enum class Kind { NETWORK, HTTP, TOO_LARGE, REDIRECT }
}

class PlaylistFetcher {

    /**
     * Streams the playlist into [consume] line by line, so a list of any size never has to fit in memory
     * (and there is no byte limit). The result is whatever [consume] returns.
     */
    suspend fun <T> fetch(url: String, consume: (BufferedReader) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(download(url, consume))
        } catch (e: FetchException) {
            Result.failure(e)
        } catch (e: Exception) {
            // Network exceptions often carry the host name; normalise to a URL-free error.
            Result.failure(FetchException(FetchException.Kind.NETWORK))
        }
    }

    private fun <T> download(startUrl: String, consume: (BufferedReader) -> T): T {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "*/*")

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
                    return connection.inputStream.use { input ->
                        consume(BufferedReader(InputStreamReader(input, Charsets.UTF_8), READ_BUFFER_CHARS))
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
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val USER_AGENT = "PrismPlayer/0.1 (Android TV)"
    }
}
