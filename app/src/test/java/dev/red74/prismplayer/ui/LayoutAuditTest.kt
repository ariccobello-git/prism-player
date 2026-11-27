package dev.red74.prismplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.HighlightEvent
import dev.red74.prismplayer.data.HighlightKind
import dev.red74.prismplayer.data.HighlightsData
import dev.red74.prismplayer.data.PlaylistStatus
import dev.red74.prismplayer.data.SavedPlaylist
import dev.red74.prismplayer.data.SportPrefs
import dev.red74.prismplayer.data.SportPrefsStore
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout check of the main pages on a 1280x720 dp TV canvas, in each of the three languages (French is the longest):
 * no text may be cut off by the edge of its box (it would end in "...") and none may spill past the sides of the
 * screen. It cannot judge beauty, only the defects a viewer would notice at once.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
abstract class LayoutAuditBase {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun resetChoices() {
        SportPrefsStore.update { SportPrefs() }
    }

    private val sky = Channel("Sky Sport Uno", "http://x/1", "Sport", tvgId = "s1")
    private val soon = System.currentTimeMillis() + 3_600_000L
    private val onAirStart = System.currentTimeMillis() - 600_000L
    private val data = HighlightsData(
        mapOf(
            HighlightKind.SPORT to listOf(
                HighlightEvent(HighlightKind.SPORT, "Roma - Lazio", null, onAirStart, onAirStart + 7_200_000L, 50, listOf(sky)),
            ),
            HighlightKind.FILM to listOf(HighlightEvent(HighlightKind.FILM, "Heat", null, soon, soon + 7_000_000L, 40, listOf(sky))),
        ),
    )
    private var nav by mutableStateOf(LibraryNav(tab = LibraryTab.HIGHLIGHTS))

    private fun audit(page: String) {
        rule.waitForIdle()
        val root = rule.onRoot().fetchSemanticsNode()
        val width = root.size.width
        val problems = mutableListOf<String>()
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        for (node in nodes) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            if (results.firstOrNull()?.hasVisualOverflow == true) problems += "$page: cut off: '$text'"
            val bounds = node.boundsInRoot
            if (bounds.left < -1f || bounds.right > width + 1f) problems += "$page: past the screen side: '$text'"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun homeFitsItsBoxes() {
        val list = SavedPlaylist(id = "p1", name = "Lista", url = "http://x/y", addedAtEpochMs = 0L, status = PlaylistStatus.OK, channelCount = 5)
        rule.setContent {
            HomeScreen(
                playlists = listOf(list), verifying = emptySet(), removedCount = 0, notice = null,
                onOpen = {}, onVerify = {}, onVerifyLists = {}, onDelete = {}, onAddUrl = {}, onPair = {},
                onUpdates = {}, onCheckUpdates = {}, onShortcuts = {}, onRemoved = {}, onPin = {}, onAppearance = {}, onNoticeShown = {},
            )
        }
        audit("home")
    }

    @Test
    fun theFeaturedPageFitsItsBoxes() {
        rule.setContent {
            CompositionLocalProvider(LocalHighlights provides data) {
                HighlightsView(nav = nav, onNav = { change -> nav = change(nav) }, onPlay = { _, _, _ -> })
            }
        }
        audit("featured")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "it-w1280dp-h720dp")
class LayoutAuditItalianTest : LayoutAuditBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1280dp-h720dp")
class LayoutAuditEnglishTest : LayoutAuditBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-w1280dp-h720dp")
class LayoutAuditFrenchTest : LayoutAuditBase()
