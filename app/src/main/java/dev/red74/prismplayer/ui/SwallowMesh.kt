package dev.red74.prismplayer.ui

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * The picture being swallowed by the black hole, cut into fine cells. Each cell is one quad of a mesh: while it waits
 * it sits in place; when its turn comes it spirals into the hole, stretching into a streak along its path, thinning,
 * turning red and fading as it nears the horizon. Only plain arrays and maths, so it is tested on the JVM.
 *
 * Counts follow the `Canvas.drawVertices` convention: every array is filled in FLOATS (2 per point), see
 * [FLOATS_PER_QUAD]; passing point counts instead drew a stray triangle from the screen corner once (moon mesh).
 */
internal class SwallowMesh(val cols: Int, val rows: Int, val width: Int, val height: Int, cells: IntArray, seed: Long) {
    /** Cells (row * cols + col) of this layer that are swallowed. */
    val cell: IntArray = cells
    val count: Int get() = cell.size
    private val start = FloatArray(cells.size)
    private val duration = FloatArray(cells.size)

    init {
        val random = java.util.Random(seed)
        for (i in cells.indices) {
            start[i] = BlackHoleTimeline.SWALLOW_START + random.nextFloat() * BlackHoleTimeline.SWALLOW_SPREAD
            duration[i] = BlackHoleTimeline.PIECE_MIN + random.nextFloat() * (BlackHoleTimeline.PIECE_MAX - BlackHoleTimeline.PIECE_MIN)
        }
    }

    /**
     * Writes the quads of every cell still on screen at time [t], packed from the start of the arrays, and returns how
     * many. [verts] and [texs] take 8 floats per quad, [colors] 4 ints (one per corner).
     */
    fun fill(t: Float, holeX: Float, holeY: Float, verts: FloatArray, texs: FloatArray, colors: IntArray): Int {
        val cw = width.toFloat() / cols
        val ch = height.toFloat() / rows
        var q = 0
        for (i in cell.indices) {
            val f = BlackHoleTimeline.fall(t, start[i], duration[i])
            if (f >= 1f) continue
            val col = cell[i] % cols
            val row = cell[i] / cols
            val x0 = col * cw
            val y0 = row * ch
            val x1 = if (col == cols - 1) width.toFloat() else (col + 1) * cw
            val y1 = if (row == rows - 1) height.toFloat() else (row + 1) * ch
            val v = q * FLOATS_PER_QUAD
            val c = q * 4
            // texture corners, in pixels of the layer: top-left, top-right, bottom-right, bottom-left
            texs[v] = x0; texs[v + 1] = y0
            texs[v + 2] = x1; texs[v + 3] = y0
            texs[v + 4] = x1; texs[v + 5] = y1
            texs[v + 6] = x0; texs[v + 7] = y1
            if (f <= 0f) {
                verts[v] = x0; verts[v + 1] = y0
                verts[v + 2] = x1; verts[v + 3] = y0
                verts[v + 4] = x1; verts[v + 5] = y1
                verts[v + 6] = x0; verts[v + 7] = y1
                colors[c] = WHITE; colors[c + 1] = WHITE; colors[c + 2] = WHITE; colors[c + 3] = WHITE
            } else {
                val hx = (x0 + x1) / 2f
                val hy = (y0 + y1) / 2f
                val r0 = hypot(hx - holeX, hy - holeY)
                val a0 = atan2(hy - holeY, hx - holeX)
                val turn = SPIRAL * f.pow(1.3f)
                val r = r0 * (1f - f)
                val a = a0 + turn
                val cx = holeX + cos(a) * r
                val cy = holeY + sin(a) * r
                // direction of travel: inwards plus the sideways swing of the spiral
                val swing = SPIRAL * 1.3f * f.pow(0.3f)
                val dx = -cos(a) - sin(a) * swing * (1f - f)
                val dy = -sin(a) + cos(a) * swing * (1f - f)
                val len = hypot(dx, dy).coerceAtLeast(1e-6f)
                val ux = dx / len
                val uy = dy / len
                val halfLong = (x1 - x0) / 2f * (1f + STREAK * f.pow(0.9f)) * (1f - f * 0.35f)
                val halfWide = (y1 - y0) / 2f * (1f - f).pow(0.9f)
                val lx = ux * halfLong
                val ly = uy * halfLong
                val wx = -uy * halfWide
                val wy = ux * halfWide
                verts[v] = cx - lx - wx; verts[v + 1] = cy - ly - wy
                verts[v + 2] = cx + lx - wx; verts[v + 3] = cy + ly - wy
                verts[v + 4] = cx + lx + wx; verts[v + 5] = cy + ly + wy
                verts[v + 6] = cx - lx + wx; verts[v + 7] = cy - ly + wy
                val tint = tint(f)
                colors[c] = tint; colors[c + 1] = tint; colors[c + 2] = tint; colors[c + 3] = tint
            }
            q++
        }
        return q
    }

    companion object {
        const val FLOATS_PER_QUAD = 8
        const val WHITE = -1
        private const val SPIRAL = 2.4f
        private const val STREAK = 5f

        /** Indices of [quads] quads: two triangles each, over 4 corners per quad. */
        fun indices(quads: Int): ShortArray = ShortArray(quads * 6).also { out ->
            for (q in 0 until quads) {
                val b = q * 4
                val o = q * 6
                out[o] = b.toShort(); out[o + 1] = (b + 1).toShort(); out[o + 2] = (b + 2).toShort()
                out[o + 3] = b.toShort(); out[o + 4] = (b + 2).toShort(); out[o + 5] = (b + 3).toShort()
            }
        }

        /**
         * Colour a falling cell is multiplied by: fades as it goes in and reddens (the light loses blue first), the
         * red shift of matter near a horizon.
         */
        fun tint(f: Float): Int {
            val alpha = ((1f - f).pow(0.35f) * 255f).toInt().coerceIn(0, 255)
            val s = f.pow(1.3f)
            val green = (255f * (1f - 0.55f * s)).toInt().coerceIn(0, 255)
            val blue = (255f * (1f - 0.9f * s)).toInt().coerceIn(0, 255)
            return (alpha shl 24) or (255 shl 16) or (green shl 8) or blue
        }
    }
}
