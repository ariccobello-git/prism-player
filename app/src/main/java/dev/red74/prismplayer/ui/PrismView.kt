package dev.red74.prismplayer.ui

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 3D view of the opening scene. The prism is a real solid: its triangular cross-section ([PrismOptics], where
 * the light is traced) is extruded along its length, and the whole scene is seen from slightly above and from the
 * side, with perspective. While it turns around its own length the side faces really sweep past, and the viewpoint
 * itself swings a little and settles with the music.
 *
 * Units are the prism's circumradius; view space has x to the right, y down and z going away from the viewer, who
 * stands [CAMERA] units in front of the prism.
 */
internal object PrismView {
    /** Half the prism's length, along its axis. */
    const val HALF_LENGTH = 1.25

    /** Distance from the viewer to the centre of the prism. */
    const val CAMERA = 7.0

    /** Seen from slightly above. */
    const val PITCH = 0.28

    /** Side angle the view settles on when the prism rests: enough to show the length of the glass. */
    const val REST_YAW = 0.62

    /** Nothing comes closer to the viewer than this (view-space z), so rays flying out of the screen stay drawable. */
    const val NEAREST = -0.86 * CAMERA

    class V(val x: Double, val y: Double, val z: Double) {
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun unit(): V {
            val d = sqrt(dot(this)).coerceAtLeast(1e-9)
            return V(x / d, y / d, z / d)
        }
    }

    /** The light of the galaxy core: up and to the left, in front of the prism (view space, pointing to the light). */
    val LIGHT = V(-0.5, -0.75, -0.45).unit()

    /** Where the viewer is, in view space. */
    val EYE = V(0.0, 0.0, -CAMERA)

    private fun smooth(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Side angle of the view at time [tm] of the opening (music time): it swings while the prism turns, then rests. */
    fun yaw(tm: Double): Double {
        val settle = 1.0 - smooth(0.0, PrismMotion.STOP_AT, tm)
        return REST_YAW + 0.45 * settle * cos(tm * 0.9)
    }

    /** A point (or a direction) of the prism's frame turned into view space; no perspective. */
    fun toView(x: Double, y: Double, z: Double, yaw: Double): V {
        val x1 = x * cos(yaw) + z * sin(yaw)
        val z1 = -x * sin(yaw) + z * cos(yaw)
        val y2 = y * cos(PITCH) - z1 * sin(PITCH)
        val z2 = y * sin(PITCH) + z1 * cos(PITCH)
        return V(x1, y2, z2)
    }

    /** How much bigger than at the prism's distance something at view depth [z] looks. */
    fun scale(z: Double): Double = CAMERA / (CAMERA + max(z, NEAREST))
}
