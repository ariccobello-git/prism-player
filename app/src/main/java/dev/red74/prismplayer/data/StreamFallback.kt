package dev.red74.prismplayer.data

import java.net.URI
import java.util.Collections

/**
 * One try at opening a stream: the identity (User-Agent) and the address. [format] says which form of the address
 * this is: 0 the one the list gives, 1 and 2 the other forms of the same channel (see [StreamFallback.alternates]).
 */
data class StreamAttempt(val agent: String?, val url: String, val format: Int = 0)

/**
 * When a live channel does not start, the player tries again by itself before showing an error: first with the
 * same address (a second time, a moment later), then with the other identities, then with the other form of the
 * same address. Panels of the Xtream family serve every live channel both as a plain transport stream (`.ts`) and
 * as HLS (`.m3u8`), and some of them answer 403 or 404 to just one of the two; the other is often fine.
 *
 * Every try opens only after the previous connection has closed (the player waits for it), so the list never has two
 * connections at once, not even while the tries follow one another.
 */
object StreamFallback {
    /** Other forms of a live address: `.ts` gives `.m3u8`, `.m3u8` gives `.ts`, no ending gives both. Empty for any other address. */
    fun alternates(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return emptyList()
        val path = uri.rawPath.orEmpty()
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return emptyList()
        val last = path.substring(slash + 1)
        val dot = last.lastIndexOf('.')
        val id = if (dot >= 0) last.substring(0, dot) else last
        val ending = if (dot >= 0) last.substring(dot + 1).lowercase() else ""
        if (id.isEmpty() || !id.all { it.isDigit() }) return emptyList()
        // At least user and password before the number (`/user/pass/12`, `/live/user/pass/12`).
        val before = path.substring(0, slash).split('/').filter { it.isNotEmpty() }
        if (before.size < 2) return emptyList()
        // A film or an episode is not a live channel: it has no other form.
        if (before.any { it.equals("movie", true) || it.equals("series", true) || it.equals("timeshift", true) }) return emptyList()
        val hash = url.indexOf('#')
        val fragment = if (hash >= 0) url.substring(hash) else ""
        val withoutFragment = if (hash >= 0) url.substring(0, hash) else url
        val mark = withoutFragment.indexOf('?')
        val query = if (mark >= 0) withoutFragment.substring(mark) else ""
        val bare = if (mark >= 0) withoutFragment.substring(0, mark) else withoutFragment
        if (!bare.endsWith(last)) return emptyList()
        val head = bare.substring(0, bare.length - last.length)
        val tail = query + fragment
        fun form(extension: String) = head + id + extension + tail
        return when (ending) {
            "ts" -> listOf(form(".m3u8"))
            "m3u8" -> listOf(form(".ts"))
            "" -> listOf(form(".m3u8"), form(".ts"))
            else -> emptyList()
        }
    }

    /**
     * The tries of one channel, in order: the identity [preferred] twice (the second a moment later, when a connection
     * still open from the preview has closed), the other identities, then the other forms of the address (a live
     * channel only) with the preferred identity and with one other. A form that worked before for this address comes
     * first. [candidates] are all the identities (see [dev.red74.prismplayer.player.StreamIdentity]).
     */
    fun plan(url: String, live: Boolean, preferred: String?, candidates: List<String?>): List<StreamAttempt> {
        val others = candidates.filter { it != preferred }
        val same = StreamAttempt(preferred, url)
        val identities = buildList {
            add(same)
            add(same)
            others.forEach { add(StreamAttempt(it, url)) }
        }
        if (!live) return identities
        val alternates = alternates(url)
        if (alternates.isEmpty()) return identities
        val extra = buildList {
            alternates.take(2).forEachIndexed { i, alt ->
                add(StreamAttempt(preferred, alt, i + 1))
                if (i == 0) others.firstOrNull()?.let { add(StreamAttempt(it, alt, 1)) }
            }
        }
        val known = worked[url]
        if (known != null) {
            val first = (identities + extra).firstOrNull { it.url == known }
            if (first != null) return listOf(first.copy(agent = preferred)) + (identities + extra)
        }
        return identities + extra
    }

    /** Addresses a form of which worked after the original failed: the original, then the form that played. Session only. */
    private val worked: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_KNOWN
        },
    )

    /** Called when [played] (a form of [original]) started: next time it is tried first. */
    fun remember(original: String, played: String) {
        if (played == original) worked.remove(original) else worked[original] = played
    }

    fun forget() = worked.clear()

    private const val MAX_KNOWN = 300
}
