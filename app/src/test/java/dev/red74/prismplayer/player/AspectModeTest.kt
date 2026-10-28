package dev.red74.prismplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectModeTest {
    @Test
    fun offersAtLeastTenCinemaFormatsBesidesTheScreenModes() {
        val cinema = AspectMode.entries.filter { it.ratio != null }
        assertTrue("expected 10+ forced ratios, got ${cinema.size}", cinema.size >= 10)
        val ratios = cinema.map { it.ratio!! }
        assertEquals("every ratio must be different", ratios.size, ratios.toSet().size)
        assertTrue(ratios.any { Math.abs(it - 2.39f) < 0.001f })
        assertTrue(ratios.any { Math.abs(it - 2.35f) < 0.001f })
        assertTrue(ratios.any { Math.abs(it - 1.85f) < 0.001f })
        assertTrue(ratios.any { Math.abs(it - 16f / 9f) < 0.001f })
        assertTrue(ratios.any { Math.abs(it - 4f / 3f) < 0.001f })
    }

    @Test
    fun screenModesAndStretchBehaviour() {
        assertNull(AspectMode.AUTO.ratio)
        assertFalse("auto must keep the native ratio", AspectMode.AUTO.stretch)
        assertTrue(AspectMode.FIT_SCREEN.stretch)
        assertNull(AspectMode.FIT_SCREEN.ratio)
        assertTrue(AspectMode.ZOOM.zoom)
        assertFalse(AspectMode.ZOOM.stretch)
        assertEquals(16f / 9f, AspectMode.RATIO_16_9.ratio!!, 0.0001f)
        assertEquals(4f / 3f, AspectMode.RATIO_4_3.ratio!!, 0.0001f)
        assertTrue(AspectMode.entries.filter { it.ratio != null }.all { it.stretch && it.label != null })
    }

    @Test
    fun nextAndPreviousCycleThroughAllModes() {
        var mode = AspectMode.AUTO
        val seen = mutableListOf(mode)
        repeat(AspectMode.entries.size) {
            mode = mode.next()
            seen += mode
        }
        assertEquals(AspectMode.AUTO, seen.last())
        assertEquals(AspectMode.entries.toSet(), seen.toSet())

        assertEquals(AspectMode.entries.last(), AspectMode.AUTO.previous())
        assertEquals(AspectMode.AUTO, AspectMode.entries.last().next())
    }

    @Test
    fun savedNamesStillWorkAndUnknownOnesFallBackToAuto() {
        assertEquals(AspectMode.AUTO, AspectMode.fromName(null))
        assertEquals(AspectMode.AUTO, AspectMode.fromName("nonsense"))
        assertEquals(AspectMode.RATIO_4_3, AspectMode.fromName("RATIO_4_3"))
        assertEquals(AspectMode.RATIO_16_9, AspectMode.fromName("RATIO_16_9"))
        assertEquals(AspectMode.FIT_SCREEN, AspectMode.fromName("FIT_SCREEN"))
    }
}
