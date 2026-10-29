package dev.red74.prismplayer.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real ray optics for the opening animation, in two dimensions (screen axes, y pointing down).
 *
 * An equilateral glass prism of circumradius 1 centred on the origin is turned by an angle theta. A white beam
 * arrives from the lower left; every colour has its own refractive index (dense flint glass, so the colours really
 * separate), is refracted with Snell's law where it enters and where it leaves, loses light according to the Fresnel
 * equations, and is reflected inside the glass when it meets a face too obliquely (total internal reflection).
 * Because the animation traces these rays every frame, the spectrum follows the turning prism exactly as it would
 * in front of a real one: violet disappears first near the edge of the visible window, the fan sweeps as the glass turns.
 */
internal object PrismOptics {
    /** Refractive index of red, orange, yellow, green, cyan, blue and violet. */
    val INDICES = doubleArrayOf(1.612, 1.616, 1.620, 1.626, 1.632, 1.640, 1.652)

    /** Direction of the incoming beam: it climbs from the lower left at this angle (radians, y down so negative is up). */
    val BEAM_ANGLE: Double = Math.toRadians(-28.0)

    class Vec(val x: Double, val y: Double)

    /**
     * What one colour does. [entry] is where it enters the glass, [inside] the points where it meets a face from the
     * inside (the last one is the exit when [exits]). [exitAngle] is the direction it leaves in and [transmission]
     * the share of the light that gets through the two surfaces.
     */
    class Path(
        val entry: Vec,
        val inside: List<Vec>,
        val exits: Boolean,
        val exitAngle: Double,
        val transmission: Double,
    )

    fun triangle(theta: Double): Array<Vec> {
        val base = arrayOf(Vec(0.0, -1.0), Vec(0.8660254037844386, 0.5), Vec(-0.8660254037844386, 0.5))
        val c = cos(theta)
        val s = sin(theta)
        return Array(3) { Vec(base[it].x * c - base[it].y * s, base[it].x * s + base[it].y * c) }
    }

    private fun fresnelTransmission(cosI: Double, cosT: Double, n1: Double, n2: Double): Double {
        val rs = (n1 * cosI - n2 * cosT) / (n1 * cosI + n2 * cosT)
        val rp = (n1 * cosT - n2 * cosI) / (n1 * cosT + n2 * cosI)
        return 1.0 - 0.5 * (rs * rs + rp * rp)
    }

    private class Hit(val t: Double, val point: Vec, val nx: Double, val ny: Double)

    /** First face the ray meets going in (inside = false) or out (inside = true); normals point out of the glass. */
    private fun hit(corners: Array<Vec>, ox: Double, oy: Double, dx: Double, dy: Double, inside: Boolean): Hit? {
        var best: Hit? = null
        for (i in 0 until 3) {
            val a = corners[i]
            val b = corners[(i + 1) % 3]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = hypot(ex, ey)
            var nx = ey / len
            var ny = -ex / len
            val mx = (a.x + b.x) / 2
            val my = (a.y + b.y) / 2
            if (nx * mx + ny * my < 0) { nx = -nx; ny = -ny }
            val facing = dx * nx + dy * ny
            if (inside && facing <= 0.0) continue
            if (!inside && facing >= 0.0) continue
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-9) continue
            val t = ((a.x - ox) * ey - (a.y - oy) * ex) / den
            val px = ox + dx * t
            val py = oy + dy * t
            val s = ((px - a.x) * ex + (py - a.y) * ey) / (len * len)
            if (t > 1e-6 && s >= -1e-6 && s <= 1 + 1e-6 && (best == null || t < best.t)) best = Hit(t, Vec(px, py), nx, ny)
        }
        return best
    }

    /** Snell's law in vector form; [nx], [ny] point against the ray. Null when there is no refracted ray. */
    private fun refract(dx: Double, dy: Double, nx: Double, ny: Double, eta: Double): DoubleArray? {
        val cosI = -(dx * nx + dy * ny)
        val k = 1.0 - eta * eta * (1.0 - cosI * cosI)
        if (k < 0.0) return null
        val t = eta * cosI - sqrt(k)
        return doubleArrayOf(eta * dx + t * nx, eta * dy + t * ny)
    }

    /**
     * Follows a beam of index [n] through the prism turned by [theta]. The beam passes through the point
     * (0, [offsetY]) of the prism's frame. Null only if it misses the glass.
     */
    fun trace(theta: Double, n: Double, offsetY: Double = 0.0, beamAngle: Double = BEAM_ANGLE): Path? {
        val corners = triangle(theta)
        var dx = cos(beamAngle)
        var dy = sin(beamAngle)
        val ox = -dx * 3.0
        val oy = offsetY - dy * 3.0
        val first = hit(corners, ox, oy, dx, dy, inside = false) ?: return null
        val cosIn = -(dx * first.nx + dy * first.ny)
        val bent = refract(dx, dy, first.nx, first.ny, 1.0 / n) ?: return null
        val cosRefracted = -(bent[0] * first.nx + bent[1] * first.ny)
        var transmission = fresnelTransmission(cosIn, cosRefracted, 1.0, n)
        dx = bent[0]; dy = bent[1]
        var px = first.point.x
        var py = first.point.y
        val inside = ArrayList<Vec>(4)
        repeat(4) {
            val h = hit(corners, px, py, dx, dy, inside = true) ?: return Path(first.point, inside, false, 0.0, 0.0)
            inside += h.point
            val cosInside = dx * h.nx + dy * h.ny
            val out = refract(dx, dy, -h.nx, -h.ny, n)
            if (out == null) {
                // total internal reflection: the light stays in the glass and tries the next face
                dx -= 2 * cosInside * h.nx
                dy -= 2 * cosInside * h.ny
                px = h.point.x
                py = h.point.y
            } else {
                val cosOut = out[0] * h.nx + out[1] * h.ny
                transmission *= fresnelTransmission(cosInside, cosOut, n, 1.0)
                return Path(first.point, inside, true, atan2(out[1], out[0]), transmission)
            }
        }
        return Path(first.point, inside, false, 0.0, 0.0)
    }

    /** The angle (radians) at which the middle colour passes with the smallest deviation: the classic textbook view. */
    val CLASSIC_THETA: Double by lazy {
        var best = 0.0
        var bestDeviation = Double.MAX_VALUE
        var degrees = -60.0
        while (degrees <= 60.0) {
            val p = trace(Math.toRadians(degrees), INDICES[3])
            if (p != null && p.exits) {
                val deviation = abs(p.exitAngle - BEAM_ANGLE)
                if (deviation < bestDeviation) { bestDeviation = deviation; best = degrees }
            }
            degrees += 0.1
        }
        Math.toRadians(best)
    }

    /** How far (radians, -pi/3..pi/3) the prism is from the classic angle; the prism looks the same every 120 degrees. */
    fun relativeToClassic(theta: Double): Double {
        val third = 2 * PI / 3
        var r = (theta - CLASSIC_THETA) % third
        if (r >= third / 2) r -= third
        if (r < -third / 2) r += third
        return r
    }
}
