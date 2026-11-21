package dev.red74.prismplayer.ui

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpMeshTest {
    private val w = 1920
    private val h = 1080

    private fun mesh(cols: Int = 40, rows: Int = 22, keep: (Int) -> Boolean = { true }) = WarpMesh(cols, rows, w, h, w / 2f, h / 2f, keep)

    private fun frame(m: WarpMesh, t: Float): Pair<FloatArray, IntArray> {
        val v = FloatArray(m.vertexCount * 2)
        val c = IntArray(m.vertexCount)
        m.fill(t, v, c)
        return v to c
    }

    private fun cross(v: FloatArray, a: Int, b: Int, c: Int): Float =
        (v[b * 2] - v[a * 2]) * (v[c * 2 + 1] - v[a * 2 + 1]) - (v[b * 2 + 1] - v[a * 2 + 1]) * (v[c * 2] - v[a * 2])

    @Test
    fun beforeThePullTheSheetIsTheUntouchedPicture() {
        val m = mesh()
        val (v, c) = frame(m, 0f)
        for (i in v.indices) assertEquals(m.texs[i], v[i], 1e-4f)
        assertTrue(c.all { it == WarpMesh.WHITE })
        // the triangles tile the whole picture
        var area = 0f
        for (k in m.indices.indices step 3) area += cross(v, m.indices[k].toInt(), m.indices[k + 1].toInt(), m.indices[k + 2].toInt()) / 2f
        assertEquals(w * h.toFloat(), area, 1f)
    }

    @Test
    fun countsAreInFloatsAndIndicesStayInRange() {
        // drawVertices wants floats (2 per point)
        val m = mesh()
        assertEquals(41 * 23, m.vertexCount)
        assertEquals(m.vertexCount * 2, m.texs.size)
        assertEquals(40 * 22 * 6, m.indices.size)
        assertTrue(m.indices.all { it >= 0 && it < m.vertexCount })
    }

    @Test
    fun onlyKeptCellsAreDrawnAndOnlyTheirCornersExist() {
        val m = mesh(10, 10) { it == 0 || it == 55 }
        assertEquals(12, m.indices.size)
        assertEquals(8, m.vertexCount)
        assertTrue(m.indices.all { it >= 0 && it < m.vertexCount })
    }

    @Test
    fun theFinestGridStaysWithinSixteenBitIndices() {
        val m = WarpMesh(120, 68, w, h, w / 2f, h / 2f)
        assertEquals(121 * 69, m.vertexCount)
        assertTrue(m.vertexCount <= Short.MAX_VALUE)
        assertEquals(m.vertexCount - 1, m.indices.maxOf { it.toInt() })
    }

    @Test
    fun everythingIsInTheHoleAndInvisibleOnceThePullIsOver() {
        val m = mesh()
        val (v, c) = frame(m, BlackHoleTimeline.WARP_END + 0.01f)
        for (i in 0 until m.vertexCount) {
            assertEquals(w / 2f, v[i * 2], 0.01f)
            assertEquals(h / 2f, v[i * 2 + 1], 0.01f)
            assertEquals(0, c[i] ushr 24)
        }
    }

    @Test
    fun nearPartsFallBeforeFarOnesAndPointsStayFinite() {
        val m = mesh()
        for (step in 0..60) {
            val t = step / 10f
            val (v, _) = frame(m, t)
            assertTrue(v.all { it.isFinite() })
        }
        // the point nearest the hole starts earlier than the farthest corner
        val nearest = (0 until m.vertexCount).minBy { hypot(m.texs[it * 2] - w / 2f, m.texs[it * 2 + 1] - h / 2f) }
        val farthest = (0 until m.vertexCount).maxBy { hypot(m.texs[it * 2] - w / 2f, m.texs[it * 2 + 1] - h / 2f) }
        val t = BlackHoleTimeline.WARP_START + 0.8f
        assertTrue(m.progress(nearest, t) > m.progress(farthest, t))
        assertEquals(0f, m.progress(farthest, BlackHoleTimeline.WARP_START), 0f)
    }

    @Test
    fun theSheetNeverFoldsOverItselfNoticeably() {
        // a folded triangle would show as a crease in the picture; allow only a tiny sliver of one cell
        val m = mesh(120, 68)
        val cell = (w / 120f) * (h / 68f)
        for (step in 0..60 step 2) {
            val (v, _) = frame(m, step / 10f)
            for (k in m.indices.indices step 3) {
                val area = cross(v, m.indices[k].toInt(), m.indices[k + 1].toInt(), m.indices[k + 2].toInt())
                assertTrue("t=${step / 10f} triangle $k area $area", area > -0.2f * cell)
            }
        }
    }

    @Test
    fun fadeAndRedShiftOnTheWayIn() {
        assertEquals(255, WarpMesh.tint(0f) ushr 24)
        assertEquals(0, WarpMesh.tint(1f) ushr 24)
        val late = WarpMesh.tint(0.9f)
        assertTrue(((late shr 8) and 255) < 255)
        assertTrue((late and 255) < ((late shr 8) and 255))
        assertEquals(255, (late shr 16) and 255)
    }
}
