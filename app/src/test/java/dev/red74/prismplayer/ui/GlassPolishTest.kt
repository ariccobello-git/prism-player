package dev.red74.prismplayer.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassPolishTest {
    @Test
    fun ringStaysAlmostWhiteForEveryPalette() {
        for (p in Appearance.palettes) {
            val c = ringColor(p.accent)
            assertTrue("red ${p.accent}", c.red >= 0.8f)
            assertTrue("green ${p.accent}", c.green >= 0.8f)
            assertTrue("blue ${p.accent}", c.blue >= 0.8f)
        }
    }

    @Test
    fun ringTintIsVisibleButSmall() {
        val c = ringColor(Color(0xFFFF0000))
        assertTrue(c.green < 1f && c.blue < 1f)
        assertEquals(1f, c.red, 1e-4f)
    }

    @Test
    fun grainIsDeterministicAndVaried() {
        val a = grainPixels()
        assertArrayEquals(a, grainPixels())
        assertEquals(GRAIN_SIZE * GRAIN_SIZE, a.size)
        assertTrue(a.map { it and 0xFF }.toSet().size > 200)
        assertTrue(a.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun versionLabelInsideSafeMargin() {
        assertTrue(VERSION_SAFE_BOTTOM.value >= 18f)
    }
}
