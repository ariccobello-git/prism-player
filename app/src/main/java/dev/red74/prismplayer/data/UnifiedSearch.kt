package dev.red74.prismplayer.data

import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A programme of the guide found by a search, with the channels of the list that carry it (best first). */
class EpgHit(val programme: Programme, val channels: List<Channel>) {
    fun isLive(nowMs: Long): Boolean = programme.startMs <= nowMs && nowMs < programme.endMs
}

/** What one search found, in the four kinds the page shows. */
class SearchAllResult(
    val channels: List<Channel>,
    val movies: List<Channel>,
    val shows: List<Show>,
    val programmes: List<EpgHit>,
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && shows.isEmpty() && programmes.isEmpty()

    companion object {
        val Empty = SearchAllResult(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * The programme guide ready to be searched by title. It is built once, in the background, when the search page is
 * first opened (never at start-up), from the guide already in memory: no network, nothing stored.
 */
class EpgIndex private constructor(private val entries: List<Entry>) {
    private class Entry(val words: List<String>, val programme: Programme, val channels: List<Channel>)

    val size: Int get() = entries.size

    /** Programmes not over yet whose title has a word starting with every word typed: on air first, then by start. */
    fun search(query: String, nowMs: Long, limit: Int): List<EpgHit> {
        val typed = UnifiedSearch.words(query)
        if (typed.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { it.programme.endMs > nowMs && UnifiedSearch.matches(it.words, typed) }
            .sortedWith(compareBy<Entry> { it.programme.startMs > nowMs }.thenBy { it.programme.startMs })
            .take(limit)
            .map { EpgHit(it.programme, it.channels) }
            .toList()
    }

    companion object {
        val Empty = EpgIndex(emptyList())

        /** Channels sharing one guide entry are looked at once; copies of one programme on several channels become one hit. */
        suspend fun build(epg: EpgData, channels: List<Channel>, nowMs: Long): EpgIndex {
            if (epg.isEmpty || channels.isEmpty()) return Empty
            val groups = IdentityHashMap<List<Programme>, MutableList<Channel>>()
            for ((i, channel) in channels.withIndex()) {
                if (i % 512 == 0) currentCoroutineContext().ensureActive()
                val list = epg.programmes(channel)
                if (list.isNotEmpty()) groups.getOrPut(list) { ArrayList() } += channel
            }
            val merged = LinkedHashMap<String, Pair<Programme, MutableList<Channel>>>()
            for ((list, owners) in groups) {
                currentCoroutineContext().ensureActive()
                for (programme in list) {
                    if (programme.endMs <= nowMs) continue
                    val key = (EpgData.normalize(programme.title) ?: continue) + "|" + programme.startMs / 300_000L
                    val found = merged[key]
                    if (found == null) merged[key] = programme to owners.toMutableList() else found.second += owners
                }
            }
            val entries = merged.values.map { (programme, owners) ->
                val best = owners.distinctBy { it.url }.sortedBy { if (isLowQuality(it.name)) 1 else 0 }.take(HighlightEvent.MAX_CHANNELS)
                Entry(UnifiedSearch.words(programme.title), programme, best)
            }
            return EpgIndex(entries)
        }

        private fun isLowQuality(name: String): Boolean = Highlights.clean(name).let { it.endsWith(" sd") || " sd " in it }
    }
}

/**
 * One search over everything the list has: live channels, films, series and programmes of the guide. Channels, films
 * and series are looked up by the list's own word index (see [Catalog.search]); programmes by [EpgIndex].
 */
object UnifiedSearch {
    /** The shortest search that is run (one letter would match half the list). */
    const val MIN_LENGTH = 2

    /** Results per kind. */
    const val PER_KIND = 12

    /** Lower case words of a text, letters and digits only. */
    fun words(text: String): List<String> = Highlights.clean(text).split(' ').filter { it.isNotEmpty() }

    /** True when every word typed is the start of a word of [titleWords]. */
    fun matches(titleWords: List<String>, typed: List<String>): Boolean =
        typed.all { t -> titleWords.any { it.startsWith(t) } }

    suspend fun run(catalog: Catalog, index: EpgIndex, query: String, nowMs: Long): SearchAllResult {
        val text = query.trim()
        if (words(text).sumOf { it.length } < MIN_LENGTH) return SearchAllResult.Empty
        val channels = catalog.search(ContentType.LIVE, text, PER_KIND)
        currentCoroutineContext().ensureActive()
        val movies = catalog.search(ContentType.MOVIE, text, PER_KIND)
        currentCoroutineContext().ensureActive()
        val shows = catalog.searchShows(text, PER_KIND)
        currentCoroutineContext().ensureActive()
        return SearchAllResult(channels, movies, shows, index.search(text, nowMs, PER_KIND))
    }
}
