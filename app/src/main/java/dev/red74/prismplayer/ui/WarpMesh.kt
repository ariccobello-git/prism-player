package dev.red74.prismplayer.ui

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The picture falling into the black hole as ONE continuous sheet, like a film of space-time being dragged in: a grid of
 * shared vertices whose positions are warped while the picture stays glued to them, so nothing breaks into pieces.
 *
 * What the eye sees (cinematic "spaghettification", seen in films about black holes and tidal disruptions):
 * - the pull starts near the hole and spreads outwards, each direction a little out of step, so the sheet is stretched;
 * - the matter nearer the hole turns faster than the matter farther out (differential rotation), so a round moon is
 *   smeared into a thinning arc that winds around the hole;
 * - only then does everything plunge in, fading and turning red (red shift) until it vanishes at the horizon.
 *
 * Only plain arrays and maths, so it is tested on the JVM. Counts follow the `Canvas.drawVertices` convention: arrays
 * are filled in FLOATS (2 per point), see [vertexCount]; passing point counts drew a stray triangle once (moon mesh).
 *
 * @param keep tells which cells (row * cols + col) of the grid are drawn; a layer that is empty almost everywhere only
 *   keeps the cells with something in them, and only their corners are computed.
 */
internal class WarpMesh(
    val cols: Int,
    val rows: Int,
    val width: Int,
    val height: Int,
    private val holeX: Float,
    private val holeY: Float,
    keep: (Int) -> Boolean = { true },
) {
    /** Number of vertices; [texs], [WarpMesh.fill]'s `verts` hold 2 floats for each, `colors` 1 int. */
    val vertexCount: Int

    /** Where each vertex sits in the picture, in pixels: the texture coordinates, fixed. */
    val texs: FloatArray

    /** Two triangles for every kept cell. */
    val indices: ShortArray

    private val radius: FloatArray
    private val angle: FloatArray
    private val shear: FloatArray
    private val delay: FloatArray

    init {
        val corner = IntArray((cols + 1) * (rows + 1)) { -1 }
        var cells = 0
        var points = 0
        for (row in 0 until rows) for (col in 0 until cols) {
            if (!keep(row * cols + col)) continue
            cells++
            for (c in intArrayOf(row * (cols + 1) + col, row * (cols + 1) + col + 1, (row + 1) * (cols + 1) + col, (row + 1) * (cols + 1) + col + 1)) {
                if (corner[c] < 0) corner[c] = points++
            }
        }
        vertexCount = points
        require(points <= Short.MAX_VALUE) { "too many vertices for 16-bit indices: $points" }
        texs = FloatArray(points * 2)
        radius = FloatArray(points)
        angle = FloatArray(points)
        shear = FloatArray(points)
        delay = FloatArray(points)
        val farthest = hypot(maxOf(holeX, width - holeX), maxOf(holeY, height - holeY))
        for (gy in 0..rows) for (gx in 0..cols) {
            val id = corner[gy * (cols + 1) + gx]
            if (id < 0) continue
            val x = if (gx == cols) width.toFloat() else gx * width.toFloat() / cols
            val y = if (gy == rows) height.toFloat() else gy * height.toFloat() / rows
            texs[id * 2] = x
            texs[id * 2 + 1] = y
            val dx = x - holeX
            val dy = y - holeY
            val r = hypot(dx, dy)
            val a = atan2(dy, dx)
            val q = (r / farthest).coerceIn(0f, 1f)
            radius[id] = r
            angle[id] = a
            shear[id] = (1f / (q + SHEAR_OFFSET)).pow(SHEAR_POWER)
            // the pull reaches the far parts later, and each direction a little out of step (smooth in the angle)
            val wobble = 0.5f + 0.3f * sin(2f * a + PHASE_A) + 0.2f * sin(5f * a + PHASE_B)
            delay[id] = BlackHoleTimeline.WARP_SPREAD * q.pow(0.85f) + BlackHoleTimeline.WARP_NOISE * wobble * sqrt(q)
        }
        indices = ShortArray(cells * 6)
        var o = 0
        for (row in 0 until rows) for (col in 0 until cols) {
            if (!keep(row * cols + col)) continue
            val a = corner[row * (cols + 1) + col].toShort()
            val b = corner[row * (cols + 1) + col + 1].toShort()
            val c = corner[(row + 1) * (cols + 1) + col + 1].toShort()
            val d = corner[(row + 1) * (cols + 1) + col].toShort()
            indices[o++] = a; indices[o++] = b; indices[o++] = c
            indices[o++] = a; indices[o++] = c; indices[o++] = d
        }
    }

    /** Progress of vertex [i] at [t], from 0 (untouched) to 1 (gone into the hole). */
    fun progress(i: Int, t: Float): Float = ((t - BlackHoleTimeline.WARP_START - delay[i]) / BlackHoleTimeline.WARP_DURATION).coerceIn(0f, 1f)

    /** Writes where every vertex is at time [t] into [verts] (2 floats each) and its colour into [colors]. */
    fun fill(t: Float, verts: FloatArray, colors: IntArray) {
        for (i in 0 until vertexCount) {
            val u = progress(i, t)
            val v = i * 2
            if (u <= 0f) {
                verts[v] = texs[v]
                verts[v + 1] = texs[v + 1]
                colors[i] = WHITE
                continue
            }
            val fall = u.pow(2.4f)
            val r = radius[i] * (1f - fall)
            val a = angle[i] + TURN * u.pow(1.3f) * shear[i]
            verts[v] = holeX + cos(a) * r
            verts[v + 1] = holeY + sin(a) * r
            colors[i] = tint(u)
        }
    }

    companion object {
        const val WHITE = -1

        /** Total turn, in radians, of matter next to the hole; farther out it turns less. */
        private const val TURN = 2.1f
        private const val SHEAR_OFFSET = 0.12f
        private const val SHEAR_POWER = 0.8f
        private const val PHASE_A = 1.3f
        private const val PHASE_B = 4.1f

        /**
         * Colour a falling point is multiplied by: it fades away over the last third of its fall and reddens (the light
         * loses blue first), the red shift of matter near a horizon.
         */
        fun tint(u: Float): Int {
            val alpha = ((1f - ((u - 0.65f) / 0.35f).coerceIn(0f, 1f).pow(1.5f)) * 255f).toInt().coerceIn(0, 255)
            val s = u.pow(2.9f)
            val green = (255f * (1f - 0.5f * s)).toInt().coerceIn(0, 255)
            val blue = (255f * (1f - 0.85f * s)).toInt().coerceIn(0, 255)
            return (alpha shl 24) or (255 shl 16) or (green shl 8) or blue
        }
    }
}
