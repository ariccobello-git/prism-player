package dev.red74.prismplayer.ui

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * How the prism turns during the opening music (10.6 s): it starts to turn slowly with the first notes, speeds up
 * following the pulses of the music (the same pulse times the sound uses), reaches its fastest turn around 4 s,
 * slows down through the main hit at 5.3 s and stops at 8.1 s exactly at the classic view, leaving 2.5 s of
 * stillness until the music ends. The angle comes from integrating the speed, so the motion is always smooth.
 */
internal object PrismMotion {
    const val IMPACT = 5.3
    const val STOP_AT = 8.1
    const val MUSIC_END = 10.6
    private const val PEAK_DEG_PER_SECOND = 62.0
    private const val PEAK_AT = 3.9
    private const val STEP = 1.0 / 240.0

    /** Pulse times of the intro: the accelerating ticks before the hit, then the bright glints that follow it. */
    private val pulses: DoubleArray = run {
        val list = ArrayList<Double>()
        var t = 1.4
        while (t < IMPACT - 0.2) {
            list += t
            t += 0.55 * exp(-(t - 1.4) / 2.2) + 0.12
        }
        DoubleArray(list.size) { list[it] }
    }
    private val glints = DoubleArray(6) { IMPACT + 0.5 + 0.55 * it }

    private fun smootherStep(x: Double): Double = x * x * x * (x * (6 * x - 15) + 10)

    /** Turning speed in degrees per second. */
    fun speed(t: Double): Double {
        if (t >= STOP_AT || t < 0) return 0.0
        val envelope = if (t < PEAK_AT) {
            4.0 + (PEAK_DEG_PER_SECOND - 4.0) * smootherStep(t / PEAK_AT).pow(1.15)
        } else {
            PEAK_DEG_PER_SECOND * (1.0 - (t - PEAK_AT) / (STOP_AT - PEAK_AT)).pow(3.0)
        }
        var kick = 0.0
        for (p in pulses) if (t >= p) kick += 0.25 * exp(-(t - p) / 0.10)
        for (g in glints) if (t >= g) kick += 0.10 * exp(-(t - g) / 0.12)
        val wander = 1.0 + 0.08 * sin(1.9 * t + 0.4) + 0.05 * sin(4.3 * t + 1.7)
        return envelope * (1.0 + kick) * wander
    }

    /** Degrees turned from the start up to each step; the last entry is the whole turn. */
    private val turned: DoubleArray = run {
        val steps = (STOP_AT / STEP).toInt() + 1
        val table = DoubleArray(steps + 1)
        for (i in 1..steps) table[i] = table[i - 1] + speed((i - 0.5) * STEP) * STEP
        table
    }

    private fun turnedAt(t: Double): Double {
        val x = (t / STEP).coerceIn(0.0, (turned.size - 1).toDouble())
        val i = x.toInt().coerceAtMost(turned.size - 2)
        val f = x - i
        return turned[i] * (1 - f) + turned[i + 1] * f
    }

    /** The prism's angle in radians at time [t]; from [STOP_AT] on it rests at the classic view. */
    fun angle(t: Double): Double {
        val remaining = turned[turned.size - 1] - turnedAt(t.coerceAtMost(STOP_AT))
        return PrismOptics.CLASSIC_THETA - Math.toRadians(remaining)
    }

    /** Degrees still to turn at time [t] before the prism rests. */
    fun remainingDegrees(t: Double): Double = turned[turned.size - 1] - turnedAt(t.coerceAtMost(STOP_AT))
}
