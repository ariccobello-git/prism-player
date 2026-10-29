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
}
