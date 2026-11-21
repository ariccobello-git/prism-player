package dev.red74.prismplayer.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live must play at true 1x: no slow motion (0.97x) while building a cushion, no speed-up to catch up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSpeedTest {
    @Test
    fun theSpeedControlNeverLeavesOneX() {
        val control = steadyLiveSpeed()
        // A stream that declares its own range and target offset (the worst case) is still held at 1x by the item.
        control.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        for ((offsetUs, bufferedUs) in listOf(
            0L to 0L, 1_000_000L to 500_000L, 2_000_000L to 10_000_000L, 5_000_000L to 5_000_000L,
            9_000_000L to 30_000_000L, 60_000_000L to 60_000_000L,
        )) {
            control.setTargetLiveOffsetOverrideUs(5_000_000L)
            assertEquals("offset $offsetUs buffered $bufferedUs", 1f, control.getAdjustedPlaybackSpeed(offsetUs, bufferedUs), 0f)
        }
    }

    @Test
    fun theSpeedControlWithoutAnItemAlsoStaysAtOneX() {
        val control = steadyLiveSpeed()
        control.setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
        control.setTargetLiveOffsetOverrideUs(C.TIME_UNSET)
        assertEquals(1f, control.getAdjustedPlaybackSpeed(1_000_000L, 200_000L), 0f)
        assertEquals(1f, control.getAdjustedPlaybackSpeed(30_000_000L, 20_000_000L), 0f)
    }

    @Test
    fun everyPlaybackItemPinsTheSpeedToOneX() {
        for (url in listOf("http://example/live/1.ts", "http://example/movie/2.mkv", "http://example/live/3.m3u8")) {
            val live = playbackItem(url).liveConfiguration
            assertEquals(1f, live.minPlaybackSpeed, 0f)
            assertEquals(1f, live.maxPlaybackSpeed, 0f)
        }
        assertEquals(1f, previewItem("http://example/x").liveConfiguration.maxPlaybackSpeed, 0f)
    }
}
