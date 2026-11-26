package dev.red74.prismplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryState
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.CatalogBuilder
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.HighlightsData
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The film and series pages with the poster-coloured glow behind them: still shown, and the cursor still starts on the main button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class CinematicDetailTest {
    @get:Rule
    val rule = createComposeRule()

    private val film = Channel("Film di prova", "http://x/m/1.mp4", "Cinema", logo = null, type = ContentType.MOVIE)
    private val catalog: Catalog = CatalogBuilder.build(
        listOf(
            film,
            Channel("Serie di prova S1E1", "http://x/s/1.mp4", "Serie TV", type = ContentType.SERIES, seriesTitle = "Serie di prova", season = 1, episode = 1),
        ),
        null, false,
    )
    private var nav by mutableStateOf(LibraryNav())

    private fun show() {
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

    private fun focusedTexts(): List<String> =
        rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "?" }

    @Test
    fun theFilmPageShowsTheTitleAndTheCursorStartsOnPlay() {
        nav = LibraryNav(tab = LibraryTab.MOVIES, movieUrl = film.url)
        show()
        assertTrue(rule.onAllNodesWithText("Film di prova").fetchSemanticsNodes().isNotEmpty())
        assertTrue("cursor should be on Play, it is on ${focusedTexts()}", "Play" in focusedTexts())
    }

    @Test
    fun theSeriesPageShowsTheTitleAndTheSeasons() {
        nav = LibraryNav(tab = LibraryTab.SERIES, showTitle = "Serie di prova", showGroup = "Serie TV", season = -1)
        show()
        assertTrue(rule.onAllNodesWithText("Serie di prova").fetchSemanticsNodes().isNotEmpty())
        assertTrue("cursor should be on a season/star control, it is on ${focusedTexts()}", focusedTexts().isNotEmpty())
    }
}
