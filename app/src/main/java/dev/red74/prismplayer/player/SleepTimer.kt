package dev.red74.prismplayer.player

/** The sleep timer of the player: off, then 15, 30, 45, 60 and 90 minutes, one press of the button at a time. */
object SleepTimer {
    val STEPS = listOf(0, 15, 30, 45, 60, 90)

    /** The step after [minutes] (any unknown value goes back to off). */
    fun next(minutes: Int): Int {
        val at = STEPS.indexOf(minutes)
        return if (at < 0) 0 else STEPS[(at + 1) % STEPS.size]
    }

    /** When the player stops, [minutes] from [nowMs]; 0 means no timer. */
    fun endsAt(minutes: Int, nowMs: Long): Long = if (minutes <= 0) 0L else nowMs + minutes * 60_000L

    /** Whole minutes still to go, rounded up, never below 1 while the timer runs. */
    fun remainingMinutes(endsAtMs: Long, nowMs: Long): Int =
        if (endsAtMs <= 0L) 0 else (((endsAtMs - nowMs) + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
}
