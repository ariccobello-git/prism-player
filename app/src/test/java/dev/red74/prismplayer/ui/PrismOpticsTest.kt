package dev.red74.prismplayer.ui

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrismOpticsTest {
    private val mid = PrismOptics.INDICES[3]

    @Test
    fun classicViewHasTheTextbookMinimumDeviation() {
        val p = PrismOptics.trace(PrismOptics.CLASSIC_THETA, mid)!!
        assertTrue(p.exits)
        val expected = 2 * asin(mid * sin(Math.toRadians(30.0))) - Math.toRadians(60.0)
        assertEquals(Math.toDegrees(expected), Math.toDegrees(abs(p.exitAngle - PrismOptics.BEAM_ANGLE)), 0.4)
    }

    @Test
    fun violetBendsMoreThanRedAtTheClassicView() {
        val angles = PrismOptics.INDICES.map { PrismOptics.trace(PrismOptics.CLASSIC_THETA, it)!!.also { p -> assertTrue(p.exits) }.exitAngle }
        for (i in 1 until angles.size) assertTrue("colour $i", angles[i] > angles[i - 1])
        val spread = Math.toDegrees(angles.last() - angles.first())
        assertTrue("spread $spread", spread in 2.0..8.0)
    }

    @Test
    fun everyColourLeavesSmoothlyInsideTheVisibleWindow() {
        var previous: List<Double>? = null
        var rel = -12.0
        while (rel <= 4.0) {
            val theta = PrismOptics.CLASSIC_THETA + Math.toRadians(rel)
            val paths = PrismOptics.INDICES.map { PrismOptics.trace(theta, it) }
            assertTrue("rel $rel", paths.all { it != null && it.exits })
            val angles = paths.map { it!!.exitAngle }
            assertTrue("fan too wide at $rel", angles.last() - angles.first() < Math.toRadians(8.0))
            previous?.let { prev -> assertTrue("jump at $rel", abs(angles[0] - prev[0]) < Math.toRadians(1.5)) }
            previous = angles
            rel += 0.25
        }
    }

    @Test
    fun lightLostAtTheSurfacesIsPlausible() {
        for (n in PrismOptics.INDICES) {
            val p = PrismOptics.trace(PrismOptics.CLASSIC_THETA, n)!!
            assertTrue(p.transmission > 0.5 && p.transmission < 1.0)
            assertNotNull(p.inside.lastOrNull())
        }
    }

    @Test
    fun theBeamAlwaysMeetsTheGlass() {
        var deg = 0.0
        while (deg < 360.0) {
            assertNotNull("at $deg", PrismOptics.trace(Math.toRadians(deg), mid))
            deg += 1.0
        }
    }

    @Test
    fun relativeAngleWrapsEveryThirdOfATurn() {
        val a = PrismOptics.relativeToClassic(PrismOptics.CLASSIC_THETA + Math.toRadians(7.0))
        val b = PrismOptics.relativeToClassic(PrismOptics.CLASSIC_THETA + Math.toRadians(7.0 + 120.0))
        assertEquals(a, b, 1e-9)
        assertEquals(7.0, Math.toDegrees(a), 1e-6)
    }
}

class PrismMotionTest {
    @Test
    fun startsSlowSpeedsUpAndStopsAtEightPointOneSeconds() {
        assertTrue(PrismMotion.speed(0.0) in 1.0..10.0)
        val peak = (0..810).maxOf { PrismMotion.speed(it / 100.0) }
        assertTrue("peak $peak", peak in 50.0..110.0)
        assertEquals(0.0, PrismMotion.speed(8.1), 0.0)
        assertEquals(0.0, PrismMotion.speed(9.5), 0.0)
        assertTrue(PrismMotion.speed(7.8) < 6.0)
    }

    @Test
    fun restsAtTheClassicViewForTheLastTwoAndAHalfSeconds() {
        val still = PrismMotion.angle(8.1)
        assertEquals(PrismOptics.CLASSIC_THETA, still, 1e-9)
        for (t in listOf(8.5, 9.0, 10.0, 10.6)) assertEquals(still, PrismMotion.angle(t), 1e-12)
        assertEquals(2.5, PrismMotion.MUSIC_END - PrismMotion.STOP_AT, 1e-9)
    }

    @Test
    fun turnsOneWayWithoutJumps() {
        var previous = PrismMotion.angle(0.0)
        var t = 1.0 / 60
        while (t <= 8.2) {
            val a = PrismMotion.angle(t)
            assertTrue("goes back at $t", a >= previous - 1e-12)
            assertTrue("jump at $t", Math.toDegrees(a - previous) < 2.6)
            previous = a
            t += 1.0 / 60
        }
    }

    @Test
    fun theHitFindsThePrismInsideTheVisibleWindow() {
        val rel = Math.toDegrees(PrismOptics.relativeToClassic(PrismMotion.angle(PrismMotion.IMPACT)))
        assertTrue("relative angle at the hit: $rel", rel in -14.0..-7.0)
        assertTrue(PrismMotion.remainingDegrees(PrismMotion.IMPACT) in 7.0..14.0)
    }
}
