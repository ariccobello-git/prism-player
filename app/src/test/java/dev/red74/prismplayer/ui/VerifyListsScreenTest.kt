package dev.red74.prismplayer.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.red74.prismplayer.data.PlaylistStatus
import dev.red74.prismplayer.data.SavedPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One, several or all lists can be picked, and only the picked ones are started. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifyListsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun list(id: String) = SavedPlaylist(id = id, name = "Lista $id", url = "", addedAtEpochMs = 0L, status = PlaylistStatus.UNVERIFIED)

    @Test
    fun onlyThePickedListsAreStarted() {
        var started: Set<String>? = null
        rule.setContent {
            VerifyListsScreen(playlists = listOf(list("a"), list("b"), list("c")), onStart = { started = it }, onBack = {})
        }
        rule.onNodeWithText("Lista a", substring = true).performClick()
        rule.onNodeWithText("Lista c", substring = true).performClick()
        rule.onNodeWithText("Verify 2", substring = true).performClick()
        assertEquals(setOf("a", "c"), started)
    }

    @Test
    fun nothingPickedStartsNothingAndSelectAllPicksEverything() {
        var started: Set<String>? = null
        rule.setContent {
            VerifyListsScreen(playlists = listOf(list("a"), list("b")), onStart = { started = it }, onBack = {})
        }
        rule.onNodeWithText("Verify 0", substring = true).performClick()
        assertTrue(started == null)
        rule.onNodeWithText("Select all", substring = true).performClick()
        rule.onNodeWithText("Verify 2", substring = true).performClick()
        assertEquals(setOf("a", "b"), started)
    }
}
