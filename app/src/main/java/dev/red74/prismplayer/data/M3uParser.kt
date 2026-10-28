package dev.red74.prismplayer.data

import java.io.BufferedReader
import java.io.StringReader
import java.net.URI

/**
 * Minimal, dependency-free M3U/M3U8 reader.
 *
 * - IPTV-style lists (`#EXTINF` followed by a URL) become one [Channel] per entry.
 * - A single HLS media/master playlist (`#EXT-X-TARGETDURATION`, `#EXT-X-STREAM-INF`, ...) is treated as one stream
 *   pointing at the playlist URL itself, so a direct `.m3u8` link just plays.
 * - Only http/https entries are kept; `file:`, `content:` and other schemes are dropped.
 */
object M3uParser {
    /** Safety cap on parsed entries (memory on a TV stick); a longer list is cut here and flagged as truncated. */
    const val MAX_CHANNELS = 300_000

    private val groupTitle = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val absoluteUrl = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")

    /**
     * Outcome of a streaming parse. [count] is the number of valid entries seen; [channels] holds them only
     * when the caller asked to keep them (verification just counts, so it never holds a big list in memory).
     */
    class Result(val channels: List<Channel>, val count: Int, val truncated: Boolean)

    fun parse(text: String, sourceUrl: String): List<Channel> =
        parse(BufferedReader(StringReader(text)), sourceUrl, keepChannels = true).channels

    /** Reads line by line, so the size of the playlist is limited by the entry cap, never by a byte count. */
    fun parse(reader: BufferedReader, sourceUrl: String, keepChannels: Boolean): Result {
        val channels = ArrayList<Channel>()
        val groups = HashMap<String, String>()
        var count = 0
        var truncated = false
        var pendingName: String? = null
        var pendingGroup: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) ||
                    line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                    // A single HLS playlist is one stream pointing at the playlist URL itself.
                    val single = Channel(name = PlaylistUrl.displayLabel(sourceUrl), url = sourceUrl)
                    return Result(listOf(single), 1, false)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val info = parseExtInf(line)
                    pendingName = info.first
                    pendingGroup = info.second?.let { groups.getOrPut(it) { it } }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }?.let { groups.getOrPut(it) { it } }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val resolved = resolve(sourceUrl, line)
                    if (resolved != null) {
                        count++
                        if (keepChannels) {
                            val name = pendingName ?: fallbackName(resolved, count)
                            channels += Channel(name = name, url = resolved, group = pendingGroup)
                        }
                        if (count >= MAX_CHANNELS) {
                            truncated = true
                            break
                        }
                    }
                    pendingName = null
                    pendingGroup = null
                }
            }
        }
        return Result(channels, count, truncated)
    }

    /** Returns (display name, group) from an `#EXTINF` line. The name starts after the first comma outside quotes. */
    private fun parseExtInf(line: String): Pair<String?, String?> {
        val body = line.substringAfter(':', "")
        var inQuotes = false
        var commaAt = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) {
                commaAt = i
                break
            }
        }
        val head = if (commaAt >= 0) body.substring(0, commaAt) else body
        val name = if (commaAt >= 0) body.substring(commaAt + 1).trim().ifEmpty { null } else null
        val group = groupTitle.find(head)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        return name to group
    }

    private fun resolve(base: String, reference: String): String? {
        val candidate = reference.replace(" ", "%20")
        val resolved = if (absoluteUrl.containsMatchIn(candidate)) {
            candidate
        } else {
            runCatching { URI(base).resolve(candidate).toString() }.getOrNull() ?: return null
        }
        val scheme = runCatching { URI(resolved).scheme?.lowercase() }.getOrNull()
        return if (scheme == "http" || scheme == "https") resolved else null
    }

    private fun fallbackName(url: String, position: Int): String {
        val last = runCatching { URI(url).path }.getOrNull()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return last ?: "#$position"
    }
}
