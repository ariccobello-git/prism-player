package dev.red74.prismplayer.data

import java.io.File

/**
 * The featured events kept on the device between two openings, so "In evidenza" shows at once while the guide is
 * downloaded again in the background. The channels are kept whole (they hold the addresses of the list), so the file
 * is sealed with the device key exactly like the saved lists ([HighlightsStore]); reading it needs no look at the list
 * itself, which on a big list (every address is sealed one by one) would take seconds on a small TV box.
 */
object HighlightsCache {
    private const val HEADER = "prism-highlights-2"
    private const val FIELD = '\u001f'
    private const val CHANNEL = '\u001e'

    private fun clean(text: String?): String = text.orEmpty().filter { !it.isISOControl() }

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
                    .append(
                        event.channels.joinToString(CHANNEL.toString()) { c ->
                            listOf(c.name, c.url, c.group, c.logo, c.tvgId, c.tvgName).joinToString(FIELD.toString()) { clean(it) }
                        },
                    ).append('\t')
                    .append(clean(event.title)).append('\n')
            }
        }
        return out.toString()
    }

    /**
     * The events again. Events already over, with no channel left, or from a damaged line are dropped; nothing here
     * throws.
     */
    fun decode(text: String?, nowMs: Long): HighlightsData {
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
            val channels = f[5].split(CHANNEL).mapNotNull(::channelOf).take(HighlightEvent.MAX_CHANNELS)
            if (channels.isEmpty() || f[6].isBlank()) continue
            byKind.getOrPut(kind) { ArrayList() } += HighlightEvent(kind, f[6], topic, start, end, score, channels)
        }
        return HighlightsData(byKind.mapValues { (_, list) -> list.sortedWith(compareByDescending<HighlightEvent> { it.score }.thenBy { it.startMs }) })
    }

    private fun channelOf(text: String): Channel? {
        val p = text.split(FIELD)
        if (p.size < 6 || p[0].isBlank() || p[1].isBlank()) return null
        return Channel(p[0], p[1], p[2].ifEmpty { null }, p[3].ifEmpty { null }, p[4].ifEmpty { null }, p[5].ifEmpty { null })
    }
}

/**
 * The cache file of each list, in the app's private no-backup folder and sealed with [box]. A failure to read or write
 * just means no cache.
 */
class HighlightsStore(private val dir: File, private val box: SecureBox) {
    private fun file(id: String) = File(dir, id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80) + ".bin")

    fun read(id: String): String? = runCatching {
        file(id).takeIf { it.isFile }?.let { String(box.decrypt(it.readBytes()), Charsets.UTF_8) }
    }.getOrNull()

    fun write(id: String, text: String) {
        runCatching {
            dir.mkdirs()
            val target = file(id)
            val temp = File(dir, target.name + ".tmp")
            temp.writeBytes(box.encrypt(text.toByteArray(Charsets.UTF_8)))
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    fun delete(id: String) {
        runCatching { file(id).delete() }
    }
}
