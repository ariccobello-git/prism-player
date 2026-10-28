package dev.red74.prismplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectModeTest {
    @Test
    fun offersAutoSixteenNineFourThreeAndFitScreen() {
        assertEquals(
            listOf(AspectMode.AUTO, AspectMode.RATIO_16_9, AspectMode.RATIO_4_3, AspectMode.FIT_SCREEN),
            AspectMode.entries,
        )
    }

    @Test
    fun ratiosAndStretchBehaviour() {
        assertNull(AspectMode.AUTO.ratio)
        assertFalse("auto must keep the native ratio", AspectMode.AUTO.stretch)
        assertEquals(16f / 9f, AspectMode.RATIO_16_9.ratio!!, 0.0001f)
        assertEquals(4f / 3f, AspectMode.RATIO_4_3.ratio!!, 0.0001f)
        assertTrue(AspectMode.RATIO_16_9.stretch && AspectMode.RATIO_4_3.stretch)
        assertNull(AspectMode.FIT_SCREEN.ratio)
        assertTrue(AspectMode.FIT_SCREEN.stretch)
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

        assertEquals(AspectMode.FIT_SCREEN, AspectMode.AUTO.previous())
        assertEquals(AspectMode.AUTO, AspectMode.FIT_SCREEN.next())
    }

    @Test
    fun unknownNamesFallBackToAuto() {
        assertEquals(AspectMode.AUTO, AspectMode.fromName(null))
        assertEquals(AspectMode.AUTO, AspectMode.fromName("nonsense"))
        assertEquals(AspectMode.RATIO_4_3, AspectMode.fromName("RATIO_4_3"))
    }
}
