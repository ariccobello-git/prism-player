package dev.red74.prismplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientColorTest {
    private fun px(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun noPixelsTransparentOrBlackGiveNoColour() {
        assertNull(AmbientColor.of(IntArray(0)))
        assertNull(AmbientColor.of(IntArray(16) { px(0, 255, 0, 0) }))
        assertNull(AmbientColor.of(IntArray(16) { px(255, 0, 0, 0) }))
        assertNull(AmbientColor.of(IntArray(16) { px(255, 10, 10, 10) }))
    }

    @Test
    fun aRedPosterGivesARedTintAtTheFixedLevel() {
        val c = AmbientColor.of(IntArray(64) { px(255, 200, 20, 20) })!!
        assertEquals(190, Math.round(c.red * 255f))
        assertTrue(c.red > c.green * 5 && c.red > c.blue * 5)
    }

    @Test
    fun vividPixelsCountMoreThanGreyOnes() {
        val mixed = IntArray(64) { if (it % 2 == 0) px(255, 220, 30, 30) else px(255, 128, 128, 128) }
        val c = AmbientColor.of(mixed)!!
        assertTrue(c.red > c.green && c.red > c.blue)
    }

    @Test
    fun aDarkButNotBlackPosterIsLiftedAndTransparentPixelsAreIgnored() {
        val pixels = IntArray(32) { if (it < 16) px(255, 0, 40, 80) else px(0, 255, 255, 255) }
        val c = AmbientColor.of(pixels)
        assertNotNull(c)
        assertTrue(c!!.blue > c.green && c.green > c.red)
        assertTrue(c.blue * 255f > 150f)
    }
}
