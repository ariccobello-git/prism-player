package dev.red74.prismplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun seventeenDistinctPalettes() {
        assertEquals(17, Appearance.palettes.size)
        assertEquals(17, Appearance.palettes.map { it.accent }.toSet().size)
    }

    @Test
    fun defaultIsTheOriginalBlue() {
        assertTrue(Appearance.paletteIndex == 0)
        assertEquals(Appearance.palettes[0].bg0, PrismColors.Bg0)
    }
}
