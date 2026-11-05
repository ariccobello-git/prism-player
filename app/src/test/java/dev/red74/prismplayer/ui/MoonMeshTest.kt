package dev.red74.prismplayer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The moon mesh must only draw triangles whose points really lie on the ball: no stray point (the old corner triangle). */
class MoonMeshTest {
    @Test
    fun everyDrawnTriangleStaysOnTheBall() {
        val center = Offset(800f, 400f)
        val radius = 240f
        var theta = 0.0
        while (theta < 25.0) {
            val n = MoonMesh.pose(theta, center, radius, 1024f, 512f)
            val verts = MoonMesh.vertexPositions
            val idx = MoonMesh.triangleIndices
            assertEquals(MoonMesh.FLOATS, verts.size)
            assertTrue("no triangle drawn", n > 0 && n % 3 == 0)
            for (t in 0 until n) {
                val v = idx[t].toInt()
                assertTrue("index $v out of range", v >= 0 && v * 2 + 1 < MoonMesh.FLOATS)
                val d = hypot(verts[v * 2] - center.x, verts[v * 2 + 1] - center.y)
                assertTrue("point at distance $d is off the ball at theta=$theta", d <= radius * 1.001f)
            }
            theta += 0.37
        }
    }
}
