package dev.red74.prismplayer.data

import androidx.media3.common.PlaybackException

/**
 * Contents the provider could not deliver, shown with a ⊘ in the lists.
 *
 * Only faults on the provider's side count, seen by the full-screen player (never by the small preview): the server
 * answers with an HTTP error (403, 404, 5xx...) to every try (a second try a moment later, because the first refusal
 * is often the preview's connection still being counted, then the other identities of
 * [dev.red74.prismplayer.player.StreamIdentity], because some panels answer 403 or 404 to identities they do not
 * know); what arrives is not a video stream; or the server does not answer at all, but only while another channel
 * has just played, so that a VPN switched off does not mark the whole list. Decoder and device problems never
 * mark a content. A mark lasts 6 hours and a good playback removes it at once. Keys are hashes, never URLs.
 */
object Availability {
    const val MARK_TTL_MS = 6L * 60 * 60 * 1000

    /** A channel played within this time proves that the network and the VPN work. */
    const val NETWORK_PROOF_MS = 10L * 60 * 1000

    /** Wait before the second try of a refused channel (the same identity), and before each other identity. */
    const val RETRY_DELAY_MS = 2_000L
    const val NEXT_IDENTITY_DELAY_MS = 400L

    enum class Trouble {
        /** 401, 403, 429...: the provider refuses (channel off, or too many connections open). */
        REFUSED,
        /** 404, 410: the provider does not have it. */
        MISSING,
        /** 5xx: the provider's server is failing. */
        SERVER,
        /** The server does not answer or the connection drops. */
        NO_ANSWER,
        /** What arrives is not a playable stream. */
        BAD_STREAM,
        /** Anything else (decoder, device): never the provider's fault for sure. */
        OTHER,
    }

    fun classify(errorCode: Int, httpCode: Int?): Trouble = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when {
            httpCode == 404 || httpCode == 410 -> Trouble.MISSING
            httpCode != null && httpCode >= 500 -> Trouble.SERVER
            else -> Trouble.REFUSED
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Trouble.MISSING
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> Trouble.NO_ANSWER
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> Trouble.BAD_STREAM
        else -> Trouble.OTHER
    }

    /** HTTP errors are tried again silently (see the class comment) before being shown. */
    fun retries(trouble: Trouble): Boolean = trouble == Trouble.REFUSED || trouble == Trouble.SERVER || trouble == Trouble.MISSING

    /**
     * Whether a failure marks the content. [afterRetry]: the second try failed too. [networkProven]: another channel
     * played a moment ago. [preview]: the small preview, which never marks (it has no second tries).
     */
    fun marks(trouble: Trouble, afterRetry: Boolean, networkProven: Boolean, preview: Boolean): Boolean = !preview && when (trouble) {
        Trouble.BAD_STREAM -> true
        Trouble.MISSING, Trouble.REFUSED, Trouble.SERVER -> afterRetry
        Trouble.NO_ANSWER -> networkProven
        Trouble.OTHER -> false
    }

    /** Marks still valid at [nowMs]. */
    fun live(marks: Map<String, Long>, nowMs: Long): Map<String, Long> = marks.filterValues { nowMs - it in 0 until MARK_TTL_MS }

    fun encode(marks: Map<String, Long>): Set<String> = marks.mapTo(HashSet()) { (key, at) -> "$key@$at" }

    fun decode(stored: Set<String>): Map<String, Long> = stored.mapNotNull { entry ->
        val at = entry.substringAfterLast('@', "").toLongOrNull() ?: return@mapNotNull null
        entry.substringBeforeLast('@') to at
    }.toMap()
}
