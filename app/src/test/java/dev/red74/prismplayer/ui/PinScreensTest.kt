package dev.red74.prismplayer.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.red74.prismplayer.data.ParentalPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Setting, checking and removing the PIN through its screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class PinScreensTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun fresh() {
        ParentalPin.init(ApplicationProvider.getApplicationContext())
        ParentalPin.clear()
    }

    private fun type(pin: String) {
        for (d in pin) rule.onNodeWithTag("pin$d", useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    @Test
    fun aPinIsSetWhenTheTwoEntriesMatchAndNotWhenTheyDiffer() {
        rule.setContent { PrismTheme { PinSettingsScreen(onBack = {}) } }
        rule.onNodeWithText("Set a PIN").performClick()
        type("1234")
        type("1111")
        assertFalse(ParentalPin.isSet)
        type("1234")
        type("1234")
        assertTrue(ParentalPin.isSet)
        assertTrue(ParentalPin.check("1234"))
    }

    @Test
    fun removingNeedsTheRightPin() {
        ParentalPin.set("2468")
        rule.setContent { PrismTheme { PinSettingsScreen(onBack = {}) } }
        rule.onNodeWithText("Remove PIN").performClick()
        type("0000")
        assertTrue(ParentalPin.isSet)
        type("2468")
        assertFalse(ParentalPin.isSet)
    }

    @Test
    fun theUnlockDialogOpensOnlyWithTheRightPin() {
        ParentalPin.set("1357")
        var opened = 0
        rule.setContent { PrismTheme { PinUnlockDialog(onOpen = { opened++ }, onDismiss = {}) } }
        type("0000")
        assertEquals(0, opened)
        type("1357")
        assertEquals(1, opened)
        assertFalse(ParentalPin.locks("XXX"))
    }
}
