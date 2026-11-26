package dev.red74.prismplayer.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Play next" cards: what they hold, what their link carries (never a stream address), and how they are parsed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchNextTest {
    private val key = FavoriteKey.ofUrl("http://provider.example/movie/1.mp4")

    @Test
    fun aLinkGoesRoundTripAndHoldsOnlyTheListAndTheKey() {
        val text = WatchNext.link("list-1", key, series = true)
        assertFalse(text.contains("provider"))
        val back = WatchNext.parse(Uri.parse(text))
        assertNotNull(back)
        assertEquals("list-1", back!!.listId)
        assertEquals(key, back.key)
        assertTrue(back.series)
        assertFalse(WatchNext.parse(Uri.parse(WatchNext.link("l", key, series = false)))!!.series)
    }

    @Test
    fun foreignOrMalformedLinksAreRefused() {
        assertNull(WatchNext.parse(null))
        assertNull(WatchNext.parse(Uri.parse("https://example.com/watch?list=a&key=$key&kind=film")))
        assertNull(WatchNext.parse(Uri.parse("prismplayer://other?list=a&key=$key&kind=film")))
        assertNull(WatchNext.parse(Uri.parse("prismplayer://watch?list=a&key=http://x/y.mp4&kind=film")))
        assertNull(WatchNext.parse(Uri.parse("prismplayer://watch?key=$key&kind=film")))
        assertNull(WatchNext.parse(Uri.parse("prismplayer://watch?list=a&key=$key&kind=other")))
    }

    @Test
    fun aPictureOnTheStreamsServerIsNeverUsed() {
        val stream = "http://provider.example:8080/movie/user/pass/1.mp4"
        assertNull(WatchNext.safeLogo("http://provider.example:8080/logo.png", stream))
        assertNull(WatchNext.safeLogo("ftp://images.example/a.png", stream))
        assertNull(WatchNext.safeLogo("https://user:pw@images.example/a.png", stream))
        assertNull(WatchNext.safeLogo(null, stream))
        assertEquals("https://image.tmdb.org/p/a.jpg", WatchNext.safeLogo("https://image.tmdb.org/p/a.jpg", stream))
    }

    private fun film(n: Int) = Channel("Film $n", "http://x/m$n.mp4", "Cinema", type = ContentType.MOVIE)
    private fun episode(show: Int, n: Int) = Channel(
        "Serie $show S1E$n", "http://x/s$show/$n.mp4", "Serie TV", type = ContentType.SERIES, seriesTitle = "Serie $show", season = 1, episode = n,
    )

    @Test
    fun filmsAndSeriesWithAPointBecomeCardsNewestFirstAndASeriesIsOneCard() {
        val films = (1..3).map(::film)
        val shows = (1..2).map { Show("Serie $it", null, "Serie TV", listOf(episode(it, 1), episode(it, 2))) }
        val catalog = Catalog(emptyList(), listOf(Category("Cinema", films)), listOf(SeriesCategory("Serie TV", shows)), null, false, 5)
        val points = mapOf(
            films[0].url to WatchProgress.Point(600_000L, 6_000_000L, 100L),
            shows[0].episodes[0].url to WatchProgress.Point(300_000L, 2_400_000L, 200L),
            shows[0].episodes[1].url to WatchProgress.Point(900_000L, 2_400_000L, 400L),
            shows[1].episodes[0].url to WatchProgress.Point(100_000L, 2_400_000L, 50L),
        )
        val keys = (films.map { it.url } + shows.flatMap { s -> s.episodes.map { it.url } }).map(FavoriteKey::ofUrl).toSet()
        val cards = WatchNext.entries(catalog, keys) { points[it] }
        assertEquals(listOf("Serie 1", "Film 1", "Serie 2"), cards.map { it.title })
        assertEquals(listOf(true, false, true), cards.map { it.series })
        assertEquals("S1 E2", cards[0].note)
        assertEquals(FavoriteKey.ofShow(shows[0]), cards[0].key)
        assertEquals(FavoriteKey.of(films[0]), cards[1].key)
        assertEquals(900_000L, cards[0].positionMs)
        assertTrue(WatchNext.entries(catalog, emptySet()) { points[it] }.isEmpty())
    }

    @Test
    fun noMoreThanTheLimitAreKept() {
        val films = (1..9).map(::film)
        val catalog = Catalog(emptyList(), listOf(Category("Cinema", films)), emptyList(), null, false, 9)
        val keys = films.map { FavoriteKey.ofUrl(it.url) }.toSet()
        val cards = WatchNext.entries(catalog, keys) { url -> WatchProgress.Point(100_000L, 1_000_000L, url.hashCode().toLong()) }
        assertEquals(WatchNext.LIMIT, cards.size)
        assertEquals(cards.sortedByDescending { it.savedAt }.map { it.key }, cards.map { it.key })
    }
}
