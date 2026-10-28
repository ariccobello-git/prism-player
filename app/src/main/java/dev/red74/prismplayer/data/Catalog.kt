package dev.red74.prismplayer.data

import java.security.MessageDigest

/** Entries of one category, in the order the list declares them. An empty [name] means "no category". */
data class Category(val name: String, val items: List<Channel>)

/** One series: its episodes sorted by season and episode. */
data class Show(val title: String, val logo: String?, val group: String, val episodes: List<Channel>) {
    val seasons: List<Int> = episodes.map { it.season }.distinct().sorted()
    fun episodesOf(season: Int): List<Channel> = episodes.filter { it.season == season }
}

data class SeriesCategory(val name: String, val shows: List<Show>)

/**
 * A playlist organised the way the list itself describes it: live channels, films and series, each split in the
 * categories (`group-title`) found in the file, in file order.
 */
class Catalog(
    val live: List<Category>,
    val movies: List<Category>,
    val series: List<SeriesCategory>,
    val epgUrl: String?,
    val truncated: Boolean,
    val totalEntries: Int,
) {
    val isEmpty: Boolean get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()

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

    fun ofShow(show: Show): String = hash("show|${show.group}|${show.title}")

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
