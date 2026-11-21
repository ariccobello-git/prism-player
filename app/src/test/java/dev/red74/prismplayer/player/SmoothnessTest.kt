package dev.red74.prismplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothnessTest {
    private fun mode(id: Int, rate: Float, w: Int = 1920, h: Int = 1080) = FrameRateMatch.Mode(id, w, h, rate)

    private val modes = listOf(
        mode(1, 60f), mode(2, 59.94f), mode(3, 50f), mode(4, 30f), mode(5, 29.97f), mode(6, 25f),
        mode(7, 24f), mode(8, 23.976f), mode(9, 3840.0f / 1000, 3840, 2160), mode(10, 60f, 3840, 2160),
    )
    private val current = mode(1, 60f)

    @Test
    fun filmsGoToTheirOwnRateAndPalUsesFifty() {
        assertEquals(7, FrameRateMatch.pick(modes, current, 24f))
        assertEquals(8, FrameRateMatch.pick(modes, current, 23.976f))
        assertEquals("25 fps on 50 Hz, not 25 Hz", 3, FrameRateMatch.pick(modes, current, 25f))
        assertEquals(2, FrameRateMatch.pick(modes, current, 29.97f))
    }

    @Test
    fun fiftyAndSixtyStayOrMoveToTheMatchingOne() {
        assertEquals(3, FrameRateMatch.pick(modes, current, 50f))
        assertNull("60 fps on 60 Hz needs no change", FrameRateMatch.pick(modes, current, 60f))
        assertEquals("59.94 fps wants the exact 59.94 Hz", 2, FrameRateMatch.pick(modes, current, 59.94f))
    }

    @Test
    fun aRateThatIsAlreadyAMultipleIsKept() {
        // 30 fps on 60 Hz is already even (every frame twice): no switch needed.
        assertNull(FrameRateMatch.pick(modes, current, 30f))
        assertNull(FrameRateMatch.pick(modes, mode(7, 24f), 24f))
    }

    @Test
    fun sizeNeverChangesAndNoMatchMeansNoChange() {
        assertNull(FrameRateMatch.pick(listOf(mode(1, 60f), mode(10, 24f, 3840, 2160)), current, 24f))
        assertNull(FrameRateMatch.pick(emptyList(), current, 24f))
    }

    @Test
    fun nonsenseRatesAreIgnored() {
        assertNull(FrameRateMatch.pick(modes, current, 0f))
        assertNull(FrameRateMatch.pick(modes, current, -1f))
        assertNull(FrameRateMatch.pick(modes, current, 500f))
    }

    @Test
    fun stallsOnlyCountWhenFrequentAndRecent() {
        val log = StallLog(windowMs = 10 * 60_000L, threshold = 3)
        assertFalse(log.struggling(0))
        log.record(1_000); log.record(2_000)
        assertFalse(log.struggling(3_000))
        log.record(4_000)
        assertTrue(log.struggling(5_000))
        assertFalse("old stalls are forgotten", log.struggling(4_000 + 11 * 60_000L))
    }

    @Test
    fun steadyPlanKeepsTheSameMemoryButWaitsLongerAfterAStall() {
        assertEquals(BufferPlan.FULL.maxBytes, BufferPlan.STEADY.maxBytes)
        assertTrue(BufferPlan.STEADY.afterStallMs > BufferPlan.FULL.afterStallMs)
        assertTrue(BufferPlan.STEADY.maxMs <= 60_000)
    }
}
