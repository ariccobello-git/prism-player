package dev.red74.prismplayer.player

/**
 * Fast forward and rewind (2x up to 128x) done the way TV players do it, instead of asking the decoder to play 4 or 8
 * times faster.
 *
 * Playing at 8x means downloading and decoding 8 times the stream in real time: a TV stick cannot, so the buffer
 * empties, the player stops on "Buffering" and the picture falls behind the speed on screen. Here the position moves
 * on a clock at exactly the chosen speed, while the paused player only shows a key frame near that position each
 * time the previous one is on screen. The pictures are taken at block boundaries, and a block grows with the speed
 * ([blockMs]): the picture moves by regular jumps while the time on screen keeps exactly the chosen speed, even when
 * the stream is slow to answer (it then skips whole blocks instead of slowing down). 2x is light enough to be played for real, and drops to the same clock as soon
 * as the stream cannot keep up.
 */
object FastScan {
    /** Gap between two pictures while scanning: lower would only queue seeks the network cannot serve. */
    const val MIN_SEEK_GAP_MS = 120L

    /**
     * A picture that takes longer than this is given up for a newer position. At 12x and more every position is
     * beyond what is already downloaded, and an IPTV server can take a few seconds to answer each jump: giving up
     * sooner cancelled every jump before it arrived, so the picture never changed.
     */
    const val MAX_SEEK_WAIT_MS = 6_000L

    /** Real 2x playback that stalls for this long switches to the scan clock. */
    const val STALL_FALLBACK_MS = 600L

    /** Forward scan stops this far before the end of a film or the live edge, and normal playback resumes. */
    const val END_GUARD_MS = 3_000L

    /** The speeds each press of fast forward (or rewind) goes through, before going back to normal. */
    val SPEEDS = listOf(2, 4, 8, 12, 24, 48, 64, 128)

    /** Real time one block lasts on screen: each block is this much film multiplied by the speed. */
    const val BLOCK_REAL_MS = 500L

    /** Length of film one jump covers at [speed]: 2 s at 4x, 4 s at 8x, 24 s at 48x, 64 s at 128x. */
    fun blockMs(speed: Int): Long = kotlin.math.abs(speed) * BLOCK_REAL_MS

    /**
     * The block boundary the picture shows for clock position [positionMs]: the next boundary in the direction of
     * the scan, so the picture never jumps the wrong way when a scan starts in the middle of a block.
     */
    fun blockTarget(positionMs: Long, speed: Int): Long {
        val pos = positionMs.coerceAtLeast(0L)
        val block = blockMs(speed).coerceAtLeast(1L)
        val start = Math.floorDiv(pos, block) * block
        return if (speed > 0 && start != pos) start + block else start
    }

    /**
     * The speed after one press in [direction] (+1 forward, -1 rewind): the next step in the same direction, the
     * first step when changing direction, 0 (normal playback) after the last.
     */
    fun next(current: Int, direction: Int): Int {
        val sign = if (direction > 0) 1 else -1
        if (current == 0 || current * sign < 0) return sign * SPEEDS.first()
        val i = SPEEDS.indexOf(kotlin.math.abs(current))
        return if (i < 0 || i == SPEEDS.lastIndex) 0 else sign * SPEEDS[i + 1]
    }

    /** Only 2x is played for real (without sound); faster speeds use the clock. */
    fun playsForReal(speed: Int): Boolean = speed == 2

    /** Where the scan clock is after [elapsedMs] of real time at [speed] (negative for rewind). */
    fun advance(positionMs: Long, speed: Int, elapsedMs: Long): Long = positionMs + speed * elapsedMs.coerceAtLeast(0L)

    /** Last position a forward scan may reach, or null when the length is unknown. */
    fun forwardLimit(durationMs: Long?): Long? = durationMs?.let { (it - END_GUARD_MS).coerceAtLeast(0L) }

    /** True when a new picture should be asked for now. */
    fun shouldSeek(sinceLastSeekMs: Long, pictureShown: Boolean): Boolean =
        (pictureShown && sinceLastSeekMs >= MIN_SEEK_GAP_MS) || sinceLastSeekMs >= MAX_SEEK_WAIT_MS
}
