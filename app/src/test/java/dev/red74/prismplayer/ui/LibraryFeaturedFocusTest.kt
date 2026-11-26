package dev.red74.prismplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryState
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.CatalogBuilder
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.HighlightEvent
import dev.red74.prismplayer.data.HighlightKind
import dev.red74.prismplayer.data.HighlightsData
import dev.red74.prismplayer.data.SportPrefs
import dev.red74.prismplayer.data.SportPrefsStore
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cursor on the tab row and the featured page under it: going up from the page lands on the tab it came from. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class LibraryFeaturedFocusTest {
    @get:Rule
    val rule = createComposeRule()

    private val sky = Channel("Sky Sport Uno", "http://x/1", "Sport", tvgId = "s1")
    private val soon = System.currentTimeMillis() + 3_600_000L
    private val data = HighlightsData(
        mapOf(HighlightKind.SPORT to listOf(HighlightEvent(HighlightKind.SPORT, "Roma - Lazio", null, soon, soon + 7_200_000L, 50, listOf(sky)))),
    )
    private val catalog: Catalog = CatalogBuilder.build(
        listOf(
            sky,
            Channel("Film di prova", "http://x/m/1.mp4", "Cinema", type = dev.red74.prismplayer.data.ContentType.MOVIE),
            Channel("Serie di prova S1E1", "http://x/s/1.mp4", "Serie TV", type = dev.red74.prismplayer.data.ContentType.SERIES, seriesTitle = "Serie di prova", season = 1, episode = 1),
        // a real mixed list has plenty of live channels (a list with only a handful is an on-demand list: no Live button)
        ) + (1..320).map { Channel("Canale $it", "http://x/live/$it", "Italia", tvgId = "c$it") },
        null, false,
    )
    private var nav by mutableStateOf(LibraryNav())

    @Before
    fun reset() {
        SportPrefsStore.update { SportPrefs() }
    }

    private fun show() {
        rule.setContent {
            LibraryScreen(
                state = LibraryState(playlistId = "p", playlistName = "Lista", loading = false, catalog = catalog),
                nav = nav, epg = EpgData.Empty, favorites = emptySet(),
                onNav = { change -> nav = change(nav) },
                onPlay = { _, _, _ -> }, onToggleFavorite = {}, onRetry = {}, onBack = {}, highlights = data,
            )
        }
        rule.waitForIdle()
    }

    private fun focusedTexts(): List<String> =
        rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "?" }

    private fun isFocused(label: String) = label in focusedTexts()

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun theCursorStartsOnATabWhenTheListOpens() {
        show()
        assertTrue("cursor should be on a tab, it is on ${focusedTexts()}", focusedTexts().any { it in setOf("Live", "Movies", "Series", "Favorites") })
    }

    @Test
    fun upFromTheFeaturedPageReturnsToTheTabTheCursorCameFrom() {
        show()
        for (tab in listOf("Series", "Movies", "Live")) {
            rule.onNodeWithText(tab).performSemanticsAction(SemanticsActions.RequestFocus)
            rule.waitForIdle()
            assertTrue("RequestFocus should put the cursor on $tab, it is on ${focusedTexts()}", isFocused(tab))
            press(Key.DirectionDown)
            assertTrue("$tab still focused after Down", !isFocused(tab))
            press(Key.DirectionUp)
            assertTrue("Up from the page should return to $tab, the cursor is on ${focusedTexts()}", isFocused(tab))
        }
    }
}
