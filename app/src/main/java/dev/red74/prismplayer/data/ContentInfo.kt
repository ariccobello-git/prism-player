package dev.red74.prismplayer.data

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The plot of a film or series, asked of the IPTV panel only when its page is opened (playlists almost never carry
 * one). Works with Xtream-style panels, whose stream addresses are `/movie/<user>/<password>/<id>.<ext>` and
 * `/series/<user>/<password>/<id>.<ext>`: a film is asked for by its id; series are listed once per panel and
 * matched by title. Null means "no description".
 *
 * Every answer is kept on the stick (see [ContentInfoStore]), so a page seen once opens with its plot at once, even
 * after a restart. An answer older than its age limit is still shown straight away and asked for again in the
 * background, so the next visit has the update. Requests go through [PanelLine]: one at a time, never while a stream is open.
 */
object ContentInfo {
    /** Plots of films almost never change. */
    const val FILM_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    /** New series and new episodes appear every day. */
    const val SERIES_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private var store: ContentInfoStore? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = Collections.synchronizedSet(HashSet<String>())

    fun init(context: Context) {
        if (store == null) store = ContentInfoStore(File(context.applicationContext.noBackupFilesDir, "content_info.db"))
    }

    suspend fun filmPlot(streamUrl: String): String? {
        val address = PanelAddress.vodInfo(streamUrl) ?: return null
        val key = InfoKeys.film(streamUrl)
        val saved = read(key)
        if (saved != null) {
            if (InfoKeys.isStale(saved.savedAt, System.currentTimeMillis(), FILM_MAX_AGE_MS)) {
                refreshLater(key) { fetchFilm(address, key) }
            }
            return saved.a
        }
        // A failed request is not remembered, so the next visit asks again.
        return fetchFilm(address, key)?.a
    }

    suspend fun seriesPlot(episodeUrl: String?, title: String): String? =
        seriesEntry(episodeUrl, title)?.plot

    /** Plot, length and date of one episode: the series is found by title, then its episodes are asked for once. */
    suspend fun episodeInfo(episodeUrl: String, title: String): EpisodeInfo? {
        val seriesId = seriesEntry(episodeUrl, title)?.id ?: return null
        val address = PanelAddress.seriesInfo(episodeUrl, seriesId) ?: return null
        val episodeId = PanelAddress.streamId(episodeUrl) ?: return null
        val group = InfoKeys.episodes(address)
        if (!ensureGroup(group) { fetchEpisodes(address, group) }) return null
        val row = read(InfoKeys.row(group, episodeId)) ?: return null
        return EpisodeInfo(row.a, row.b, row.c)
    }

    private suspend fun seriesEntry(episodeUrl: String?, title: String): PanelJson.SeriesEntry? {
        val address = episodeUrl?.let { PanelAddress.seriesList(it) } ?: return null
        val group = InfoKeys.seriesList(address)
        if (!ensureGroup(group) { fetchSeries(address, group) }) return null
        val row = read(InfoKeys.row(group, PanelJson.titleKey(title))) ?: return null
        return PanelJson.SeriesEntry(row.a, row.b)
    }

    /** True when the group is on the stick (asking the panel first if it never was); a stale one is renewed later. */
    private suspend fun ensureGroup(group: String, fetch: suspend () -> Boolean): Boolean {
        val marker = read(group) ?: return fetch()
        if (InfoKeys.isStale(marker.savedAt, System.currentTimeMillis(), SERIES_MAX_AGE_MS)) refreshLater(group) { fetch() }
        return true
    }

    private suspend fun fetchFilm(address: String, key: String): ContentInfoStore.Row? = PanelLine.request(LineKey.of(address)) {
        withContext(Dispatchers.IO) {
            val answer = runCatching { request(address) { PanelJson.filmPlot(it) } }
            if (answer.isFailure) return@withContext cutOrNull<ContentInfoStore.Row>(answer)
            // "No plot" is saved too, so a film without one does not ask the panel on every visit.
            val row = ContentInfoStore.Row(answer.getOrNull(), null, null, System.currentTimeMillis())
            runCatching { store?.put(key, row) }
            row
        }
    }

