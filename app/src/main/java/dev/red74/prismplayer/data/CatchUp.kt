package dev.red74.prismplayer.data

import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catch-up: watching a programme of a live channel from its start, after it has begun. Xtream-style panels keep the
 * last days of the channels that have an archive and serve a past stretch as a stream of its own at
 * `/timeshift/user/password/minutes/start/id.ts` (or `/streaming/timeshift.php?...`). The playlist does not say which
 * channels have an archive, so the player tries, and a channel that answers with an error is remembered as having
 * none ([Marks]); the button for it then goes away for a week.
 *
 * Only one stream is ever open: the catch-up replaces the live stream, it never runs next to it.
 */
object CatchUp {
    /** A channel marked without archive is asked again after this long (providers change their archives). */
    const val MARK_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** A programme shorter than this is not worth restarting. */
    const val MIN_PROGRAMME_MS = 5L * 60_000

    /** A programme that started less than this ago is still at its beginning: nothing to catch up. */
    const val MIN_ELAPSED_MS = 60_000L

    /** Extra minutes asked after the programme's end: guides and archives never agree to the minute. */
    private const val EXTRA_MINUTES = 5

    class Parts(val base: String, val user: String, val password: String, val id: String)

    /** The pieces of an Xtream live address (`/live/user/pass/id.ts`, `/user/pass/id`), or null for any other. */
    fun parts(url: String): Parts? {
        if (StreamFallback.alternates(url).isEmpty()) return null
        val bare = url.substringBefore('#').substringBefore('?')
        val schemeEnd = bare.indexOf("://")
        if (schemeEnd < 0) return null
        val afterScheme = bare.substring(schemeEnd + 3)
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return null
        val authority = afterScheme.substring(0, slash)
        val segments = afterScheme.substring(slash).split('/').filter { it.isNotEmpty() }
        if (segments.size < 3) return null
        val n = segments.size
        val id = segments[n - 1].substringBefore('.')
        val password = segments[n - 2]
        val user = segments[n - 3]
        val prefixEnd = if (n >= 4 && segments[n - 4].equals("live", ignoreCase = true)) n - 4 else n - 3
        val prefix = segments.subList(0, prefixEnd).joinToString("") { "/$it" }
        return Parts(bare.substring(0, schemeEnd + 3) + authority + prefix, user, password, id)
    }

    /** True when the channel could have an archive: an Xtream live address. */
    fun possible(url: String): Boolean = parts(url) != null

    /**
     * Whether the button "from the start" is offered: a programme of at least [MIN_PROGRAMME_MS] on air for more than
     * [MIN_ELAPSED_MS], on a channel not marked without archive.
     */
    fun offered(url: String, programme: Programme?, nowMs: Long, marked: Boolean): Boolean =
        programme != null && !marked && possible(url) &&
            programme.endMs - programme.startMs >= MIN_PROGRAMME_MS &&
            nowMs - programme.startMs >= MIN_ELAPSED_MS && nowMs < programme.endMs

    /**
     * The addresses to try for the stretch of [programme], in order. Panels want the start in their own time, so
     * [serverOffsetMs] (the panel's clock ahead of UTC) is added.
     */
    fun addresses(url: String, programme: Programme, serverOffsetMs: Long): List<String> {
        val p = parts(url) ?: return emptyList()
        val minutes = (((programme.endMs - programme.startMs) + 59_999L) / 60_000L).toInt() + EXTRA_MINUTES
        val start = startText(programme.startMs, serverOffsetMs)
        return listOf(
            "${p.base}/timeshift/${p.user}/${p.password}/$minutes/$start/${p.id}.ts",
            "${p.base}/streaming/timeshift.php?username=${p.user}&password=${p.password}&stream=${p.id}&start=$start&duration=$minutes",
        )
    }

    /** `2026-11-21:20-45`, the way the panel writes a start. */
    fun startText(startMs: Long, serverOffsetMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(startMs + serverOffsetMs))
    }

    /** The key under which a channel is marked without archive (a hash, never the address). */
    fun markKey(url: String): String = "c:" + FavoriteKey.ofUrl(url)

    /** The provider of a channel (its server and path prefix, hashed), or null for an address that is not Xtream style. */
    fun hostKey(url: String): String? = parts(url)?.let { FavoriteKey.ofUrl(it.base) }

    /** The mark of one channel that failed, grouped under its provider. */
    fun failureKey(url: String): String? = hostKey(url)?.let { "f:$it:" + FavoriteKey.ofUrl(url) }

    /** The mark of a provider whose archive answered at least once. */
    fun workedKey(url: String): String? = hostKey(url)?.let { "ok:$it" }

    /** Distinct channels of a provider that must fail, with no success, before the whole provider counts as having no archive. */
    const val PROVIDER_FAILURES = 2

    /**
     * True when the provider of [url] has shown no archive: [PROVIDER_FAILURES] different channels failed and none
     * ever worked (all within the week the marks live). The button is then not offered on any of its channels.
     */
    fun providerHasNoArchive(marks: Map<String, Long>, url: String): Boolean {
        val host = hostKey(url) ?: return false
        if ("ok:$host" in marks) return false
        return marks.keys.count { it.startsWith("f:$host:") } >= PROVIDER_FAILURES
    }

    // --- the panel's clock -------------------------------------------------------------------------------------

    /**
     * How far the panel's clock is from UTC, read from `player_api.php` (`server_info.time_now` is the panel's wall
     * clock, `timestamp_now` the same moment in UTC). Rounded to a quarter of an hour (the answer takes a little to
     * arrive); null when the answer lacks them or is not plausible.
     */
    fun serverOffset(reader: Reader): Long? {
        var wall: String? = null
        var stamp: Long? = null
        val scan = JsonScan(reader, numbers = true)
        scan.run { _, key, value ->
            if (scan.within("server_info")) when (key.lowercase()) {
                "time_now" -> if (wall == null) wall = value
                "timestamp_now" -> if (stamp == null) stamp = value.toLongOrNull()
            }
        }
        val local = wall?.let { parseWall(it) } ?: return null
        val utc = stamp?.times(1000L) ?: return null
        val raw = local - utc
        val quarter = 15L * 60_000
        val rounded = Math.floorDiv(raw + quarter / 2, quarter) * quarter
        return rounded.takeIf { it in -14L * 3_600_000..14L * 3_600_000 }
    }

    private fun parseWall(text: String): Long? = runCatching {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        format.isLenient = false
        format.parse(text.trim())?.time
    }.getOrNull()

    /** The address of `player_api.php` for the panel a stream address belongs to. */
    fun clockAddress(url: String): String? {
        val p = parts(url) ?: return null
        return "${p.base}/player_api.php?username=${p.user}&password=${p.password}"
    }

    /** The panel's offset kept for the run: it is asked once per panel, while no stream is open. */
    private val offsets = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun hostOf(url: String): String? = parts(url)?.base?.lowercase()

    fun offsetFor(url: String, fallbackMs: Long): Long = hostOf(url)?.let { offsets[it] } ?: fallbackMs

    fun knowsOffset(url: String): Boolean = hostOf(url)?.let { offsets.containsKey(it) } == true

    fun setOffset(url: String, offsetMs: Long) {
        hostOf(url)?.let { offsets[it] = offsetMs }
    }

    // --- channels without archive --------------------------------------------------------------------------------

    /** Marks as (key, time). Expired ones are dropped on reading. */
    fun liveMarks(marks: Map<String, Long>, nowMs: Long): Map<String, Long> =
        marks.filterValues { nowMs - it in 0 until MARK_TTL_MS }
}
