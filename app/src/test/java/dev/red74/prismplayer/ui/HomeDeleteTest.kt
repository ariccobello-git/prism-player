package dev.red74.prismplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import dev.red74.prismplayer.data.PlaylistStatus
import dev.red74.prismplayer.data.SavedPlaylist
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deleting a playlist from Home asks first, and the cursor starts on "Don't delete". */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class HomeDeleteTest {
    @get:Rule
    val rule = createComposeRule()

    private val deleted = mutableListOf<String>()

    private fun show() {
        val list = SavedPlaylist(id = "p1", name = "Lista uno", url = "http://x/y", addedAtEpochMs = 0L, status = PlaylistStatus.OK, channelCount = 5)
        rule.setContent {
            HomeScreen(
                playlists = listOf(list), verifying = emptySet(), removedCount = 0, notice = null,
                onOpen = {}, onVerify = {}, onVerifyLists = {}, onDelete = { deleted += it }, onAddUrl = {}, onPair = {},
                onUpdates = {}, onCheckUpdates = {}, onShortcuts = {}, onRemoved = {}, onAppearance = {}, onNoticeShown = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun deleteAsksFirstAndTheCursorIsOnKeep() {
        show()
        rule.onNodeWithTag("delete:p1").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(emptyList<String>(), deleted)
        rule.onNodeWithTag("keep:p1").assertIsDisplayed()
        rule.onNodeWithTag("keep:p1").assertIsFocused()
        rule.onNodeWithTag("confirmDelete:p1").assertIsDisplayed()
    }

    @Test
    fun keepGoesBackWithoutDeletingAndTheCursorReturnsToDelete() {
        show()
        rule.onNodeWithTag("delete:p1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("keep:p1").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(emptyList<String>(), deleted)
        rule.onNodeWithTag("delete:p1").assertIsFocused()
        assertEquals(0, rule.onAllNodesWithTag("keep:p1").fetchSemanticsNodes().size)
    }

    @Test
    fun theSecondButtonDeletesForReal() {
        show()
        rule.onNodeWithTag("delete:p1").performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(10_000) // no timeout: the question waits
        rule.waitForIdle()
        rule.onNodeWithTag("confirmDelete:p1").performClick()
        assertEquals(listOf("p1"), deleted)
    }
}
