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
    private const val PROGRESS_STEP = 2_500

    private val attribute = Regex("""([A-Za-z0-9_\-]+)="([^"]*)"""")
    private val absoluteUrl = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")
    private val episodeTag = Regex("""(?i)^(.*?)[\s._\-:|]*\bS(\d{1,3})[\s._\-]*E(\d{1,4})\b""")
    private val episodeTagAnywhere = Regex("""(?i)\bS\d{1,3}[\s._\-]*E\d{1,4}\b""")
    private val videoFileExtensions = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "mpg", "mpeg", "m4v")

    /**
     * Outcome of a streaming parse. [count] is the number of valid entries seen; [channels] holds them only
     * when the caller asked to keep them (verification just counts, so it never holds a big list in memory).
     * [epgUrl] is the programme-guide address declared in the `#EXTM3U` header, if any, and
     * [sawPlaylistMarker] tells an empty playlist apart from a page that is not a playlist at all.
     */
    class Result(
        val channels: List<Channel>,
        val count: Int,
        val truncated: Boolean,
        val epgUrl: String? = null,
        val sawPlaylistMarker: Boolean = true,
    )

    fun parse(text: String, sourceUrl: String): List<Channel> =
        parse(BufferedReader(StringReader(text)), sourceUrl, keepChannels = true).channels

    /** Reads line by line, so the size of the playlist is limited by the entry cap, never by a byte count. */
    fun parse(
        reader: BufferedReader,
        sourceUrl: String,
        keepChannels: Boolean,
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        val channels = ArrayList<Channel>()
        val shared = HashMap<String, String>()
        fun share(value: String?): String? = value?.let { shared.getOrPut(it) { it } }
        var count = 0
        var truncated = false
        var epgUrl: String? = null
        var sawMarker = false
        var pending: ExtInf? = null
        var pendingGroup: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    sawMarker = true
                    val attributes = attributes(line)
                    val declared = attributes["url-tvg"] ?: attributes["x-tvg-url"] ?: attributes["tvg-url"]
                    epgUrl = declared?.split(',')?.map { it.trim() }
                        ?.firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                }
                line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) ||
                    line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                    // A single HLS playlist is one stream pointing at the playlist URL itself.
                    val single = Channel(name = PlaylistUrl.displayLabel(sourceUrl), url = sourceUrl)
                    return Result(listOf(single), 1, false, epgUrl, true)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    sawMarker = true
                    pending = parseExtInf(line)
                    pendingGroup = share(pending.group)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = share(TextCleaner.cleanOrNull(line.substringAfter(':')))
                }
                line.startsWith("#") -> Unit
                else -> {
                    val resolved = resolve(sourceUrl, line)
                    if (resolved != null) {
                        count++
                        if (onProgress != null && count % PROGRESS_STEP == 0) onProgress(count)
                        if (keepChannels) {
                            channels += buildChannel(resolved, pending, pendingGroup, count, ::share)
                        }
                        if (count >= MAX_CHANNELS) {
                            truncated = true
                            break
                        }
                    }
                    pending = null
                    pendingGroup = null
                }
            }
        }
        return Result(channels, count, truncated, epgUrl, sawMarker || count > 0)
    }

    private class ExtInf(
        val name: String?,
        val group: String?,
        val logo: String?,
        val tvgId: String?,
        val tvgName: String?,
    )

    private fun buildChannel(
        url: String,
        info: ExtInf?,
        group: String?,
        position: Int,
        share: (String?) -> String?,
    ): Channel {
        val name = info?.name ?: info?.tvgName ?: fallbackName(url, position)
        val type = classify(url, name)
        var seriesTitle: String? = null
        var season = 0
        var episode = 0
        if (type == ContentType.SERIES) {
            val match = episodeTag.find(name)
            if (match != null) {
                seriesTitle = match.groupValues[1].trim().trimEnd('-', ':', '|', '.', '_').trim().ifEmpty { name }
                season = match.groupValues[2].toIntOrNull() ?: 0
                episode = match.groupValues[3].toIntOrNull() ?: 0
            } else {
                seriesTitle = name
                season = 1
            }
            seriesTitle = share(seriesTitle)
        }
        return Channel(
            name = name,
            url = url,
            group = group,
            logo = info?.logo,
            tvgId = info?.tvgId,
            tvgName = info?.tvgName,
            type = type,
            seriesTitle = seriesTitle,
            season = season,
            episode = episode,
        )
    }

    /** Live, film or episode: first by the path an IPTV panel uses, then by the file type and the episode tag. */
    internal fun classify(url: String, name: String): ContentType {
        // Looks only at the path part of the address, without building temporary strings: this runs once per entry.
        val schemeEnd = url.indexOf("://")
        val pathStart = url.indexOf('/', if (schemeEnd >= 0) schemeEnd + 3 else 0)
        if (pathStart < 0) return ContentType.LIVE
        var pathEnd = url.length
        for (i in pathStart until url.length) {
            val c = url[i]
            if (c == '?' || c == '#') { pathEnd = i; break }
        }
        if (regionContains(url, pathStart, pathEnd, "/series/")) return ContentType.SERIES
        if (regionContains(url, pathStart, pathEnd, "/movie/")) return ContentType.MOVIE
        val dot = url.lastIndexOf('.', pathEnd - 1)
        val slash = url.lastIndexOf('/', pathEnd - 1)
        if (dot > slash && dot >= pathStart) {
            val extension = url.substring(dot + 1, pathEnd).lowercase()
            if (extension in videoFileExtensions) {
                return if (episodeTagAnywhere.containsMatchIn(name)) ContentType.SERIES else ContentType.MOVIE
            }
        }
        return ContentType.LIVE
    }

    private fun regionContains(text: String, from: Int, to: Int, needle: String): Boolean {
        val at = text.indexOf(needle, from, ignoreCase = true)
        return at >= 0 && at + needle.length <= to
    }

    private fun attributes(text: String): Map<String, String> {
        val found = HashMap<String, String>()
        for (match in attribute.findAll(text)) {
            found[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
        }
        return found
    }

    /** Parses an `#EXTINF` line. The display name starts after the first comma outside quotes. */
    private fun parseExtInf(line: String): ExtInf {
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
        val name = if (commaAt >= 0) TextCleaner.cleanOrNull(body.substring(commaAt + 1)) else null
        return ExtInf(
            name = name,
            group = TextCleaner.cleanOrNull(attrValue(head, "group-title")),
            logo = attrValue(head, "tvg-logo")?.ifEmpty { null },
            tvgId = attrValue(head, "tvg-id")?.ifEmpty { null },
            tvgName = TextCleaner.cleanOrNull(attrValue(head, "tvg-name")),
        )
    }

    /**
     * Reads one `key="value"` attribute by scanning for it. A list can have hundreds of thousands of entries and a
     * TV stick is slow, so this avoids the regex and the map the general reader builds for every line.
     */
    private fun attrValue(text: String, key: String): String? {
        var from = 0
        while (true) {
            val at = text.indexOf(key, from, ignoreCase = true)
            if (at < 0) return null
            val end = at + key.length
            if ((at == 0 || text[at - 1].isWhitespace()) && end + 1 < text.length && text[end] == '=') {
                val quote = text[end + 1]
                if (quote == '"' || quote == '\'') {
                    val close = text.indexOf(quote, end + 2)
                    return if (close < 0) null else text.substring(end + 2, close).trim()
                }
            }
            from = end
        }
    }

    private fun resolve(base: String, reference: String): String? {
        val candidate = if (reference.indexOf(' ') >= 0) reference.replace(" ", "%20") else reference
        // The common case (an absolute http link) needs no URI parsing, which is slow and strict about odd characters.
        if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
            return candidate
        }
        if (absoluteUrl.containsMatchIn(candidate)) return null
        val resolved = runCatching { URI(base).resolve(candidate).toString() }.getOrNull() ?: return null
        return if (resolved.startsWith("http://", ignoreCase = true) || resolved.startsWith("https://", ignoreCase = true)) resolved else null
    }

    private fun fallbackName(url: String, position: Int): String {
        val last = runCatching { URI(url).path }.getOrNull()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return last ?: "#$position"
    }
}
