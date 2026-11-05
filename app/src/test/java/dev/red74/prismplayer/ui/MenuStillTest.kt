package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home menu (taller than the screen, so it scrolls) with the same flags + Aspetto row as the real one:
 * moving the cursor flag -> flag -> Aspetto and back must leave the scroll offset and the row position untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuStillTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun focusMovesDoNotShiftTheMenu() {
        val it = FocusRequester()
        val en = FocusRequester()
        val aspect = FocusRequester()
        val scroll = androidx.compose.foundation.ScrollState(0)
        rule.setContent {
            Column(
                Modifier.height(260.dp).width(400.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(90.dp))
                Row(
                    Modifier.stillRow().testTag("row"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlagButton(italian = true, selected = true, onClick = {}, focusRequester = it)
                    FlagButton(italian = false, selected = false, onClick = {}, focusRequester = en)
                    GlassButton(text = "Aspetto", onClick = {}, modifier = Modifier.weight(1f), focusRequester = aspect)
                }
                repeat(8) { GlassButton(text = "Voce $it", onClick = {}, modifier = Modifier.fillMaxWidth()) }
            }
        }
        // A small scroll, so the row sits near the top edge where a focus move could pull the menu around.
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { scroll.scrollTo(60) } }
        rule.waitForIdle()
        it.requestFocus()
        rule.mainClock.advanceTimeBy(1000)
        val offset = scroll.value
        val bounds = rule.onNodeWithTag("row").getBoundsInRoot()
        for (target in listOf(en, aspect, en, it, aspect, it)) {
            target.requestFocus()
            rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
            assertEquals("scroll offset moved", offset, scroll.value)
            assertEquals("row moved", bounds, rule.onNodeWithTag("row").getBoundsInRoot())
        }
    }
}
