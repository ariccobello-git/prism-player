package dev.red74.prismplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryState
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.CatalogBuilder
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.HighlightsData
import dev.red74.prismplayer.data.SportPrefs
import dev.red74.prismplayer.data.SportPrefsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The choice of sports and teams to follow lives on the Favourites page: a button, the editor, and back to the button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class FavoritesTeamsTest {
    @get:Rule
    val rule = createComposeRule()

    private val sky = Channel("Sky Sport Uno", "http://x/1", "Sport", tvgId = "s1")
    private val liveCatalog: Catalog = CatalogBuilder.build(listOf(sky), null, false)
    private val movieCatalog: Catalog = CatalogBuilder.build(
        listOf(Channel("Film di prova", "http://x/m/1.mp4", "Cinema", type = ContentType.MOVIE)), null, false,
    )
    private var nav by mutableStateOf(LibraryNav(tab = LibraryTab.FAVORITES))

    @Before
    fun reset() {
        SportPrefsStore.update { SportPrefs() }
    }

    private fun show(catalog: Catalog) {
        rule.setContent {
            LibraryScreen(
                state = LibraryState(playlistId = "p", playlistName = "Lista", loading = false, catalog = catalog),
                nav = nav, epg = EpgData.Empty, favorites = emptySet(),
                onNav = { change -> nav = change(nav) },
                onPlay = { _, _, _ -> }, onToggleFavorite = {}, onRetry = {}, onBack = {}, highlights = HighlightsData.Empty,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun theButtonIsOnTheFavouritesPageOfAListWithLiveChannels() {
        show(liveCatalog)
        rule.onNodeWithText("Follow teams").assertIsDisplayed()
    }

    @Test
    fun aListWithoutLiveChannelsHasNoSuchButton() {
        show(movieCatalog)
        assertEquals(0, rule.onAllNodesWithText("Follow teams").fetchSemanticsNodes().size)
    }

    @Test
    fun theEditorOpensSavesAndTheCursorComesBackToTheButton() {
        show(liveCatalog)
        rule.onNodeWithText("Follow teams").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Done").assertIsDisplayed()
        rule.onNodeWithText("Juventus").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue("juventus" in SportPrefsStore.current.keywords)
        rule.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Follow teams").assertIsDisplayed()
        rule.onNodeWithText("Follow teams").assertIsFocused()
    }
}
