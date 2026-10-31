package dev.red74.prismplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Where each film or episode was left, so it can resume from there. Only hash keys (see [FavoriteKey]) and
 * numbers are stored, never addresses. SharedPreferences keeps everything in memory, so reads cost nothing.
 */
object WatchProgress {
    data class Point(val positionMs: Long, val durationMs: Long, val savedAt: Long) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    /** Below this nothing is worth resuming. */
    const val MIN_MS = 60_000L
    /** The end credits: past this the content counts as watched and its point is dropped. */
    const val END_MARGIN_MS = 90_000L
    /** Resuming goes back a few seconds so the viewer finds the thread again. */
    const val REWIND_MS = 5_000L
    const val MAX_ENTRIES = 300

    /** Bumped on every change, so screens that show progress redraw when the player closes. */
    var version by mutableIntStateOf(0)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("watch_progress", Context.MODE_PRIVATE)
    }

    /** True when [positionMs] is a point worth coming back to, false when it is too early or the end. */
    fun worthKeeping(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_MS) return false
        if (durationMs <= 0) return true
        val margin = maxOf(END_MARGIN_MS, durationMs / 20)
        return positionMs < durationMs - margin
    }

    fun resumeFrom(point: Point): Long = (point.positionMs - REWIND_MS).coerceAtLeast(0L)

    fun encode(point: Point): String = "${point.positionMs}|${point.durationMs}|${point.savedAt}"

    fun decode(text: String?): Point? {
        val parts = text?.split('|') ?: return null
        if (parts.size != 3) return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        return Point(pos, dur, at)
    }

    /** The keys to forget so that at most [max] remain, oldest first. */
    fun overflow(entries: Map<String, Point>, max: Int = MAX_ENTRIES): List<String> =
        if (entries.size <= max) emptyList()
        else entries.entries.sortedBy { it.value.savedAt }.take(entries.size - max).map { it.key }

    fun get(url: String): Point? = decode(prefs?.getString(FavoriteKey.ofUrl(url), null))

    /** Keeps the point, or drops it when it is too early or the content is finished. */
    fun save(url: String, positionMs: Long, durationMs: Long) {
        val store = prefs ?: return
        if (!worthKeeping(positionMs, durationMs)) {
            clear(url)
            return
        }
        val key = FavoriteKey.ofUrl(url)
        val editor = store.edit().putString(key, encode(Point(positionMs, durationMs, System.currentTimeMillis())))
        if (!store.contains(key) && store.all.size >= MAX_ENTRIES) {
            val points = store.all.mapNotNull { (k, v) -> decode(v as? String)?.let { k to it } }.toMap()
            overflow(points, MAX_ENTRIES - 1).forEach(editor::remove)
        }
        editor.apply()
        version++
    }

    fun clear(url: String) {
        val store = prefs ?: return
        val key = FavoriteKey.ofUrl(url)
        if (store.contains(key)) {
            store.edit().remove(key).apply()
            version++
        }
    }
}
