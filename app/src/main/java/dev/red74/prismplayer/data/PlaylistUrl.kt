package dev.red74.prismplayer.data

import java.net.URI
import java.net.URISyntaxException

/** Validation of user-supplied playlist links. Only absolute http/https URLs with a host are accepted. */
object PlaylistUrl {
    const val MAX_LENGTH = 2048

    enum class Reason { EMPTY, TOO_LONG, BAD_FORMAT, UNSUPPORTED_SCHEME, DUPLICATE }

    sealed interface Result {
        data class Valid(val url: String, val host: String) : Result
        data class Invalid(val reason: Reason) : Result
    }

    /** Lets people type `example.com:8080/list.m3u`: when no scheme is given, plain http is assumed. */
    fun withDefaultScheme(raw: String): String {
        val trimmed = stripInvisible(raw)
        return if (trimmed.isEmpty() || trimmed.contains("://")) trimmed else "http://$trimmed"
    }

    /**
     * A link can never contain spaces or line breaks, but pasting one from a message or a web page often adds them
     * (for example a break after the port). They are removed instead of being treated as an error.
     */
    fun stripInvisible(raw: String): String = raw.filterNot {
        it.isWhitespace() || it.isISOControl() || it == '\u200B' || it == '\u200C' || it == '\u200D' ||
            it == '\u2060' || it == '\uFEFF' || it == '\u00A0'
    }

    fun validate(raw: String): Result {
        val candidate = stripInvisible(raw)
        if (candidate.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (candidate.length > MAX_LENGTH) return Result.Invalid(Reason.TOO_LONG)
        val strict = try {
            URI(candidate)
        } catch (e: URISyntaxException) {
            null
        }
        val scheme = (strict?.scheme ?: candidate.substringBefore("://", "").takeIf { candidate.contains("://") })
            ?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
        // Real panels use odd characters (an underscore in a host name, a pipe in a query) that the strict parser
        // refuses but every player and server accepts, so the lenient URL parser decides when the strict one fails.
        val host = strict?.host ?: runCatching { java.net.URL(candidate).host }.getOrNull()
        if (host.isNullOrBlank()) return Result.Invalid(Reason.BAD_FORMAT)
        // A provider is always a domain or an IP address: a bare word (a code typed in the wrong field) is not a list.
        if ('.' !in host && ':' !in host) return Result.Invalid(Reason.BAD_FORMAT)
        return Result.Valid(candidate, host)
    }

    /**
     * Same list written differently (letter case of the host, default port, a trailing slash) compares equal.
     * The path and query are compared as typed: they usually carry the account.
     */
    fun sameList(a: String, b: String): Boolean = normalized(a) == normalized(b)

    private fun normalized(raw: String): String {
        val candidate = raw.trim()
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return candidate.lowercase().trimEnd('/')
        val scheme = uri.scheme?.lowercase() ?: return candidate
        val host = uri.host?.lowercase() ?: return candidate
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
        val path = (uri.rawPath ?: "").let { if (uri.rawQuery == null) it.trimEnd('/') else it }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host$port$path$query"
    }

    /** What the UI may show for a saved link: the host only, never the path or query (which often hold tokens). */
    fun displayLabel(url: String): String =
        (runCatching { URI(url).host }.getOrNull() ?: runCatching { java.net.URL(url).host }.getOrNull())
            ?.takeIf { it.isNotBlank() } ?: "—"

    /**
     * Xtream-style `get.php` links: other spellings of the same request that panels accept when they refuse the
     * original one (404 on an odd `output`/`type`). Empty for any other kind of link.
     */
    fun alternatives(url: String): List<String> {
        val at = url.indexOf("get.php?")
        if (at < 0) return emptyList()
        val head = url.substring(0, at + 8)
        val pairs = url.substring(at + 8).split('&').filter { it.isNotEmpty() }
        fun build(change: (String) -> String?): String =
            head + pairs.mapNotNull(change).joinToString("&")
        val variants = linkedSetOf(
            build { if (it.startsWith("output=", true)) null else it },
            build { if (it.startsWith("output=", true)) "output=ts" else it },
            build { if (it.startsWith("output=", true)) "output=m3u8" else it },
            build { if (it.startsWith("type=", true)) "type=m3u" else if (it.startsWith("output=", true)) null else it },
        )
        variants.remove(url)
        return variants.toList()
    }
}
