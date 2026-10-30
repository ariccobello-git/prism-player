package dev.red74.prismplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdleExitTest {
    @Test
    fun noCountdownBeforeFiveMinutes() {
        assertNull(IdleExit.secondsLeft(now = 1_000L, lastActivity = 1_000L))
        assertNull(IdleExit.secondsLeft(now = IdleExit.IDLE_MS - 1, lastActivity = 0L))
    }

    @Test
    fun countdownRunsFromFifteenToZero() {
        assertEquals(15, IdleExit.secondsLeft(now = IdleExit.IDLE_MS, lastActivity = 0L))
        assertEquals(14, IdleExit.secondsLeft(now = IdleExit.IDLE_MS + 1_000L, lastActivity = 0L))
        assertEquals(1, IdleExit.secondsLeft(now = IdleExit.IDLE_MS + 14_999L, lastActivity = 0L))
        assertEquals(0, IdleExit.secondsLeft(now = IdleExit.IDLE_MS + 15_000L, lastActivity = 0L))
        assertEquals(0, IdleExit.secondsLeft(now = IdleExit.IDLE_MS + 99_000L, lastActivity = 0L))
    }

    @Test
    fun activityRestartsTheWait() {
        val last = IdleExit.IDLE_MS + 10_000L
        assertNull(IdleExit.secondsLeft(now = last + 1_000L, lastActivity = last))
    }
}
