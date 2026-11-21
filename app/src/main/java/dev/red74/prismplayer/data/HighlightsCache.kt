package dev.red74.prismplayer.data

/**
 * The featured events kept on the device between two openings, so "In evidenza" shows at once while the guide is
 * downloaded again in the background. Only the programme titles, times and the *hashed keys* of the channels are
 * written (never an address or a name from the list); the channels are found again in the list when the file is read.
 */
object HighlightsCache {
    private const val HEADER = "prism-highlights-1"

    /** The text to keep: one line per event. */
    fun encode(data: HighlightsData): String {
        val out = StringBuilder(HEADER).append('\n')
        for (kind in HighlightKind.entries) {
            for (event in data.events(kind)) {
                out.append(kind.name).append('\t')
                    .append(event.topic?.name.orEmpty()).append('\t')
                    .append(event.score).append('\t')
                    .append(event.startMs).append('\t')
                    .append(event.endMs).append('\t')
                    .append(event.channels.joinToString(",") { FavoriteKey.of(it) }).append('\t')
                    .append(event.title.filter { !it.isISOControl() }).append('\n')
            }
        }
        return out.toString()
    }

    /**
     * The events again, with their channels found by [find] (key to channel, null when the list no longer has it).
     * Events already over, with no channel left, or from a damaged line are dropped; nothing here throws.
     */
    fun decode(text: String?, nowMs: Long, find: (String) -> Channel?): HighlightsData {
        if (text.isNullOrEmpty()) return HighlightsData.Empty
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return HighlightsData.Empty
        val byKind = HashMap<HighlightKind, MutableList<HighlightEvent>>()
        for (line in lines.drop(1)) {
            val f = line.split('\t', limit = 7)
            if (f.size < 7) continue
            val kind = HighlightKind.entries.firstOrNull { it.name == f[0] } ?: continue
            val topic = SportTopic.entries.firstOrNull { it.name == f[1] }
            val score = f[2].toIntOrNull() ?: continue
            val start = f[3].toLongOrNull() ?: continue
            val end = f[4].toLongOrNull() ?: continue
            if (end <= nowMs || end <= start) continue
            val channels = f[5].split(',').filter { it.isNotEmpty() }.mapNotNull(find).take(HighlightEvent.MAX_CHANNELS)
            if (channels.isEmpty() || f[6].isBlank()) continue
            byKind.getOrPut(kind) { ArrayList() } += HighlightEvent(kind, f[6], topic, start, end, score, channels)
        }
        return HighlightsData(byKind.mapValues { (_, list) -> list.sortedWith(compareByDescending<HighlightEvent> { it.score }.thenBy { it.startMs }) })
    }
}

/** The cache file of each list, in the app's private no-backup folder. A failure to read or write just means no cache. */
class HighlightsStore(private val dir: java.io.File) {
    private fun file(id: String) = java.io.File(dir, id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80) + ".txt")

    fun read(id: String): String? = runCatching { file(id).takeIf { it.isFile }?.readText() }.getOrNull()

    fun write(id: String, text: String) {
        runCatching {
            dir.mkdirs()
            val target = file(id)
            val temp = java.io.File(dir, target.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    fun delete(id: String) {
        runCatching { file(id).delete() }
    }
}
