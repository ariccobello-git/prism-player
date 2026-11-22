package dev.red74.prismplayer.data

import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoveltyTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun film(n: Int, group: String = "Cinema") = Channel("Film $n", "http://p/movie/u/p/$n.mkv", group, type = ContentType.MOVIE)

    private fun show(title: String, group: String = "Serie") =
        Show(title, null, group, listOf(Channel("$title S01 E01", "http://p/series/u/p/${title.hashCode()}.mkv", group, type = ContentType.SERIES, seriesTitle = title, season = 1, episode = 1)))

    private fun catalog(films: List<Channel>, shows: List<Show> = emptyList()): Catalog {
        val byGroup = films.groupBy { it.group.orEmpty() }.map { (g, items) -> Category(g, items) }
        val series = shows.groupBy { it.group }.map { (g, items) -> SeriesCategory(g, items) }
        return Catalog(emptyList(), byGroup, series, null, false, films.size)
    }

    @Test
    fun onlyWhatArrivesAfterTheFirstCopyIsNewAndAdultGroupsNeverShow() {
        val store = CatalogStore(Files.createTempDirectory("novelty").toFile(), { key })
        val base = (1..20).map { film(it) }
        assertTrue(store.write("L", catalog(base, listOf(show("Old")))))
        // first copy: nothing to compare with, nothing is new
        store.open("L")!!.use { assertTrue(it.recentMovies(0L, 50).isEmpty()) }

        val added = listOf(film(21), film(22), film(23, "Adulti XXX"))
        assertTrue(store.write("L", catalog(base + added, listOf(show("Old"), show("New")))))
        store.open("L")!!.use { db ->
            val since = System.currentTimeMillis() - Novelty.WINDOW_MS
            assertEquals(setOf("Film 21", "Film 22"), db.recentMovies(since, 50).map { it.name }.toSet())
            assertEquals(listOf("New"), db.recentShows(since, 50).map { it.title })
        }

        // a later renewal keeps the date of what was new, and adds nothing else
        assertTrue(store.write("L", catalog(base + added, listOf(show("Old"), show("New")))))
        store.open("L")!!.use { assertEquals(2, it.recentMovies(0L, 50).size) }
    }

    @Test
    fun aListWhoseAddressesAllChangedIsNotNews() {
        assertFalse(Novelty.isNews(oldCount = 1000, newCount = 900))
        assertTrue(Novelty.isNews(oldCount = 1, newCount = 1))
        assertFalse(Novelty.isNews(oldCount = 0, newCount = 5))
        assertTrue(Novelty.isNews(oldCount = 100, newCount = 3))
    }
}

private inline fun <T> CatalogDb.use(block: (CatalogDb) -> T): T = try { block(this) } finally { close() }
