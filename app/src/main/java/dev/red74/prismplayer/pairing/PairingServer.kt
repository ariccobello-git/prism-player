package dev.red74.prismplayer.pairing

import dev.red74.prismplayer.data.PlaylistUrl
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.net.InetAddress

/**
 * Short-lived HTTP endpoint used only while the pairing screen is open.
 *
 * It is write-only by design:
 * - `GET /` and `GET /pair?c=CODE` serve static forms and never contain saved data;
 * - `POST /submit` accepts one playlist per valid code, then burns the code;
 * - nothing here can list, read or export playlists stored on the TV, so saved URLs are never on the network.
 *
 * Requests from non-local addresses are refused.
 */
class PairingServer(
    private val manager: PairingManager,
    private val onPlaylist: (name: String, url: String) -> Unit,
    port: Int = 0,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val lang = PairPage.langFrom(session.headers["accept-language"])
        return try {
            if (!isLocalClient(session.remoteIpAddress)) {
                html(Response.Status.FORBIDDEN, PairPage.denied(lang))
            } else {
                route(session, lang)
            }
        } catch (e: Exception) {
            html(Response.Status.INTERNAL_ERROR, PairPage.denied(lang))
        }
    }

    private fun route(session: IHTTPSession, lang: PairPage.Lang): Response {
        val path = session.uri
        return when {
            session.method == Method.GET && path == "/" ->
                html(Response.Status.OK, PairPage.codeEntry(lang))

            session.method == Method.GET && path == "/pair" -> {
                val code = session.parameters["c"]?.firstOrNull().orEmpty()
                if (manager.verify(code)) {
                    html(Response.Status.OK, PairPage.form(code, lang))
                } else {
                    html(Response.Status.FORBIDDEN, PairPage.denied(lang))
                }
            }

            session.method == Method.POST && path == "/submit" -> handleSubmit(session, lang)

            else -> html(Response.Status.NOT_FOUND, PairPage.notFound(lang))
        }
    }

    private fun handleSubmit(session: IHTTPSession, lang: PairPage.Lang): Response {
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0L || length > MAX_BODY_BYTES) {
            return html(Response.Status.BAD_REQUEST, PairPage.denied(lang))
        }
        session.parseBody(HashMap<String, String>())
        val params = session.parameters
        fun field(key: String): String = params[key]?.firstOrNull().orEmpty()

        val code = field("c")
        if (!manager.verify(code)) return html(Response.Status.FORBIDDEN, PairPage.denied(lang))

        // A typo in the link must not burn the code: validate first, consume only when the input is good.
        val validated = PlaylistUrl.validate(PlaylistUrl.withDefaultScheme(field("u")))
        if (validated !is PlaylistUrl.Result.Valid) {
            return html(Response.Status.BAD_REQUEST, PairPage.form(code, lang, showError = true))
        }
        if (!manager.consume(code)) return html(Response.Status.FORBIDDEN, PairPage.denied(lang))

        val name = sanitizeName(field("n")).ifBlank { validated.host }
        onPlaylist(name, validated.url)
        return html(Response.Status.OK, PairPage.success(lang))
    }

    private fun sanitizeName(raw: String): String =
        raw.filter { !it.isISOControl() }.trim().take(MAX_NAME_LENGTH)

    private fun isLocalClient(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        return runCatching {
            val ip = InetAddress.getByName(address)
            ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress
        }.getOrDefault(false)
    }

    private fun html(status: Response.IStatus, body: String): Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, "text/html; charset=utf-8", body)
        // One request per connection: phones keep the pairing form open for a long time while typing,
        // and a keep-alive connection reused after the server's read timeout would silently drop the POST.
        response.closeConnection(true)
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
        )
        return response
    }

    private companion object {
        const val MAX_BODY_BYTES = 8192L
        const val MAX_NAME_LENGTH = 60
    }
}