    private suspend fun fetchSeries(address: String, group: String): Boolean = PanelLine.request(LineKey.of(address)) {
        withContext(Dispatchers.IO) {
            if (isFresh(group)) return@withContext true
            val answer = runCatching { request(address) { PanelJson.series(it) } }
            val entries = answer.getOrNull() ?: return@withContext cutOrNull<Boolean>(answer) ?: false
            runCatching { store?.replaceGroup(group, entries.mapValues { (_, e) -> ContentInfoStore.Row(e.id, e.plot, null, 0) }) }.isSuccess
        }
    }

    private suspend fun fetchEpisodes(address: String, group: String): Boolean = PanelLine.request(LineKey.of(address)) {
        withContext(Dispatchers.IO) {
            if (isFresh(group)) return@withContext true
            val answer = runCatching { request(address) { PanelJson.episodes(it) } }
            val episodes = answer.getOrNull() ?: return@withContext cutOrNull<Boolean>(answer) ?: false
            runCatching { store?.replaceGroup(group, episodes.mapValues { (_, e) -> ContentInfoStore.Row(e.plot, e.duration, e.released, 0) }) }.isSuccess
        }
    }

    /** A request cut by a stream throws, so [PanelLine] asks again once the stream ends; a real failure is null. */
    private fun <T> cutOrNull(answer: Result<*>): T? {
        if (PanelLine.cut) throw answer.exceptionOrNull() ?: IllegalStateException("cut")
        return null
    }

    /** Another request may have filled the group while this one waited for its turn. */
    private fun isFresh(group: String): Boolean {
        val marker = runCatching { store?.get(group) }.getOrNull() ?: return false
        return !InfoKeys.isStale(marker.savedAt, System.currentTimeMillis(), SERIES_MAX_AGE_MS)
    }

    private suspend fun read(key: String): ContentInfoStore.Row? =
        withContext(Dispatchers.IO) { runCatching { store?.get(key) }.getOrNull() }

    private fun refreshLater(key: String, work: suspend () -> Unit) {
        if (!refreshing.add(key)) return
        background.launch {
            try {
                work()
            } finally {
                refreshing.remove(key)
            }
        }
    }

    private fun <T> request(address: String, read: (Reader) -> T): T {
        var last: Exception? = null
        for (agent in AGENTS) {
            val connection = URL(address).openConnection() as HttpURLConnection
            PanelLine.opened(connection)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", agent)
                connection.setRequestProperty("Connection", "close")
                if (connection.responseCode !in 200..299) {
                    last = IllegalStateException("HTTP ${connection.responseCode}")
                    continue
                }
                return connection.inputStream.use { raw -> read(reader(raw)) }
            } catch (e: Exception) {
                if (PanelLine.cut) throw e
                last = e
            } finally {
                connection.disconnect()
            }
        }
        throw last ?: IllegalStateException("no answer")
    }

    private fun reader(raw: InputStream): Reader {
        val buffered = raw.buffered(64 * 1024)
        buffered.mark(2)
        val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gzipped) GZIPInputStream(buffered, 64 * 1024) else buffered
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024)
    }

    private val AGENTS = listOf(HttpAgent.LIST_AGENTS[0], "okhttp/4.12.0", "IPTVSmartersPro")
}

/**
 * Keys of the saved answers. Addresses carry the list's credentials, so only their hashes (see [FavoriteKey]) are
 * used: `f:<film>`, a group marker `S:<series list>` or `E:<one series' episodes>` holding the time it was saved,
 * and the group's rows under `<marker>:<title or episode id>`.
 */
object InfoKeys {
    fun film(streamUrl: String): String = "f:" + FavoriteKey.ofUrl(streamUrl)
    fun seriesList(address: String): String = "S:" + FavoriteKey.ofUrl(address)
    fun episodes(address: String): String = "E:" + FavoriteKey.ofUrl(address)
    fun row(group: String, item: String): String = "$group:$item"

    /** The first key after every row of [group]: rows are `group:` + anything, and ';' follows ':'. */
    fun groupEnd(group: String): String = "$group;"

    /** Too old, or saved "in the future" (the clock was changed): ask again. */
    fun isStale(savedAt: Long, now: Long, maxAgeMs: Long): Boolean = now - savedAt !in 0..maxAgeMs
}

