package dev.red74.prismplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSearchTest {
    private val now = 1_000_000_000L
    private fun live(name: String, id: String? = null) = Channel(name, "http://h/u/p/${name.hashCode().toString().filter { it.isDigit() }}.ts", "G", tvgId = id)
    private fun movie(name: String) = Channel(name, "http://h/movie/u/p/${name.length}.mp4", "M", type = ContentType.MOVIE)
    private fun ep(name: String, season: Int, episode: Int) = Channel("$name S0${season}E0$episode", "http://h/series/u/p/$season$episode.mkv", "S", type = ContentType.SERIES, seriesTitle = name, season = season, episode = episode)

    private fun epg(vararg pairs: Pair<String, List<Programme>>): EpgData = EpgData(pairs.toMap().mapKeys { it.key.lowercase() }, emptyMap())

    private val sky1 = live("Sky Sport 1", "sky1")
    private val sky2 = live("Sky Sport 2 SD", "sky2")
    private val rai = live("Rai 1", "rai1")

    private val derby = Programme("Roma - Lazio", now + 3_600_000L, now + 3_600_000L + 6_000_000L, null)
    private val onAir = Programme("Telegiornale sera", now - 600_000L, now + 1_200_000L, null)
    private val over = Programme("Vecchio film", now - 7_200_000L, now - 3_600_000L, null)

    private fun catalog() = CatalogBuilder.build(
        listOf(sky1, sky2, rai, movie("Il Gladiatore"), movie("Gladiator II"), ep("Gomorra", 1, 1), ep("Gomorra", 1, 2)),
        null, false,
    )

    @Test fun wordsAndMatching() {
        assertEquals(listOf("roma", "lazio"), UnifiedSearch.words("Roma - Lazio!"))
        assertTrue(UnifiedSearch.matches(listOf("roma", "lazio"), listOf("rom", "laz")))
        assertTrue(!UnifiedSearch.matches(listOf("roma", "lazio"), listOf("inter")))
        assertTrue(UnifiedSearch.matches(listOf("roma", "lazio"), listOf("lazio", "roma")))
        assertTrue(UnifiedSearch.words("  ").isEmpty())
    }

    @Test fun indexFindsFutureAndLiveButNotOver() = runBlocking {
        val data = epg("sky1" to listOf(over, derby), "rai1" to listOf(onAir))
        val index = EpgIndex.build(data, listOf(sky1, rai), now)
        assertEquals(2, index.size)
        assertEquals(listOf("Roma - Lazio"), index.search("roma", now, 10).map { it.programme.title })
        assertEquals(listOf("Telegiornale sera"), index.search("tele", now, 10).map { it.programme.title })
        assertTrue(index.search("vecchio", now, 10).isEmpty())
        assertTrue(index.search("", now, 10).isEmpty())
    }

    @Test fun onAirComesBeforeLaterOnes() = runBlocking {
        val later = Programme("Telegiornale notte", now + 5_000_000L, now + 8_000_000L, null)
        val data = epg("rai1" to listOf(later, onAir))
        val index = EpgIndex.build(data, listOf(rai), now)
        assertEquals(listOf("Telegiornale sera", "Telegiornale notte"), index.search("telegiornale", now, 10).map { it.programme.title })
    }

    @Test fun sameProgrammeOnManyChannelsIsOneHitWithTheBestChannelFirst() = runBlocking {
        val a = Programme("Roma - Lazio", derby.startMs, derby.endMs, null)
        val data = epg("sky1" to listOf(derby), "sky2" to listOf(a))
        val index = EpgIndex.build(data, listOf(sky2, sky1), now)
        val hits = index.search("roma lazio", now, 10)
        assertEquals(1, hits.size)
        assertEquals(listOf("Sky Sport 1", "Sky Sport 2 SD"), hits[0].channels.map { it.name })
    }

    @Test fun emptyGuideGivesEmptyIndex() = runBlocking {
        assertEquals(0, EpgIndex.build(EpgData.Empty, listOf(sky1), now).size)
        assertEquals(0, EpgIndex.build(epg("sky1" to listOf(derby)), emptyList(), now).size)
    }

    @Test fun runSearchesEveryKind() = runBlocking {
        val index = EpgIndex.build(epg("sky1" to listOf(derby)), listOf(sky1), now)
        val result = UnifiedSearch.run(catalog(), index, "sky", now)
        assertEquals(listOf("Sky Sport 1", "Sky Sport 2 SD"), result.channels.map { it.name })
        assertTrue(result.movies.isEmpty() && result.shows.isEmpty() && result.programmes.isEmpty())

        val films = UnifiedSearch.run(catalog(), index, "gladi", now)
        assertEquals(2, films.movies.size)

        val series = UnifiedSearch.run(catalog(), index, "gomo", now)
        assertEquals(listOf("Gomorra"), series.shows.map { it.title })

        val prog = UnifiedSearch.run(catalog(), index, "lazio", now)
        assertEquals(1, prog.programmes.size)
        assertTrue(!prog.isEmpty)
    }

    @Test fun tooShortOrNothingFound() = runBlocking {
        val index = EpgIndex.Empty
        assertTrue(UnifiedSearch.run(catalog(), index, "", now).isEmpty)
        assertTrue(UnifiedSearch.run(catalog(), index, " a ", now).isEmpty)
        assertTrue(UnifiedSearch.run(catalog(), index, "zzzzzz", now).isEmpty)
    }
}
