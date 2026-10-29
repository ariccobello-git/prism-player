package dev.red74.prismplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class FavoriteListTest {
    @Test
    fun keysAreTheSameAsBeforeTheFasterHash() {
        // Favourites saved by older versions must still match.
        val url = "http://example.invalid/live/1.ts"
        val old = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        assertEquals(old, FavoriteKey.of(Channel(name = "One", url = url)))
    }

    @Test
    fun findsChannelsFilmsAndSeriesInOrderAndDropsOne() = runBlocking {
        val live = Channel(name = "Live", url = "http://example.invalid/a")
        val film = Channel(name = "Film", url = "http://example.invalid/b", type = ContentType.MOVIE)
        val other = Channel(name = "Other", url = "http://example.invalid/c")
        val episode = Channel(name = "E1", url = "http://example.invalid/d", type = ContentType.SERIES, seriesTitle = "Show", season = 1, episode = 1)
        val show = Show("Show", null, "Series", listOf(episode))
        val catalog = Catalog(
            live = listOf(Category("News", listOf(other, live))),
            movies = listOf(Category("Films", listOf(film))),
            series = listOf(SeriesCategory("Series", listOf(show))),
            epgUrl = null,
            truncated = false,
            totalEntries = 4,
        )
        val keys = setOf(FavoriteKey.of(live), FavoriteKey.of(film), FavoriteKey.ofShow(show))
        val list = FavoriteList.find(catalog, keys)
        assertEquals(listOf("Live", "Film", "Show"), list.entries.map { it.channel?.name ?: it.show?.title })
        assertEquals(listOf("Film", "Show"), list.without(FavoriteKey.of(live)).entries.map { it.channel?.name ?: it.show?.title })
        assertEquals(0, FavoriteList.find(catalog, emptySet()).entries.size)
    }
}