/** What the panel says about one episode; each part may be missing. */
data class EpisodeInfo(val plot: String?, val duration: String?, val released: String?)

/** Builds the panel requests for [ContentInfo] from a stream address; null for anything not Xtream-style. */
object PanelAddress {
    fun vodInfo(streamUrl: String): String? {
        val (base, user, password, id) = parts(streamUrl, "movie") ?: return null
        return "$base/player_api.php?username=$user&password=$password&action=get_vod_info&vod_id=$id"
    }

    fun seriesList(episodeUrl: String): String? {
        val (base, user, password, _) = parts(episodeUrl, "series") ?: return null
        return "$base/player_api.php?username=$user&password=$password&action=get_series"
    }

    fun seriesInfo(episodeUrl: String, seriesId: String): String? {
        val (base, user, password, _) = parts(episodeUrl, "series") ?: return null
        if (seriesId.isEmpty() || !seriesId.all { it.isDigit() }) return null
        return "$base/player_api.php?username=$user&password=$password&action=get_series_info&series_id=$seriesId"
    }

    /** The number at the end of an Xtream episode or film address. */
    fun streamId(url: String): String? = (parts(url, "series") ?: parts(url, "movie"))?.get(3)

    private fun parts(url: String, kind: String): List<String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.rawAuthority ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val at = segments.indexOfLast { it.equals(kind, ignoreCase = true) }
        if (at < 0 || segments.size != at + 4) return null
        val id = segments[at + 3].substringBefore('.')
        if (id.isEmpty() || !id.all { it.isDigit() }) return null
        val prefix = segments.subList(0, at).joinToString("") { "/$it" }
        return listOf("$scheme://$host$prefix", segments[at + 1], segments[at + 2], id)
    }
}

/**
 * Reads only what is needed from the panel's JSON answers, as a stream: the series list of a large panel can be
 * many megabytes, so it is never held as one string or one tree.
 */
object PanelJson {
    private const val MAX_PLOT = 900

    /** `get_vod_info`: the plot (or description) of the film. */
    fun filmPlot(reader: Reader): String? {
        var plot: String? = null
        var description: String? = null
        JsonScan(reader).run { _, key, value ->
            when (key.lowercase()) {
                "plot" -> if (plot == null) plot = clean(value)
                "description" -> if (description == null) description = clean(value)
            }
        }
        return plot ?: description
    }

    class SeriesEntry(val id: String?, val plot: String?)

    /** `get_series`: series title (as [titleKey]) to its panel id and plot. */
    fun series(reader: Reader): Map<String, SeriesEntry> {
        val out = HashMap<String, SeriesEntry>()
        var name: String? = null
        var plot: String? = null
        var id: String? = null
        JsonScan(reader, numbers = true).run(
            onEnd = { depth ->
                if (depth == 2) {
                    val n = name
                    if (n != null && (plot != null || id != null)) out.putIfAbsent(titleKey(n), SeriesEntry(id, plot))
                    name = null
                    plot = null
                    id = null
                }
            },
        ) { depth, key, value ->
            if (depth == 2) when (key.lowercase()) {
                "name" -> name = value
                "plot" -> plot = clean(value)
                "series_id" -> id = value
            }
        }
        return out
    }

    /** `get_series`: series title (as [titleKey]) to plot, for the series that have one. */
    fun seriesPlots(reader: Reader): Map<String, String> =
        series(reader).mapNotNull { (k, v) -> v.plot?.let { k to it } }.toMap()

