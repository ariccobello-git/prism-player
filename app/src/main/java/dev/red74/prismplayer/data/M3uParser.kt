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
    fun parse(reader: BufferedReader, sourceUrl: String, keepChannels: Boolean): Result {
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
        val path = pathOf(url).lowercase()
        return when {
            path.contains("/series/") -> ContentType.SERIES
            path.contains("/movie/") -> ContentType.MOVIE
            else -> {
                val extension = path.substringAfterLast('.', "")
                if (extension in videoFileExtensions) {
                    if (episodeTagAnywhere.containsMatchIn(name)) ContentType.SERIES else ContentType.MOVIE
                } else {
                    ContentType.LIVE
                }
            }
        }
    }

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return ""
        return afterScheme.substring(slash).substringBefore('?').substringBefore('#')
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
        val values = attributes(head)
        return ExtInf(
            name = name,
            group = TextCleaner.cleanOrNull(values["group-title"]),
            logo = values["tvg-logo"]?.ifEmpty { null },
            tvgId = values["tvg-id"]?.ifEmpty { null },
            tvgName = TextCleaner.cleanOrNull(values["tvg-name"]),
        )
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
