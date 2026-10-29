package dev.red74.prismplayer.player

/**
 * Fast forward and rewind (2x, 4x, 8x) done the way TV players do it, instead of asking the decoder to play 4 or 8
 * times faster.
 *
 * Playing at 8x means downloading and decoding 8 times the stream in real time: a TV stick cannot, so the buffer
 * empties, the player stops on "Buffering" and the picture falls behind the speed on screen. Here the position moves
 * on a clock at exactly the chosen speed, while the paused player only shows a key frame near that position each
 * time the previous one is on screen. 2x is light enough to be played for real, and drops to the same clock as soon
 * as the stream cannot keep up.
 */
object FastScan {
    /** Gap between two pictures while scanning: lower would only queue seeks the network cannot serve. */
    const val MIN_SEEK_GAP_MS = 120L

    /** A picture that takes longer than this is given up for a newer position. */
    const val MAX_SEEK_WAIT_MS = 1_500L

    /** Real 2x playback that stalls for this long switches to the scan clock. */
    const val STALL_FALLBACK_MS = 600L

    /** Forward scan stops this far before the end of a film or the live edge, and normal playback resumes. */
    const val END_GUARD_MS = 3_000L

    /** Only 2x is played for real; faster speeds use the clock. */
    fun playsForReal(speed: Int): Boolean = speed == 2

    /** Where the scan clock is after [elapsedMs] of real time at [speed] (negative for rewind). */
    fun advance(positionMs: Long, speed: Int, elapsedMs: Long): Long = positionMs + speed * elapsedMs.coerceAtLeast(0L)

    /** Last position a forward scan may reach, or null when the length is unknown. */
    fun forwardLimit(durationMs: Long?): Long? = durationMs?.let { (it - END_GUARD_MS).coerceAtLeast(0L) }

    /** True when a new picture should be asked for now. */
    fun shouldSeek(sinceLastSeekMs: Long, pictureShown: Boolean): Boolean =
        (pictureShown && sinceLastSeekMs >= MIN_SEEK_GAP_MS) || sinceLastSeekMs >= MAX_SEEK_WAIT_MS
}
