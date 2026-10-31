package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {
    @Test
    fun onlyAPointBetweenTheFirstMinuteAndTheCreditsIsKept() {
        val film = 120 * 60_000L
        assertFalse(WatchProgress.worthKeeping(30_000, film))
        assertTrue(WatchProgress.worthKeeping(60_000, film))
        assertTrue(WatchProgress.worthKeeping(100 * 60_000L, film))
        // 5% of two hours is six minutes of credits.
        assertFalse(WatchProgress.worthKeeping(115 * 60_000L, film))
        // A short episode keeps at least 90 seconds of margin.
        assertFalse(WatchProgress.worthKeeping(20 * 60_000L, 21 * 60_000L))
        assertTrue(WatchProgress.worthKeeping(5 * 60_000L, 0))
    }

    @Test
    fun resumingGoesBackAFewSeconds() {
        assertEquals(595_000L, WatchProgress.resumeFrom(WatchProgress.Point(600_000, 0, 0)))
        assertEquals(0L, WatchProgress.resumeFrom(WatchProgress.Point(2_000, 0, 0)))
    }

    @Test
    fun pointsSurviveEncoding() {
        val point = WatchProgress.Point(2_530_000, 7_200_000, 1_761_000_000_000)
        assertEquals(point, WatchProgress.decode(WatchProgress.encode(point)))
        assertNull(WatchProgress.decode("12|x|3"))
        assertNull(WatchProgress.decode(null))
        assertEquals(0.5f, WatchProgress.Point(50, 100, 0).fraction)
    }

    @Test
    fun theOldestPointsGoFirst() {
        val points = mapOf(
            "a" to WatchProgress.Point(1, 1, 30),
            "b" to WatchProgress.Point(1, 1, 10),
            "c" to WatchProgress.Point(1, 1, 20),
        )
        assertEquals(listOf("b"), WatchProgress.overflow(points, 2))
        assertEquals(emptyList<String>(), WatchProgress.overflow(points, 3))
    }
}
