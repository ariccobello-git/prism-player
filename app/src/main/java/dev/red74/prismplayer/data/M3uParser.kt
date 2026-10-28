package dev.red74.prismplayer.data

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
    const val MAX_CHANNELS = 20_000

    private val groupTitle = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val absoluteUrl = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")

    fun parse(text: String, sourceUrl: String): List<Channel> {
        val lines = text.lineSequence()
            .map { it.trim().removePrefix("﻿") }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return emptyList()

        val isHlsPlaylist = lines.any {
            it.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) ||
                it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                it.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true)
        }
        if (isHlsPlaylist) {
            return listOf(Channel(name = PlaylistUrl.displayLabel(sourceUrl), url = sourceUrl))
        }

        val channels = ArrayList<Channel>()
        var pendingName: String? = null
        var pendingGroup: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val info = parseExtInf(line)
                    pendingName = info.first
                    pendingGroup = info.second
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val resolved = resolve(sourceUrl, line)
                    if (resolved != null) {
                        val name = pendingName ?: fallbackName(resolved, channels.size + 1)
                        channels += Channel(name = name, url = resolved, group = pendingGroup)
                        if (channels.size >= MAX_CHANNELS) break
                    }
                    pendingName = null
                    pendingGroup = null
                }
            }
        }
        return channels
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
