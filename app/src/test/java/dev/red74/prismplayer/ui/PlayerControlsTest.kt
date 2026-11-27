package dev.red74.prismplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player shows only the controls that can really be pressed on what is playing. */
class PlayerControlsTest {
    private fun controls(
        live: Boolean = false,
        series: Boolean = false,
        channels: Int = 1,
        next: Boolean = false,
        catchUp: Boolean = false,
        onAir: Boolean = false,
        last: Boolean = false,
    ) = playerControls(live, series, channels, next, catchUp, onAir, last)

    @Test
    fun aFilmFromAListNeverShowsPreviousAndNextChannel() {
        assertFalse(controls(live = false, channels = 40).zap)
    }

    @Test
    fun aLiveListShowsPreviousAndNextButASingleChannelDoesNot() {
        assertTrue(controls(live = true, channels = 40).zap)
        assertFalse(controls(live = true, channels = 1).zap)
    }

    @Test
    fun theEpisodeListNeedsMoreThanOneEpisode() {
        assertTrue(controls(series = true, channels = 8).episodes)
        assertFalse(controls(series = true, channels = 1).episodes)
        assertFalse(controls(series = false, channels = 8).episodes)
    }

    @Test
    fun fromTheStartAndLastChannelAreForLiveOnly() {
        assertFalse(controls(live = false, onAir = true, last = true).catchUp)
        assertFalse(controls(live = false, onAir = true, last = true).lastChannel)
        assertTrue(controls(live = true, onAir = true).catchUp)
        assertTrue(controls(live = true, catchUp = true).catchUp)
        assertTrue(controls(live = true, last = true).lastChannel)
    }

    @Test
    fun aLiveChannelWithNothingToOfferShowsNoExtraRow() {
        val shown = controls(live = true, channels = 1)
        assertEquals(ShownControls(zap = false, episodes = false, catchUp = false, lastChannel = false), shown)
    }
}
