package dev.red74.prismplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The player always names what is playing: film, episode (show, season, episode) and live (channel, programme on air). */
class PlayerTitleTest {
    private fun sub(
        series: Boolean = false, season: Int = 0, episode: Int = 0, catchUp: String? = null, onAir: String? = null, group: String? = null,
    ) = playerSubtitle(series, season, episode, "Stagione $season", "Episodio $episode", catchUp, onAir, group, "Rai Movie")

    @Test
    fun aFilmShowsItsName() {
        assertEquals("Il Padrino", playerTitle(false, null, "Il Padrino"))
        assertNull(sub())
    }

    @Test
    fun anEpisodeShowsTheShowThenSeasonAndEpisode() {
        assertEquals("Dark", playerTitle(true, "Dark", "Dark S02E05"))
        assertEquals("Stagione 2 · Episodio 5", sub(series = true, season = 2, episode = 5))
        assertEquals("Episodio 5", sub(series = true, episode = 5))
    }

    @Test
    fun aSeriesWithoutShowNameFallsBackToTheName() {
        assertEquals("Dark S02E05", playerTitle(true, null, "Dark S02E05"))
        assertEquals("Dark S02E05", playerTitle(true, "  ", "Dark S02E05"))
    }

    @Test
    fun liveTitleIsTheProgrammeOnAirWithTheChannelUnderIt() {
        assertEquals("Il Padrino", playerTitle(false, null, "Rai Movie", null, "Il Padrino"))
        assertEquals("Rai Movie", sub(onAir = "Il Padrino", group = "Film"))
    }

    @Test
    fun liveWithoutGuideShowsTheChannelAndItsGroup() {
        assertEquals("Rai Movie", playerTitle(false, null, "Rai Movie", null, null))
        assertEquals("Rai Movie", playerTitle(false, null, "Rai Movie", null, " "))
        assertEquals("Film", sub(group = "Film"))
        assertNull(sub(group = " "))
    }

    @Test
    fun catchUpShowsTheProgrammeThenTheChannel() {
        assertEquals("Film sera", playerTitle(false, null, "Rai Movie", "Film sera", "TG1"))
        assertEquals("Rai Movie", sub(catchUp = "Film sera", onAir = "TG1"))
    }
}
