package dev.red74.prismplayer.data

/**
 * A reminder for a programme that has not started yet ("In evidenza", search): a notification a few minutes before it
 * begins. The channel is kept by its name and its hashed key ([FavoriteKey]) only, never its address, which carries
 * the list's credentials.
 */
data class Reminder(
    val id: String,
    val title: String,
    val startMs: Long,
    val channelName: String,
    val channelKey: String,
)

object Reminders {
    /** The notification comes this long before the start. */
    const val LEAD_MS = 5L * 60_000

    /** A reminder stays listed this long after the start (a late notification is still shown), then it is dropped. */
    const val GRACE_MS = 10L * 60_000

    /** More than this many are not kept: the oldest go first (the alarms are a shared resource of the device). */
    const val MAX = 40

    fun idOf(title: String, startMs: Long, channelKey: String): String =
        FavoriteKey.ofUrl("reminder|${Highlights.clean(title)}|${startMs / 60_000L}|$channelKey")

    /** Only a programme that has not started can be reminded. */
    fun canSet(startMs: Long, nowMs: Long): Boolean = startMs > nowMs

    /** When the alarm goes off: [LEAD_MS] before the start, or at once when that moment has passed. */
    fun triggerAt(startMs: Long, nowMs: Long): Long = maxOf(nowMs + 1_000L, startMs - LEAD_MS)

    fun isSet(list: List<Reminder>, id: String): Boolean = list.any { it.id == id }

    /** The list with [reminder] added (or taken away when it was there), kept sorted by start and within [MAX]. */
    fun toggled(list: List<Reminder>, reminder: Reminder): List<Reminder> =
        if (isSet(list, reminder.id)) list.filter { it.id != reminder.id }
        else (list + reminder).sortedBy { it.startMs }.takeLast(MAX)

    /** Reminders not yet over. */
    fun pruned(list: List<Reminder>, nowMs: Long): List<Reminder> = list.filter { it.startMs + GRACE_MS > nowMs }

    /** The reminders whose moment has come and that were not announced yet. */
    fun due(list: List<Reminder>, nowMs: Long, announced: Set<String>): List<Reminder> =
        list.filter { it.id !in announced && it.startMs - LEAD_MS <= nowMs && nowMs < it.startMs + GRACE_MS }

    /** Minutes to the start for the notification text, at least 0. */
    fun minutesLeft(startMs: Long, nowMs: Long): Int = (((startMs - nowMs) + 59_999L) / 60_000L).toInt().coerceAtLeast(0)

    // One reminder per line, fields separated by a tab; titles never contain either.
    fun encode(list: List<Reminder>): String =
        list.joinToString("\n") { listOf(it.id, it.startMs.toString(), it.channelKey, one(it.channelName), one(it.title)).joinToString("\t") }

    fun decode(text: String?): List<Reminder> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 5) return@mapNotNull null
            val start = f[1].toLongOrNull() ?: return@mapNotNull null
            if (f[0].isEmpty()) return@mapNotNull null
            Reminder(f[0], f.drop(4).joinToString(" "), start, f[3], f[2])
        }.distinctBy { it.id }.take(MAX)
    }

    private fun one(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
