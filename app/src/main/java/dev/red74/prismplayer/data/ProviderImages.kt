package dev.red74.prismplayer.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Logos and posters kept on the provider's own server take their turn on [PanelLine] like any other request: one
 * at a time, never while a stream is open (they simply show the placeholder then, and load on a later visit).
 * The picture is read whole inside its turn, so the connection is closed before the line is free again.
 * Pictures on other servers are not touched.
 */
object ProviderImages : Interceptor {
    private const val WAIT_MS = 15_000L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host !in ProviderHosts) return chain.proceed(request)
        if (PanelLine.streaming) throw IOException("a stream is open")
        return runBlocking {
            withTimeoutOrNull(WAIT_MS) {
                PanelLine.request(ProviderHosts.lineKey(request.url.host)) {
                    val call = chain.call()
                    PanelLine.opened { call.cancel() }
                    chain.proceed(request).use { response ->
                        val body = response.body
                        val bytes = body?.bytes() ?: ByteArray(0)
                        response.newBuilder().body(bytes.toResponseBody(body?.contentType())).build()
                    }
                }
            }
        } ?: throw IOException("the provider line is busy")
    }
}
