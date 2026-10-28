package dev.red74.prismplayer.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Download failure. The message never contains the URL (it may embed credentials or tokens). */
class FetchException(val kind: Kind, val httpCode: Int? = null) : IOException(kind.name) {
    enum class Kind { NETWORK, HTTP, TOO_LARGE, REDIRECT }
}

class PlaylistFetcher {

    suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(download(url))
        } catch (e: FetchException) {
            Result.failure(e)
        } catch (e: Exception) {
            // Network exceptions often carry the host name; normalise to a URL-free error.
            Result.failure(FetchException(FetchException.Kind.NETWORK))
        }
    }

    private fun download(startUrl: String): String {
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
                    return readLimited(connection.inputStream)
                } else {
                    throw FetchException(FetchException.Kind.HTTP, code)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw FetchException(FetchException.Kind.REDIRECT)
    }

    private fun readLimited(stream: InputStream): String {
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw FetchException(FetchException.Kind.TOO_LARGE)
                out.write(buffer, 0, read)
            }
            return out.toByteArray().decodeToString()
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_BYTES = 64 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val USER_AGENT = "PrismPlayer/0.1 (Android TV)"
    }
}
