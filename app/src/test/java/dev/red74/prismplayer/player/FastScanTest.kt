package dev.red74.prismplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScanTest {
    @Test
    fun theClockMovesExactlyAtTheChosenSpeed() {
        assertEquals(60_000L + 8 * 1_000L, FastScan.advance(60_000L, 8, 1_000L))
        assertEquals(60_000L + 4 * 250L, FastScan.advance(60_000L, 4, 250L))
        assertEquals(60_000L - 8 * 500L, FastScan.advance(60_000L, -8, 500L))
        assertEquals(60_000L, FastScan.advance(60_000L, 8, -20L))
    }

    @Test
    fun onlyTwoTimesIsPlayedForReal() {
        assertTrue(FastScan.playsForReal(2))
        assertFalse(FastScan.playsForReal(4))
        assertFalse(FastScan.playsForReal(8))
        assertFalse(FastScan.playsForReal(-2))
    }

    @Test
    fun forwardScanStopsBeforeTheEnd() {
        assertEquals(97_000L, FastScan.forwardLimit(100_000L))
        assertEquals(0L, FastScan.forwardLimit(1_000L))
        assertNull(FastScan.forwardLimit(null))
    }

    @Test
    fun aNewPictureWaitsForThePreviousOneButNotForever() {
        assertFalse(FastScan.shouldSeek(50L, pictureShown = true))
        assertTrue(FastScan.shouldSeek(FastScan.MIN_SEEK_GAP_MS, pictureShown = true))
        assertFalse(FastScan.shouldSeek(800L, pictureShown = false))
        assertTrue(FastScan.shouldSeek(FastScan.MAX_SEEK_WAIT_MS, pictureShown = false))
    }

    @Test
    fun eachPressGoesUpTo128TimesThenBackToNormal() {
        var speed = 0
        val seen = mutableListOf<Int>()
        repeat(9) { speed = FastScan.next(speed, +1); seen += speed }
        assertEquals(listOf(2, 4, 8, 12, 24, 48, 64, 128, 0), seen)
        assertEquals(-2, FastScan.next(24, -1))
        assertEquals(-12, FastScan.next(-8, -1))
        assertEquals(-64, FastScan.next(-48, -1))
        assertEquals(0, FastScan.next(-128, -1))
        assertEquals(2, FastScan.next(-4, +1))
    }

    @Test
    fun blocksGrowWithTheSpeed() {
        assertEquals(2_000L, FastScan.blockMs(4))
        assertEquals(24_000L, FastScan.blockMs(-48))
        assertEquals(64_000L, FastScan.blockMs(128))
    }

    @Test
    fun picturesLandOnTheNextBlockBoundaryInTheScanDirection() {
        // Forward: the end of the block the clock is in.
        assertEquals(192_000L, FastScan.blockTarget(150_000L, 128))
        assertEquals(128_000L, FastScan.blockTarget(128_000L, 128))
        // Backwards: the start of the block.
        assertEquals(128_000L, FastScan.blockTarget(150_000L, -128))
        assertEquals(128_000L, FastScan.blockTarget(128_000L, -128))
        assertEquals(0L, FastScan.blockTarget(-5L, 8))
    }
}
