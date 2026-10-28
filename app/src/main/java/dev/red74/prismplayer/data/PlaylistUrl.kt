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
        val trimmed = raw.trim()
        return if (trimmed.isEmpty() || trimmed.contains("://")) trimmed else "http://$trimmed"
    }

    fun validate(raw: String): Result {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (candidate.length > MAX_LENGTH) return Result.Invalid(Reason.TOO_LONG)
        if (candidate.any { it.isWhitespace() || it.isISOControl() }) {
            return Result.Invalid(Reason.BAD_FORMAT)
        }
        val uri = try {
            URI(candidate)
        } catch (e: URISyntaxException) {
            return Result.Invalid(Reason.BAD_FORMAT)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
        val host = uri.host
        if (host.isNullOrBlank()) return Result.Invalid(Reason.BAD_FORMAT)
        return Result.Valid(candidate, host)
    }

    /**
     * Same list written differently (letter case of the host, default port, a trailing slash) compares equal.
     * The path and query are compared as typed: they usually carry the account.
     */
    fun sameList(a: String, b: String): Boolean = normalized(a) == normalized(b)

    private fun normalized(raw: String): String {
        val candidate = raw.trim()
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return candidate
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
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—"
}
