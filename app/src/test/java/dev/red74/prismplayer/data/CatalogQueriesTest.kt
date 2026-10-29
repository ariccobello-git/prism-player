package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The questions the library screens ask a catalog, answered by the in-memory one (the database answers the same). */
class CatalogQueriesTest {
    private fun ch(name: String, group: String, type: ContentType = ContentType.LIVE, title: String? = null, s: Int = 0, e: Int = 0) =
        Channel(name, "http://x.test/$type/$group/$name/$s/$e", group, type = type, seriesTitle = title, season = s, episode = e)

    private val catalog = CatalogBuilder.build(
        listOf(
            ch("Rai 1", "ITALIA"), ch("Canale 5", "ITALIA"), ch("Sky Cinema Uno", "SKY"), ch("Sky Cinema Due", "SKY"),
            ch("Heat", "FILM", ContentType.MOVIE), ch("Alien", "FILM", ContentType.MOVIE),
            ch("Ep", "SERIE", ContentType.SERIES, "Dark", 1, 2), ch("Ep", "SERIE", ContentType.SERIES, "Dark", 1, 1),
            ch("Ep", "SERIE", ContentType.SERIES, "Lost", 1, 1),
        ),
        null, false,
    )

    @Test
    fun channelsOfSeveralCategoriesComeOneCategoryAfterTheOther() {
        assertEquals(listOf("Rai 1", "Canale 5", "Sky Cinema Uno", "Sky Cinema Due"), catalog.channels(ContentType.LIVE, listOf(0, 1)).map { it.name })
        assertEquals(listOf("Sky Cinema Uno", "Sky Cinema Due"), catalog.channels(ContentType.LIVE, listOf(1)).map { it.name })
        assertEquals(listOf("Dark", "Lost"), catalog.shows(listOf(0)).map { it.title })
    }

    @Test
    fun sortsAsAsked() {
        val all = catalog.channels(ContentType.LIVE, listOf(0, 1))
        assertEquals("Canale 5", catalog.sorted(all, SortMode.AZ).first().name)
        assertEquals("Sky Cinema Uno", catalog.sorted(all, SortMode.ZA).first().name)
        assertEquals("Sky Cinema Due", catalog.sorted(all, SortMode.RECENT).first().name)
        assertEquals(listOf("Lost", "Dark"), catalog.sortedShows(catalog.shows(listOf(0)), SortMode.ZA).map { it.title })
    }

    @Test
    fun searchesFindsAndFavourites() {
        assertEquals(listOf("Sky Cinema Uno", "Sky Cinema Due"), catalog.search(ContentType.LIVE, "cinema", 10).map { it.name })
        assertEquals(listOf("Heat"), catalog.search(ContentType.MOVIE, "EA", 10).map { it.name })
        assertEquals(listOf("Lost"), catalog.searchShows("los", 10).map { it.title })

        val heat = catalog.movies[0].items[0]
        assertEquals(heat, catalog.find(ContentType.MOVIE, heat.url))
        assertNull(catalog.find(ContentType.LIVE, heat.url))
        assertEquals(listOf(1, 2), catalog.findShow("SERIE", "Dark")!!.episodes.map { it.episode })
        assertNull(catalog.findShow("ALTRO", "Dark"))

        val rai = catalog.live[0].items[0]
        val dark = catalog.findShow(null, "Dark")!!
        val keys = setOf(FavoriteKey.of(heat), FavoriteKey.of(rai), FavoriteKey.ofShow(dark))
        assertEquals(listOf("Rai 1", "Heat"), catalog.favorites(keys).map { it.name })
        assertEquals(listOf("Dark"), catalog.favoriteShows(keys).map { it.title })
        assertEquals(FavoriteKey.of(heat), FavoriteKey.ofUrl(heat.url))
        assertEquals(4, catalog.allLive().size)
    }
}
