package dev.red74.prismplayer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {
    @After
    fun forgetFonts() {
        TextCleaner.canDraw = null
    }

    @Test
    fun keepsLettersDigitsAccentsAndNormalPunctuation() {
        assertEquals("Perché Tutti Amano Raymond - 2ª Stagione (HD)", TextCleaner.clean("Perché Tutti Amano Raymond - 2ª Stagione (HD)"))
        assertEquals("Café del Mar", TextCleaner.clean("Café del Mar"))
        assertEquals("Mission: Impossible 2", TextCleaner.clean("Mission: Impossible 2"))
        assertEquals("Rock & Roll <3 ~ !", TextCleaner.clean("Rock & Roll <3 ~ !"))
    }

    @Test
    fun keepsEmojiFlagsAndSymbols() {
        assertEquals("🇮🇹 Rai 1 HD", TextCleaner.clean("🇮🇹 Rai 1 HD"))   // flag
        assertEquals("⚽ Sport 🔥", TextCleaner.clean("⚽ Sport 🔥"))                // ball, fire
        assertEquals("★ Film ★", TextCleaner.clean("★ Film ★"))                              // stars
        // Joined emoji stay whole: family (joiners), keycap one, red heart (variation selector).
        val family = "👨‍👩‍👧"
        assertEquals("$family 1️⃣ ❤️", TextCleaner.clean("$family 1️⃣ ❤️"))
    }

    @Test
    fun dropsBrokenEncodingMarksControlsAndPrivateIcons() {
        assertEquals("Citt Antica", TextCleaner.clean("Citt� Antica"))
        assertEquals("Canale", TextCleaner.clean("\u0007Cana‎le"))
    }

    @Test
    fun symbolsTheFontsCannotDrawBecomePlainLettersOrGo() {
        // A TV font with no "mathematical" letters, superscripts, full-width letters or the new emoji.
        TextCleaner.canDraw = { text ->
            val cp = text.codePointAt(0)
            !(cp in 0x1D400..0x1D7FF || cp in 0x1D2C..0x1D6A || cp in 0xFF00..0xFFEF || cp == 0x1FAE8)
        }
        assertEquals("Rai HD", TextCleaner.clean("𝐑𝐚𝐢 ᴴᴰ"))
        assertEquals("RAI", TextCleaner.clean("ＲＡＩ"))
        assertEquals("Nuovo", TextCleaner.clean("🫨 Nuovo"))
        assertEquals("★ Film", TextCleaner.clean("★ Film"))
    }

    @Test
    fun removesQuestionMarksAndTidiesSpaces() {
        assertEquals("Caf del mar", TextCleaner.clean("Caf? del mar"))
        assertEquals("Chi sei", TextCleaner.clean("  Chi   sei ?  "))
        assertEquals("", TextCleaner.clean("???"))
        assertEquals("", TextCleaner.clean(null))
    }

    @Test
    fun playlistNamesAndGroupsKeepTheirSymbolsWhenParsed() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"🇮🇹 Italia ?\",★ Canale 5 ?\nhttp://h/1\n"
        val channel = M3uParser.parse(text, "http://h/list.m3u").single()
        assertEquals("★ Canale 5", channel.name)
        assertEquals("🇮🇹 Italia", channel.group)
    }
}
