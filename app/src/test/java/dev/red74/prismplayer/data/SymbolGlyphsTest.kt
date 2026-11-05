package dev.red74.prismplayer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolGlyphsTest {
    @After
    fun forgetFonts() {
        SymbolGlyphs.systemDraws = null
        TextCleaner.canDraw = null
    }

    @Test
    fun eachSymbolFindsTheBundledFontThatHasIt() {
        assertEquals(0, SymbolGlyphs.fontOf(0x1F17A))   // 🅺 Noto Sans Symbols
        assertEquals(1, SymbolGlyphs.fontOf(0x2654))    // ♔ Noto Sans Symbols 2
        assertEquals(2, SymbolGlyphs.fontOf(0x2A01))    // ⨁ Noto Sans Math
        assertEquals(-1, SymbolGlyphs.fontOf('K'.code))
        assertEquals(-1, SymbolGlyphs.fontOf(0xE000))   // private icon: no font
    }

    @Test
    fun onlyWhatTheTvCannotDrawGetsABundledFont() {
        // A TV that draws everything but ♔ and ⨁.
        SymbolGlyphs.systemDraws = { text -> text.codePointAt(0) != 0x2654 && text.codePointAt(0) != 0x2A01 }
        assertTrue(SymbolGlyphs.pieces("Rai 1 HD ★").isEmpty())
        val pieces = SymbolGlyphs.pieces("♔♔ Re ⨁")
        assertEquals(listOf(Triple(0, 2, 1), Triple(6, 7, 2)), pieces.map { Triple(it.start, it.end, it.font) })
    }

    @Test
    fun symbolsInTheBundledFontsAreKeptAsTheyAre() {
        SymbolGlyphs.systemDraws = { text -> text.codePointAt(0) < 0x250 }
        TextCleaner.canDraw = { text -> SymbolGlyphs.draws(text.codePointAt(0)) }
        assertEquals("♔ Scacchi 4K", TextCleaner.clean("♔ Scacchi 4\uD83C\uDD7A"))
        assertEquals("", TextCleaner.clean(""))
    }
}
