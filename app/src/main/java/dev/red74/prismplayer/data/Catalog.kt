package dev.red74.prismplayer.data

import java.security.MessageDigest

/** Entries of one category, in the order the list declares them. An empty [name] means "no category". */
class Category(val name: String, val items: List<Channel>)

/**
 * One series: its episodes sorted by season and episode. The episodes are read only when the series is opened
 * ([episodes] is computed once, on first use), so a list with thousands of series does not load them all.
 */
class Show(val title: String, val logo: String?, val group: String, loadEpisodes: () -> List<Channel>) {
    constructor(title: String, logo: String?, group: String, episodes: List<Channel>) :
        this(title, logo, group, { episodes })

    val episodes: List<Channel> by lazy(loadEpisodes)
    val seasons: List<Int> by lazy { episodes.map { it.season }.distinct().sorted() }
    fun episodesOf(season: Int): List<Channel> = episodes.filter { it.season == season }

    override fun equals(other: Any?): Boolean = other is Show && other.title == title && other.group == group
    override fun hashCode(): Int = 31 * title.hashCode() + group.hashCode()
}

class SeriesCategory(val name: String, val shows: List<Show>)

/**
 * A playlist organised the way the list itself describes it: live channels, films and series, each split in the
 * categories (`group-title`) found in the file, in file order.
 *
 * This base class keeps everything in memory. [dev.red74.prismplayer.data.CatalogDb] answers the same questions
 * from the on-device list database, reading only what the screen shows; the screens only use the methods below,
 * never assume the entries are in memory.
 */
open class Catalog(
    val live: List<Category>,
    val movies: List<Category>,
    val series: List<SeriesCategory>,
    val epgUrl: String?,
    val truncated: Boolean,
    val totalEntries: Int,
) {
    val isEmpty: Boolean get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()

    /** True when [sorted] and [sortedShows] are quick (a database query), so they can run while drawing. */
    open val quickSort: Boolean get() = false

    private fun categoriesOf(type: ContentType): List<Category> = if (type == ContentType.MOVIE) movies else live

    /** The entries of the given categories ([type] LIVE or MOVIE), one category after the other. */
    open fun channels(type: ContentType, categories: List<Int>): List<Channel> {
        val all = categoriesOf(type)
        return if (categories.size == 1) all[categories[0]].items else categories.flatMap { all[it].items }
    }

    open fun shows(categories: List<Int>): List<Show> =
        if (categories.size == 1) series[categories[0]].shows else categories.flatMap { series[it].shows }

    /** [items] (from [channels] or a search) in the order [mode] asks for. */
    open fun sorted(items: List<Channel>, mode: SortMode): List<Channel> = items.sortedBy(mode) { it.name }

    open fun sortedShows(items: List<Show>, mode: SortMode): List<Show> = items.sortedBy(mode) { it.title }

    /** Entries of [type] whose name contains [query], at most [limit]. */
    open fun search(type: ContentType, query: String, limit: Int): List<Channel> =
        categoriesOf(type).asSequence().flatMap { it.items.asSequence() }
            .filter { it.name.contains(query, ignoreCase = true) }.take(limit).toList()

    open fun searchShows(query: String, limit: Int): List<Show> =
        series.asSequence().flatMap { it.shows.asSequence() }
            .filter { it.title.contains(query, ignoreCase = true) }.take(limit).toList()

    /** The entry of [type] with this stream address, if the list has it. */
    open fun find(type: ContentType, url: String): Channel? =
        categoriesOf(type).firstNotNullOfOrNull { c -> c.items.firstOrNull { it.url == url } }

    open fun findShow(group: String?, title: String): Show? =
        series.asSequence().filter { group == null || it.name == group }
            .flatMap { it.shows.asSequence() }.firstOrNull { it.title == title }

    /** Live channels, then films, whose [FavoriteKey] is in [keys]. */
    open fun favorites(keys: Set<String>): List<Channel> {
        if (keys.isEmpty()) return emptyList()
        val found = ArrayList<Channel>()
        for (category in live) for (c in category.items) if (FavoriteKey.of(c) in keys) found += c
        for (category in movies) for (c in category.items) if (FavoriteKey.of(c) in keys) found += c
        return found
    }

    open fun favoriteShows(keys: Set<String>): List<Show> {
        if (keys.isEmpty()) return emptyList()
        return series.flatMap { it.shows }.filter { FavoriteKey.ofShow(it) in keys }
    }

    /** Every live channel, for matching the programme guide. */
    open fun allLive(): List<Channel> = live.flatMap { it.items }

    /** Releases what the catalog holds open (nothing for the in-memory one). */
    open fun close() = Unit

    companion object {
        val Empty = Catalog(emptyList(), emptyList(), emptyList(), null, false, 0)
    }
}

object CatalogBuilder {
    fun build(channels: List<Channel>, epgUrl: String?, truncated: Boolean): Catalog {
        val live = LinkedHashMap<String, MutableList<Channel>>()
        val movies = LinkedHashMap<String, MutableList<Channel>>()
        val series = LinkedHashMap<String, LinkedHashMap<String, MutableList<Channel>>>()

        for (channel in channels) {
            val group = channel.group.orEmpty()
            when (channel.type) {
                ContentType.LIVE -> live.getOrPut(group) { ArrayList() } += channel
                ContentType.MOVIE -> movies.getOrPut(group) { ArrayList() } += channel
                ContentType.SERIES -> series
                    .getOrPut(group) { LinkedHashMap() }
                    .getOrPut(channel.seriesTitle ?: channel.name) { ArrayList() } += channel
            }
        }

        return Catalog(
            live = live.map { Category(it.key, it.value) },
            movies = movies.map { Category(it.key, it.value) },
            series = series.map { (group, shows) ->
                SeriesCategory(
                    name = group,
                    shows = shows.map { (title, episodes) ->
                        val sorted = episodes.sortedWith(compareBy<Channel> { it.season }.thenBy { it.episode })
                        Show(title, sorted.firstNotNullOfOrNull { it.logo }, group, sorted)
                    },
                )
            },
            epgUrl = epgUrl,
            truncated = truncated,
            totalEntries = channels.size,
        )
    }
}

/** Stable, non-reversible identifiers for favourites, so no playlist URL is ever written to preferences. */
object FavoriteKey {
    fun of(channel: Channel): String = hash(channel.url)

    fun ofUrl(url: String): String = hash(url)

    fun ofShow(show: Show): String = hash("show|${show.group}|${show.title}")

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
