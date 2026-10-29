package dev.red74.prismplayer.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamIdentityTest {
    @After
    fun reset() {
        StreamIdentity.preferred = null
    }

    @Test
    fun theUsualIdentityIsTriedTwiceThenTheOthers() {
        assertEquals(listOf(null, null, "VLC/3.0.20 LibVLC/3.0.20", "okhttp/4.12.0", "Lavf/60.3.100"), StreamIdentity.plan())
    }

    @Test
    fun anIdentityThatWorkedComesFirst() {
        StreamIdentity.preferred = "okhttp/4.12.0"
        assertEquals(listOf("okhttp/4.12.0", "okhttp/4.12.0", null, "VLC/3.0.20 LibVLC/3.0.20", "Lavf/60.3.100"), StreamIdentity.plan())
    }

    @Test
    fun theBuffersStartFasterThanTheLibraryDefaults() {
        assert(BufferPlan.FULL.startMs < 2_500 && BufferPlan.FULL.afterStallMs < 5_000)
        assert(BufferPlan.PREVIEW.maxBytes < BufferPlan.FULL.maxBytes)
    }
}