    /**
     * `get_series_info`: episode stream id to its details. Episodes sit in `episodes` → season → list, so their own
     * fields are at depth 4 and their `info` at depth 5.
     */
    fun episodes(reader: Reader): Map<String, EpisodeInfo> {
        val out = HashMap<String, EpisodeInfo>()
        var id: String? = null
        var plot: String? = null
        var duration: String? = null
        var released: String? = null
        JsonScan(reader, numbers = true).run(
            onEnd = { depth ->
                if (depth == 4) {
                    id?.let { if (plot != null || duration != null || released != null) out[it] = EpisodeInfo(plot, duration, released) }
                    id = null; plot = null; duration = null; released = null
                }
            },
        ) { depth, key, value ->
            when {
                depth == 4 && key == "id" -> id = value
                depth == 5 -> when (key.lowercase()) {
                    "plot" -> if (plot == null) plot = clean(value)
                    "duration" -> duration = value.trim().takeIf { it.isNotEmpty() && it != "00:00:00" }
                    "releasedate", "air_date" -> if (released == null) released = value.trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        return out
    }

    /** Titles match ignoring case, accents aside, spaces and punctuation ("Dr. House" and "DR HOUSE" are the same). */
    fun titleKey(title: String): String = buildString {
        for (ch in title.lowercase()) if (ch.isLetterOrDigit()) append(ch)
    }

    private fun clean(text: String): String? {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true) || cleaned == "N/A") return null
        return TextCleaner.cleanOrNull(cleaned)?.let { if (it.length > MAX_PLOT) it.take(MAX_PLOT).trimEnd() + "…" else it }
    }
}

/**
 * A small streaming JSON reader: reports every string value with its key and depth (see [run]) and the end of every
 * object. Numbers are reported only with `numbers`, booleans and nulls are skipped. Malformed input simply stops the scan.
 */
internal class JsonScan(private val reader: Reader, private val numbers: Boolean = false) {
    private var peeked = -2

    private fun peek(): Int {
        if (peeked == -2) peeked = reader.read()
        return peeked
    }

    private fun next(): Int = peek().also { peeked = -2 }

    private fun skipSpace() {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0xFEFF) next() else return
        }
    }

    /**
     * Depth counts the objects around a value: in `[{"name":"x"}]` "name" is at depth 2 (the array counts as 1);
     * in `{"info":{"plot":"y"}}` "plot" is at depth 2 too.
     */
    fun run(onEnd: (depth: Int) -> Unit = {}, onString: (depth: Int, key: String, value: String) -> Unit) {
        runCatching { value(0, "", onEnd, onString) }
    }

    private fun value(depth: Int, key: String, onEnd: (Int) -> Unit, onString: (Int, String, String) -> Unit) {
        skipSpace()
        when (peek()) {
            '{'.code -> {
                next()
                val inner = depth + 1
                while (true) {
                    skipSpace()
                    when (peek()) {
                        '}'.code -> { next(); break }
                        ','.code -> { next(); continue }
                        '"'.code -> {
                            val k = string()
                            skipSpace()
                            check(next() == ':'.code)
                            value(inner, k, onEnd, onString)
                        }
                        else -> error("bad object")
                    }
                }
                onEnd(inner)
            }
            '['.code -> {
                next()
                while (true) {
                    skipSpace()
                    when (peek()) {
                        ']'.code -> { next(); break }
                        ','.code -> { next(); continue }
                        -1 -> error("end")
                        else -> value(depth + 1, key, onEnd, onString)
                    }
                }
            }
            '"'.code -> onString(depth, key, string())
            -1 -> error("end")
            else -> {
                // number, true, false, null: numbers are reported only when asked (ids are often numbers)
                val text = StringBuilder()
                while (true) {
                    val c = peek()
                    if (c == -1 || c == ','.code || c == '}'.code || c == ']'.code || c <= ' '.code) break
                    text.append(next().toChar())
                }
                if (numbers && text.isNotEmpty() && (text[0].isDigit() || text[0] == '-')) onString(depth, key, text.toString())
            }
        }
    }

    private fun string(): String {
        check(next() == '"'.code)
        val out = StringBuilder()
        while (true) {
            val c = next()
            when (c) {
                -1 -> error("end")
                '"'.code -> return out.toString()
                '\\'.code -> when (val e = next()) {
                    'n'.code -> out.append('\n')
                    't'.code -> out.append('\t')
                    'r'.code -> out.append('\r')
                    'b'.code, 'f'.code -> out.append(' ')
                    'u'.code -> {
                        var code = 0
                        repeat(4) { code = code * 16 + Character.digit(next(), 16).also { d -> check(d >= 0) } }
                        out.append(code.toChar())
                    }
                    -1 -> error("end")
                    else -> out.append(e.toChar())
                }
                else -> out.append(c.toChar())
            }
        }
    }
}
