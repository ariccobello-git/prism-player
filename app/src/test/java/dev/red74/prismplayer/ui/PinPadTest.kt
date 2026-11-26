package dev.red74.prismplayer.ui

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keypad of the PIN: four taps give the PIN once, and the remote's digit keys are digits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class PinPadTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun fourTapsDeliverThePinOnceAndClearTheDots() {
        val got = ArrayList<String>()
        rule.setContent { PrismTheme { PinPad(title = "PIN", onComplete = { got += it }) } }
        for (d in "4071") rule.onNodeWithTag("pin$d", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("4071"), got)
        // The next four start again from nothing.
        for (d in "0000") rule.onNodeWithTag("pin$d", useUnmergedTree = true).performClick()
        assertEquals(listOf("4071", "0000"), got)
    }

    @Test
    fun deleteTakesBackTheLastDigit() {
        val got = ArrayList<String>()
        rule.setContent { PrismTheme { PinPad(title = "PIN", onComplete = { got += it }) } }
        for (d in "12") rule.onNodeWithTag("pin$d", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("pinDelete", useUnmergedTree = true).performClick()
        for (d in "345") rule.onNodeWithTag("pin$d", useUnmergedTree = true).performClick()
        assertEquals(listOf("1345"), got)
    }

    @Test
    fun remoteKeysAreDigits() {
        assertEquals('0', pinDigitOf(KeyEvent.KEYCODE_0))
        assertEquals('9', pinDigitOf(KeyEvent.KEYCODE_9))
        assertEquals('5', pinDigitOf(KeyEvent.KEYCODE_NUMPAD_5))
        assertNull(pinDigitOf(KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
