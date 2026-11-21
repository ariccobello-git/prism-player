package dev.red74.prismplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * What the viewer watches, kept only as counts per category name and hash keys of what was already seen: no titles,
 * no addresses. It feeds the "You might like" suggestions. All of it stays on the device.
 */
class TasteModel(
    counts: Map<String, Int> = emptyMap(),
    seen: Map<String, Long> = emptyMap(),
) {
    private val counts = LinkedHashMap(counts)
    private val seen = LinkedHashMap(seen)

    /** One more play of something in [group] of [type]. */
    fun bump(type: ContentType, group: String?) {
        val name = group?.trim().orEmpty().take(MAX_NAME)
        if (name.isEmpty()) return
        val key = "${type.name}|$name"
        counts[key] = (counts[key] ?: 0) + 1
        if (counts.values.sum() > DECAY_AT) {
            // Old habits fade: halve everything, drop what falls to zero.
            val halved = counts.mapValues { it.value / 2 }.filterValues { it > 0 }
            counts.clear(); counts.putAll(halved)
        }
        if (counts.size > MAX_GROUPS) {
            counts.entries.sortedBy { it.value }.take(counts.size - MAX_GROUPS).forEach { counts.remove(it.key) }
        }
    }

    /** The [n] categories of [type] watched most, most first. A category needs [MIN_PLAYS] plays to count. */
    fun top(type: ContentType, n: Int): List<String> =
        counts.filterKeys { it.startsWith(type.name + "|") }
            .filterValues { it >= MIN_PLAYS }
            .entries.sortedByDescending { it.value }.take(n)
            .map { it.key.substringAfter('|') }

    fun markSeen(key: String, nowMs: Long) {
        seen[key] = nowMs
        if (seen.size > MAX_SEEN) seen.entries.sortedBy { it.value }.take(seen.size - MAX_SEEN).forEach { seen.remove(it.key) }
    }

    fun seenKeys(): Set<String> = seen.keys.toSet()

    fun encodeCounts(): Set<String> = counts.map { "${it.value}\t${it.key}" }.toSet()

    fun encodeSeen(): Set<String> = seen.map { "${it.value}:${it.key}" }.toSet()

    companion object {
        const val MIN_PLAYS = 2
        const val MAX_NAME = 80
        const val MAX_GROUPS = 80
        const val MAX_SEEN = 400
        const val DECAY_AT = 400

        fun decode(counts: Set<String>?, seen: Set<String>?): TasteModel {
            val c = LinkedHashMap<String, Int>()
            for (line in counts.orEmpty()) {
                val n = line.substringBefore('\t').toIntOrNull() ?: continue
                val key = line.substringAfter('\t', "")
                if (key.isNotEmpty() && n > 0) c[key] = n
            }
            val s = LinkedHashMap<String, Long>()
            for (line in seen.orEmpty()) {
                val t = line.substringBefore(':').toLongOrNull() ?: continue
                val key = line.substringAfter(':', "")
                if (key.isNotEmpty()) s[key] = t
            }
            return TasteModel(c, s)
        }
    }
}

/** Stable pick of suggestions: the unseen entries, in an order that changes once a day, at most [limit]. */
object Suggestions {
    fun <T> pick(candidates: List<T>, key: (T) -> String, seen: Set<String>, daySeed: Long, limit: Int): List<T> =
        candidates.filter { key(it) !in seen }
            .sortedBy { mix(key(it), daySeed) }
            .take(limit)

    /** A well mixed number per (entry, day): appending the day to the name would only shift every hash alike. */
    private fun mix(key: String, seed: Long): Long {
        var x = key.hashCode().toLong() * -0x61c8864680b583ebL + seed * 0x2545F4914F6CDD1DL
        x = x xor (x ushr 31)
        x *= -0x40a7b892e31b1a47L
        return x xor (x ushr 29)
    }
}

/** Keeps [TasteModel] on the device and updates it when something starts playing. */
object Taste {
    private var prefs: SharedPreferences? = null
    private var model = TasteModel()

    /** Bumped on every change, so a page showing suggestions can redraw. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("taste", Context.MODE_PRIVATE)
        prefs = store
        model = TasteModel.decode(store.getStringSet("counts", null), store.getStringSet("seen", null))
        version++
    }

    /** Something started: its category counts, and a film or series is remembered as seen. */
    fun record(channel: Channel) {
        model.bump(channel.type, channel.group)
        when (channel.type) {
            ContentType.MOVIE -> model.markSeen(FavoriteKey.of(channel), System.currentTimeMillis())
            ContentType.SERIES -> model.markSeen(FavoriteKey.ofShow(channel.group, channel.seriesTitle ?: channel.name), System.currentTimeMillis())
            ContentType.LIVE -> Unit
        }
        prefs?.edit()?.putStringSet("counts", model.encodeCounts())?.putStringSet("seen", model.encodeSeen())?.apply()
        version++
    }

    fun top(type: ContentType, n: Int): List<String> = model.top(type, n)

    fun seen(): Set<String> = model.seenKeys()
}
