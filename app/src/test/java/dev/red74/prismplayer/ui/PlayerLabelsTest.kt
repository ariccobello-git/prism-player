package dev.red74.prismplayer.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import dev.red74.prismplayer.R
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's buttons are one line wide: in every language (French is the longest) each label must stay short
 * enough for its button, and no label may be missing in a language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerLabelsTest {
    private val labels = listOf(
        R.string.np_episodes, R.string.np_channels, R.string.np_next_episode, R.string.np_audio_subs, R.string.np_format,
        R.string.catchup_from_start, R.string.catchup_back_to_live, R.string.player_last_channel,
        R.string.player_previous, R.string.player_next, R.string.sleep_button, R.string.np_live,
    )

    private fun inLanguage(code: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale(code)) }
        return base.createConfigurationContext(config)
    }

    @Test
    fun everyPlayerLabelIsShortAndPresentInAllThreeLanguages() {
        for (code in listOf("it", "en", "fr")) {
            val context = inLanguage(code)
            for (id in labels) {
                val text = context.getString(id)
                assertTrue("$code: label $id is empty", text.isNotBlank())
                assertTrue("$code: '$text' is too long for a button (${text.length})", text.length <= 24)
            }
        }
    }
}
