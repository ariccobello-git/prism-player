package dev.red74.prismplayer.player

import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The large card's preview opens no audio at all; every other player keeps its sound. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilentPreviewTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun aSilentPreviewHasNoAudioTrackType() {
        val player = createPlayer(context, preview = true, silent = true)
        try {
            assertTrue(C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes)
        } finally {
            player.release()
        }
    }

    @Test
    fun theUsualPreviewAndTheFullScreenPlayerKeepTheirSound() {
        for (preview in listOf(true, false)) {
            val player = createPlayer(context, preview = preview)
            try {
                assertFalse(C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes)
            } finally {
                player.release()
            }
        }
    }
}
