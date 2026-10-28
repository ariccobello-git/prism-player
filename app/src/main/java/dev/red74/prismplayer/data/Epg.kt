package dev.red74.prismplayer.data

import java.net.URI
import org.xmlpull.v1.XmlPullParser

/** One programme of the guide. */
data class Programme(val title: String, val startMs: Long, val endMs: Long, val description: String?)

/** What is on now and what follows, for one channel. */
data class NowNext(val now: Programme?, val next: Programme?) {
    fun progress(nowMs: Long): Float {
        val current = now ?: return 0f
        val length = (current.endMs - current.startMs).coerceAtLeast(1L)
        return ((nowMs - current.startMs).toFloat() / length).coerceIn(0f, 1f)
    }
}

/** Programme guide kept in memory: only the channels the playlist has and only a window around "now". */
class EpgData(
    private val byId: Map<String, List<Programme>>,
    private val idByName: Map<String, String>,
) {
    val isEmpty: Boolean get() = byId.isEmpty()

    fun programmes(channel: Channel): List<Programme> {
        val direct = channel.tvgId?.lowercase()?.let { byId[it] }
        if (direct != null) return direct
        val byName = normalize(channel.tvgName)?.let { idByName[it] }
            ?: normalize(channel.name)?.let { idByName[it] }
        return byName?.let { byId[it] }.orEmpty()
    }

    fun nowNext(channel: Channel, nowMs: Long): NowNext {
        val list = programmes(channel)
        val currentIndex = list.indexOfFirst { it.startMs <= nowMs && nowMs < it.endMs }
        return when {
            currentIndex >= 0 -> NowNext(list[currentIndex], list.getOrNull(currentIndex + 1))
            else -> NowNext(null, list.firstOrNull { it.startMs > nowMs })
        }
    }

    companion object {
        val Empty = EpgData(emptyMap(), emptyMap())

        fun normalize(name: String?): String? =
            name?.lowercase()?.filter { it.isLetterOrDigit() }?.ifEmpty { null }
    }
}

/** Reads an XMLTV guide as a stream, keeping little: wanted channels, a time window, title and description. */
object XmltvParser {
    fun parse(
        parser: XmlPullParser,
        wantedIds: Set<String>,
        wantedNames: Set<String>,
        windowStartMs: Long,
        windowEndMs: Long,
        keepGoing: () -> Boolean = { true },
    ): EpgData {
        val programmes = HashMap<String, ArrayList<Programme>>()
        val idByName = HashMap<String, String>()
        val acceptedIds = HashSet<String>()
        var events = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (++events % 2000 == 0 && !keepGoing()) break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser, wantedIds, wantedNames, acceptedIds, idByName)
                    "programme" -> readProgramme(parser, acceptedIds, windowStartMs, windowEndMs)?.let { (id, programme) ->
                        programmes.getOrPut(id) { ArrayList() } += programme
                    }
                }
            }
            event = parser.next()
        }
        val sorted = programmes.mapValues { (_, list) -> list.sortedBy { it.startMs } }
        return EpgData(sorted, idByName)
    }

    private fun readChannel(
        parser: XmlPullParser,
        wantedIds: Set<String>,
        wantedNames: Set<String>,
        acceptedIds: MutableSet<String>,
        idByName: MutableMap<String, String>,
    ) {
        val id = parser.getAttributeValue(null, "id")?.lowercase() ?: return
        var accepted = id in wantedIds
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                val normalized = EpgData.normalize(runCatching { parser.nextText() }.getOrNull())
                if (normalized != null && normalized in wantedNames) {
                    accepted = true
                    idByName[normalized] = id
                }
            }
        }
        if (accepted) acceptedIds += id
    }

    private fun readProgramme(
        parser: XmlPullParser,
        acceptedIds: Set<String>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Pair<String, Programme>? {
        val id = parser.getAttributeValue(null, "channel")?.lowercase()
        val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
        val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
        val depth = parser.depth
        var title: String? = null
        var description: String? = null
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title == null) title = runCatching { parser.nextText() }.getOrNull()
                    "desc" -> if (description == null) description = runCatching { parser.nextText() }.getOrNull()
                }
            }
        }
        if (id == null || id !in acceptedIds || start == null || stop == null) return null
        if (stop < windowStartMs || start > windowEndMs) return null
        val name = TextCleaner.clean(title)
        if (name.isEmpty()) return null
        return id to Programme(name, start, stop, TextCleaner.cleanOrNull(description)?.take(600))
    }
}

/** XMLTV timestamps look like `20261028031500 +0100`. */
object XmltvTime {
    fun parse(text: String?): Long? {
        if (text == null) return null
        val value = text.trim()
        if (value.length < 14 || !value.take(14).all { it.isDigit() }) return null
        val year = value.substring(0, 4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.substring(6, 8).toInt()
        val hour = value.substring(8, 10).toInt()
        val minute = value.substring(10, 12).toInt()
        val second = value.substring(12, 14).toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 24 || minute > 59 || second > 60) return null

        var offsetSeconds = 0
        val rest = value.substring(14).trim()
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-') && rest.substring(1, 5).all { it.isDigit() }) {
            val magnitude = rest.substring(1, 3).toInt() * 3600 + rest.substring(3, 5).toInt() * 60
            offsetSeconds = if (rest[0] == '-') -magnitude else magnitude
        }
        val seconds = daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second - offsetSeconds
        return seconds * 1000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}

/** Where the guide lives: the address declared in the playlist, or the one an Xtream-style panel offers. */
object EpgUrl {
    fun derive(playlistUrl: String, declared: String?): String? {
        if (!declared.isNullOrBlank()) return declared
        val uri = runCatching { URI(playlistUrl) }.getOrNull() ?: return null
        val path = uri.path.orEmpty()
        if (!path.endsWith("/get.php")) return null
        val query = uri.rawQuery ?: return null
        val pairs = query.split('&').mapNotNull {
            val index = it.indexOf('=')
            if (index <= 0) null else it.substring(0, index) to it.substring(index + 1)
        }.toMap()
        val user = pairs["username"] ?: return null
        val password = pairs["password"] ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://$host$port${path.removeSuffix("get.php")}xmltv.php?username=$user&password=$password"
    }
}
