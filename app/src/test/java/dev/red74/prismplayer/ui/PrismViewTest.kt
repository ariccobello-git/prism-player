package dev.red74.prismplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrismViewTest {
    @Test
    fun theViewSwingsWhileThePrismTurnsAndRestsWithIt() {
        assertTrue(kotlin.math.abs(PrismView.yaw(0.0) - PrismView.REST_YAW) > 0.3)
        for (t in listOf(PrismMotion.STOP_AT, 9.0, PrismMotion.MUSIC_END)) assertEquals(PrismView.REST_YAW, PrismView.yaw(t), 1e-9)
    }

    @Test
    fun turningKeepsLengths() {
        val v = PrismView.toView(0.3, -0.8, 1.1, 0.7)
        assertEquals(0.09 + 0.64 + 1.21, v.dot(v), 1e-9)
    }

    @Test
    fun perspectiveGrowsTowardsTheViewerAndStaysFinite() {
        assertEquals(1.0, PrismView.scale(0.0), 1e-12)
        assertTrue(PrismView.scale(-3.0) > 1.5)
        assertTrue(PrismView.scale(2.0) < 1.0)
        assertEquals(PrismView.scale(PrismView.NEAREST), PrismView.scale(-100.0), 1e-12)
    }

    @Test
    fun theLongSideFacesShowWhenThePrismRests() {
        // with the resting view the near end is to the left and closer to the viewer than the far end
        val near = PrismView.toView(0.0, 0.0, -PrismView.HALF_LENGTH, PrismView.REST_YAW)
        val far = PrismView.toView(0.0, 0.0, PrismView.HALF_LENGTH, PrismView.REST_YAW)
        assertTrue(near.z < far.z)
        assertTrue(kotlin.math.abs(near.x - far.x) > 1.0)
    }
}

class BlackHoleTimelineTest {
    @Test
    fun theHoleGrowsStaysThenClosesBeforeThePoint() {
        assertEquals(0f, BlackHoleTimeline.holeScale(0f), 0f)
        assertEquals(1f, BlackHoleTimeline.holeScale(BlackHoleTimeline.HOLE_IN_START + BlackHoleTimeline.HOLE_IN + 0.1f), 1e-6f)
        assertEquals(1f, BlackHoleTimeline.holeScale(BlackHoleTimeline.SHRINK_START), 1e-6f)
        assertEquals(0f, BlackHoleTimeline.holeScale(BlackHoleTimeline.POINT_START), 1e-6f)
    }

    @Test
    fun everyPieceIsSwallowedBeforeTheHoleCloses() {
        val last = BlackHoleTimeline.SWALLOW_START + BlackHoleTimeline.SWALLOW_SPREAD + BlackHoleTimeline.PIECE_MAX
        assertTrue(last <= BlackHoleTimeline.SHRINK_START + 1e-4f)
        assertEquals(1f, BlackHoleTimeline.fall(last, BlackHoleTimeline.SWALLOW_START + BlackHoleTimeline.SWALLOW_SPREAD, BlackHoleTimeline.PIECE_MAX), 1e-6f)
    }

    @Test
    fun thePointShinesTwoSecondsThenTheScreenStaysBlackOneAndAHalf() {
        assertEquals(1f, BlackHoleTimeline.point(BlackHoleTimeline.POINT_START + 1f), 0f)
        assertEquals(0f, BlackHoleTimeline.point(BlackHoleTimeline.POINT_START + BlackHoleTimeline.POINT), 1e-6f)
        assertEquals(2.0f, BlackHoleTimeline.POINT, 0f)
        assertEquals(1.5f, BlackHoleTimeline.TOTAL - BlackHoleTimeline.POINT_START - BlackHoleTimeline.POINT, 1e-6f)
    }
}
