package dev.red74.prismplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test
    fun theButtonGoesThroughTheStepsAndBackToOff() {
        var step = 0
        val seen = ArrayList<Int>()
        repeat(SleepTimer.STEPS.size) { step = SleepTimer.next(step); seen += step }
        assertEquals(listOf(15, 30, 45, 60, 90, 0), seen)
        assertEquals(0, SleepTimer.next(77))
    }

    @Test
    fun theEndAndTheRemainingMinutes() {
        assertEquals(0L, SleepTimer.endsAt(0, 1_000L))
        val end = SleepTimer.endsAt(30, 1_000L)
        assertEquals(1_000L + 1_800_000L, end)
        assertEquals(30, SleepTimer.remainingMinutes(end, 1_000L))
        assertEquals(30, SleepTimer.remainingMinutes(end, 2_000L))
        assertEquals(1, SleepTimer.remainingMinutes(end, end - 5_000L))
        assertEquals(1, SleepTimer.remainingMinutes(end, end + 5_000L))
        assertEquals(0, SleepTimer.remainingMinutes(0L, 1_000L))
    }
}
