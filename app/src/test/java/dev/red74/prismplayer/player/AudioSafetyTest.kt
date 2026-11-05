package dev.red74.prismplayer.player

import androidx.media3.common.PlaybackException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSafetyTest {
    @After
    fun reset() { AudioSafety.on = false }

    @Test
    fun soundErrorSwitchesToSaferSoundOnce() {
        assertTrue(AudioSafety.switchOn(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertTrue(AudioSafety.on)
        // Already safe: the next error is shown, not retried forever.
        assertFalse(AudioSafety.switchOn(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
    }

    @Test
    fun otherErrorsLeaveSoundAlone() {
        assertFalse(AudioSafety.switchOn(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(AudioSafety.switchOn(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(AudioSafety.on)
    }
}
