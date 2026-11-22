package dev.red74.prismplayer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RimPointTest {
    private val c = Offset(100f, 100f)

    private fun radius(p: Offset) = hypot(p.x - c.x, p.y - c.y)

    @Test
    fun endsStayWhereTheyWere() {
        val a = Offset(60f, 110f)
        val b = Offset(150f, 80f)
        val s = rimPoint(a, b, 0f, c, 94f)
        val e = rimPoint(a, b, 1f, c, 94f)
        assertEquals(a.x, s.x, 1e-3f); assertEquals(a.y, s.y, 1e-3f)
        assertEquals(b.x, e.x, 1e-3f); assertEquals(b.y, e.y, 1e-3f)
    }

    @Test
    fun theMiddleHugsTheRimNotTheChord() {
        val a = Offset(100f - 50f, 100f - 40f)
        val b = Offset(100f + 50f, 100f - 40f)
        val chordMid = radius(Offset(100f, 60f))
        val mid = radius(rimPoint(a, b, 0.5f, c, 94f))
        assertTrue(mid > chordMid + 20f)
        assertTrue(mid <= 94f + 1e-3f)
    }

    @Test
    fun neverGoesBelowTheStraightRadiusAndNeverLeavesTheRim() {
        val a = Offset(100f + 90f, 100f)
        val b = Offset(100f, 100f + 90f)
        for (k in 0..20) {
            val r = radius(rimPoint(a, b, k / 20f, c, 94f))
            assertTrue(r >= 89.9f && r <= 94.01f)
        }
    }

    @Test
    fun takesTheShortWayRoundAcrossTheSeam() {
        val a = Offset(100f - 80f, 100f - 1f)   // just above the negative x axis
        val b = Offset(100f - 80f, 100f + 1f)   // just below it
        val m = rimPoint(a, b, 0.5f, c, 94f)
        assertTrue("went round the long way", m.x < 100f)
    }
}
