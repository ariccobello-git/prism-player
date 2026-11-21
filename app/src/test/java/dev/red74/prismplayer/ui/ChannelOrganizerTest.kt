package dev.red74.prismplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryState
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.CatalogBuilder
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ChannelPrefs
import dev.red74.prismplayer.data.CustomizedCatalog
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.FavoriteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Arranging live channels: the page that hides channels and groups, the button on each row, the hidden page. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class ChannelOrganizerTest {
    @get:Rule
    val rule = createComposeRule()

    private val rai1 = Channel("Rai Uno", "http://h/live/u/p/1.ts", "Italia")
    private val rai2 = Channel("Rai Due", "http://h/live/u/p/2.ts", "Italia")
    private val sky = Channel("Sky Sport", "http://h/live/u/p/3.ts", "Sport")

    private fun focusedTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "?" }

    @Test
    fun theHideButtonIsFirstAndHidesTheChannel() {
        var prefs = ChannelPrefs()
        var hidden: Boolean? = null
        rule.setContent {
            OrganizePanel(
                channel = rai1, prefs = prefs,
                onChange = { change -> prefs = change(prefs) },
                onHidden = { hidden = it }, onManage = {}, onClose = {},
            )
        }
        rule.waitForIdle()
        assertTrue("the cursor should start on the first action, it is on ${focusedTexts()}", "Hide this channel" in focusedTexts())
        rule.onNodeWithText("Hide this channel").performClick()
        assertTrue(prefs.isChannelHidden(FavoriteKey.of(rai1)))
        assertEquals(false, hidden)
    }

    @Test
    fun hidingTheGroupNamesTheGroupAndReportsIt() {
        var prefs = ChannelPrefs()
        var hidden: Boolean? = null
        rule.setContent {
            OrganizePanel(rai1, prefs, { change -> prefs = change(prefs) }, { hidden = it }, {}, {})
        }
        rule.onNodeWithText("Hide the group “Italia”").performClick()
        assertEquals(setOf("Italia"), prefs.hiddenGroups)
        assertEquals(true, hidden)
    }

    @Test
    fun aNewGroupCanBeStartedAndTheChannelGoesIn() {
        var prefs = ChannelPrefs().withGroup("Mie").withChannelInGroup("Mie", FavoriteKey.of(rai2), true)
        var closed = 0
        rule.setContent {
            OrganizePanel(rai1, prefs, { change -> prefs = change(prefs) }, {}, {}, { closed++ })
        }
        // the existing group is offered, and adding to it closes the page
        rule.onNodeWithText("Add to ★ Mie").performClick()
        assertEquals(listOf(FavoriteKey.of(rai2), FavoriteKey.of(rai1)), prefs.groups.single().keys)
        assertEquals(1, closed)
    }

    @Test
    fun aChannelAlreadyInAGroupOffersToTakeItOut() {
        var prefs = ChannelPrefs().withGroup("Mie").withChannelInGroup("Mie", FavoriteKey.of(rai1), true)
        rule.setContent {
            OrganizePanel(rai1, prefs, { change -> prefs = change(prefs) }, {}, {}, {})
        }
        rule.onNodeWithText("Remove from ★ Mie").performClick()
        assertTrue(prefs.groups.single().keys.isEmpty())
    }

    @Test
    fun theHiddenPageListsEverythingAndShowsItAgain() {
        var prefs = ChannelPrefs().withHiddenGroup("Sport", true)
            .withHiddenChannel(dev.red74.prismplayer.data.HiddenChannel(FavoriteKey.of(rai1), "Italia", "Rai Uno"), true)
            .withGroup("Mie")
        rule.setContent { ManageChannelsScreen(prefs, { change -> prefs = change(prefs) }, {}) }
        rule.waitForIdle()
        assertTrue("the cursor starts on Back, it is on ${focusedTexts()}", "Back" in focusedTexts())
        rule.onNodeWithText("Show again: Sport").assertIsDisplayed()
        rule.onNodeWithText("Show again: Rai Uno").assertIsDisplayed()
        rule.onNodeWithText("Delete group: ★ Mie").assertIsDisplayed()
        rule.onNodeWithText("Show again: Rai Uno").performClick()
        rule.waitForIdle()
        assertFalse(prefs.isChannelHidden(FavoriteKey.of(rai1)))
        // after a change the cursor is back on Back (the item it was on is gone)
        assertTrue("the cursor should return to Back, it is on ${focusedTexts()}", "Back" in focusedTexts())
    }

    @Test
    fun theEmptyHiddenPageSaysSo() {
        rule.setContent { ManageChannelsScreen(ChannelPrefs(), {}, {}) }
        rule.onNodeWithText("Nothing is hidden and there are no groups of your own yet.").assertIsDisplayed()
    }

    @Test
    fun everyLiveRowHasTheArrangeButtonAndItOpensThePage() {
        val catalog: Catalog = CustomizedCatalog.of(CatalogBuilder.build(listOf(rai1, rai2, sky), null, false), ChannelPrefs().withHiddenChannel(dev.red74.prismplayer.data.HiddenChannel(FavoriteKey.of(rai2), "Italia", "Rai Due"), true))
        var nav by mutableStateOf(LibraryNav(tab = LibraryTab.LIVE, level = 2, category = 0))
        rule.setContent {
            LibraryScreen(
                state = LibraryState(playlistId = "p", playlistName = "Lista", loading = false, catalog = catalog),
                nav = nav, epg = EpgData.Empty, favorites = emptySet(),
                onNav = { change -> nav = change(nav) },
                onPlay = { _, _, _ -> }, onToggleFavorite = {}, onRetry = {}, onBack = {},
            )
        }
        rule.waitForIdle()
        // Rai Due is hidden: only the first channel of the group is listed, with its own button
        rule.onNodeWithText("Rai Uno").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Rai Due").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithContentDescription("Arrange channel").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Arrange channel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Arrange this channel").assertIsDisplayed()
        assertTrue("the cursor should be on the first action, it is on ${focusedTexts()}", "Hide this channel" in focusedTexts())
    }
}
