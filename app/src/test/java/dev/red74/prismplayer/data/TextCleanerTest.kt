package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {
    @Test
    fun keepsLettersDigitsAccentsAndNormalPunctuation() {
        assertEquals("Perché Tutti Amano Raymond - 2ª Stagione (HD)", TextCleaner.clean("Perché Tutti Amano Raymond - 2ª Stagione (HD)"))
        assertEquals("Cafe\u0301 del Mar", TextCleaner.clean("Cafe\u0301 del Mar"))
        assertEquals("Mission: Impossible 2", TextCleaner.clean("Mission: Impossible 2"))
    }

    @Test
    fun dropsEmojiFlagsDingbatsAndBrokenEncodingMarks() {
        assertEquals("Rai 1 HD", TextCleaner.clean("\uD83C\uDDEE\uD83C\uDDF9 Rai 1 HD"))   // flag
        assertEquals("Sport", TextCleaner.clean("\u26BD Sport \uD83D\uDD25"))                // ball, fire
        assertEquals("Film", TextCleaner.clean("\u2605 Film \u2605"))                          // stars
        assertEquals("Citt Antica", TextCleaner.clean("Citt\uFFFD Antica"))
    }

    @Test
    fun removesQuestionMarksAndTidiesSpaces() {
        assertEquals("Caf del mar", TextCleaner.clean("Caf? del mar"))
        assertEquals("Chi sei", TextCleaner.clean("  Chi   sei ?  "))
        assertEquals("", TextCleaner.clean("???"))
        assertEquals("", TextCleaner.clean(null))
    }

    @Test
    fun playlistNamesAndGroupsAreCleanedWhenParsed() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"\uD83C\uDDEE\uD83C\uDDF9 Italia ?\",\u2605 Canale 5 ?\nhttp://h/1\n"
        val channel = M3uParser.parse(text, "http://h/list.m3u").single()
        assertEquals("Canale 5", channel.name)
        assertEquals("Italia", channel.group)
    }
}
