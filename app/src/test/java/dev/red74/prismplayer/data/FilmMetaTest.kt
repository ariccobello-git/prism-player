package dev.red74.prismplayer.data

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmMetaTest {
    @Test
    fun ratingLengthYearAndGenreAreFormatted() {
        assertEquals(listOf("★ 7.8", "2h 04m", "2024", "Azione"), FilmMeta.of("7.8", "2024-03-01", "7440", null, "Azione, Thriller"))
    }

    @Test
    fun lengthFromAClockAndShortFilms() {
        assertEquals(listOf("1h 55m"), FilmMeta.of(null, null, null, "01:55:12", null))
        assertEquals(listOf("45m"), FilmMeta.of(null, null, "2700", null, null))
    }

    @Test
    fun nonsenseIsLeftOut() {
        assertEquals(emptyList<String>(), FilmMeta.of("0", "0000", "0", "abc", ""))
        assertEquals(emptyList<String>(), FilmMeta.of("11", "1800", "30", "1:2:3:4", "   "))
        assertEquals(emptyList<String>(), FilmMeta.of(null, null, null, null, null))
        assertEquals(listOf("★ 6.5"), FilmMeta.of("6,5", null, null, null, null))
    }

    @Test
    fun genrePipeCannotBreakTheSavedList() {
        assertTrue(FilmMeta.of(null, null, null, null, "A|B").none { '|' in it })
    }

    @Test
    fun panelAnswerGivesPlotAndMeta() {
        val json = """{"info":{"plot":"Una trama.","rating":"7.1","releasedate":"2023-05-05","duration_secs":5400,"genre":"Dramma"},"movie_data":{"name":"X"}}"""
        val film = PanelJson.film(StringReader(json))
        assertEquals("Una trama.", film.plot)
        assertEquals(listOf("★ 7.1", "1h 30m", "2023", "Dramma"), film.meta)
    }
}
