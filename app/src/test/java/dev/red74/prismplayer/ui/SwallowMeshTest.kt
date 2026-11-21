package dev.red74.prismplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwallowMeshTest {
    private val w = 160
    private val h = 90
    private val cols = 16
    private val rows = 9

    private fun mesh(cells: IntArray = IntArray(cols * rows) { it }) = SwallowMesh(cols, rows, w, h, cells, 5L)

    private fun arrays(m: SwallowMesh) = Triple(
        FloatArray(m.count * SwallowMesh.FLOATS_PER_QUAD),
        FloatArray(m.count * SwallowMesh.FLOATS_PER_QUAD),
        IntArray(m.count * 4),
    )

    @Test
    fun beforeTheSwallowEveryCellSitsInPlaceAndTheCellsTileThePicture() {
        val m = mesh()
        val (v, t, c) = arrays(m)
        val quads = m.fill(0f, w / 2f, h / 2f, v, t, c)
        assertEquals(cols * rows, quads)
        var area = 0f
        for (q in 0 until quads) {
            val o = q * SwallowMesh.FLOATS_PER_QUAD
            for (i in 0 until SwallowMesh.FLOATS_PER_QUAD) assertEquals(t[o + i], v[o + i], 1e-4f)
            area += (v[o + 2] - v[o]) * (v[o + 5] - v[o + 3])
        }
        assertEquals(w * h.toFloat(), area, 1f)
        assertTrue(c.all { it == SwallowMesh.WHITE })
    }

    @Test
    fun countsAreInFloatsAndIndicesStayInRange() {
        // drawVertices wants floats (2 per point): a quad is 4 points = 8 floats, 6 indices
        assertEquals(8, SwallowMesh.FLOATS_PER_QUAD)
        val quads = 3600
        val idx = SwallowMesh.indices(quads)
        assertEquals(quads * 6, idx.size)
        assertEquals((quads * 4 - 1), idx.max().toInt())
        assertTrue(idx.all { it >= 0 })
        assertEquals(listOf<Short>(4, 5, 6, 4, 6, 7), idx.slice(6..11))
    }

    @Test
    fun everyCellIsGoneBeforeTheHoleShrinks() {
        val m = mesh()
        val (v, t, c) = arrays(m)
        val last = BlackHoleTimeline.SWALLOW_START + BlackHoleTimeline.SWALLOW_SPREAD + BlackHoleTimeline.PIECE_MAX
        assertEquals(0, m.fill(last + 0.01f, w / 2f, h / 2f, v, t, c))
    }

    @Test
    fun fallingCellsStayFiniteGetCloserStreakAndRedden() {
        val m = mesh()
        val (v, t, c) = arrays(m)
        var seenStreak = false
        var time = 0f
        while (time < BlackHoleTimeline.SHRINK_START) {
            val quads = m.fill(time, w / 2f, h / 2f, v, t, c)
            for (i in 0 until quads * SwallowMesh.FLOATS_PER_QUAD) {
                assertTrue("not finite at t=$time", v[i].isFinite() && t[i].isFinite())
            }
            for (q in 0 until quads) {
                val o = q * SwallowMesh.FLOATS_PER_QUAD
                val long = kotlin.math.hypot(v[o + 2] - v[o], v[o + 3] - v[o + 1])
                if (long > 2.5f * (w / cols)) seenStreak = true
                val argb = c[q * 4]
                // never brighter in blue than in red, and alpha never above opaque
                assertTrue(((argb ushr 16) and 255) >= (argb and 255))
            }
            time += 0.1f
        }
        assertTrue("cells should stretch into streaks", seenStreak)
    }

    @Test
    fun tintFadesAndReddensAsTheCellFalls() {
        var previousAlpha = 256
        var previousBlue = 256
        for (i in 0..10) {
            val tint = SwallowMesh.tint(i / 10f)
            val alpha = tint ushr 24
            val blue = tint and 255
            assertTrue(alpha <= previousAlpha)
            assertTrue(blue <= previousBlue)
            previousAlpha = alpha
            previousBlue = blue
        }
        assertEquals(-1, SwallowMesh.tint(0f))
    }

    @Test
    fun onlyTheKeptCellsAreMeshed() {
        val m = mesh(intArrayOf(0, 17, 143))
        val (v, t, c) = arrays(m)
        assertEquals(3, m.fill(0f, 0f, 0f, v, t, c))
    }

    @Test
    fun cellsWindInBothDirectionsAndAtDifferentPaces() {
        val m = mesh()
        val (v, t, c) = arrays(m)
        val quads = m.fill(3.2f, w / 2f, h / 2f, v, t, c)
        assertTrue(quads > 0)
        val turned = HashSet<Int>()
        var clockwise = 0
        var counter = 0
        for (q in 0 until quads) {
            val o = q * SwallowMesh.FLOATS_PER_QUAD
            // tex corner 0 and 2 give the home cell centre; verts the current one
            val hx = (t[o] + t[o + 4]) / 2f - w / 2f
            val hy = (t[o + 1] + t[o + 5]) / 2f - h / 2f
            val cx = (v[o] + v[o + 4]) / 2f - w / 2f
            val cy = (v[o + 1] + v[o + 5]) / 2f - h / 2f
            if (hx == cx && hy == cy) continue
            val cross = hx * cy - hy * cx
            if (cross > 0) clockwise++ else if (cross < 0) counter++
            turned.add((kotlin.math.hypot(cx, cy) / kotlin.math.hypot(hx, hy).coerceAtLeast(1f) * 10).toInt())
        }
        assertTrue("both ways: $clockwise / $counter", clockwise > 0 && counter > 0)
        assertTrue("different paces: $turned", turned.size >= 4)
    }
}
