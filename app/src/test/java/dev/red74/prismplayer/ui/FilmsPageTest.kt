package dev.red74.prismplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ApplicationProvider
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.Category
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.data.HighlightsData
import dev.red74.prismplayer.data.SeriesCategory
import dev.red74.prismplayer.data.Show
import dev.red74.prismplayer.data.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Films page: a large card on top, "Continue watching" for films left half way, and Down goes to the FIRST card below. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class FilmsPageTest {
    @get:Rule
    val rule = createComposeRule()

    private fun film(n: Int) = Channel("Film $n", "http://x/film$n", "Netflix", type = ContentType.MOVIE)
    private val films = (1..8).map(::film)

    private class FakeCatalog(private val films: List<Channel>) :
        Catalog(emptyList(), listOf(Category("Netflix", films)), emptyList(), null, false, films.size) {
        override fun recentMovies(sinceMs: Long, limit: Int): List<Channel> = films
    }

    private var nav by mutableStateOf(LibraryNav(tab = LibraryTab.HIGHLIGHTS, highlightKind = 0, resume = true))

    private fun show() {
        WatchProgress.init(ApplicationProvider.getApplicationContext())
        rule.setContent {
            CompositionLocalProvider(LocalHighlights provides HighlightsData.Empty) {
                HighlightsView(nav = nav, onNav = { change -> nav = change(nav) }, onPlay = { _, _, _ -> }, catalog = FakeCatalog(films))
            }
        }
    }

    @Test
    fun theLargeCardShowsTheFirstFilmAndTheContinueRowTheFilmLeftHalfWay() {
        WatchProgress.init(ApplicationProvider.getApplicationContext())
        WatchProgress.save(films[5].url, 1_800_000L, 6_000_000L)
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCountSafe("Continue watching") == 1 }
        rule.onNodeWithText("Continue watching").assertIsDisplayed()
        rule.onNodeWithText("Film 1").assertIsDisplayed()
        rule.onNodeWithText("Film 6").assertIsDisplayed()
        WatchProgress.clear(films[5].url)
    }

    @Test
    fun theFilmsPageSaysWhatItIsAtAGlance() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("NEW FILM").assertIsDisplayed()
        rule.onNodeWithText("New films · 8").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCountSafe("New series · 8"))
        // every card of the grid carries the FILM label
        assertEquals(true, rule.onAllNodesWithTextCountSafe("FILM") >= 1)
    }

    @Test
    fun downFromTheLargeCardGoesToTheFirstCardOfTheRowBelow() {
        WatchProgress.init(ApplicationProvider.getApplicationContext())
        WatchProgress.save(films[5].url, 1_800_000L, 6_000_000L)
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCountSafe("Continue watching") == 1 }
        rule.onNodeWithText("Open").assertIsFocused()
        rule.onNodeWithText("Open").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        // the film left half way is the first of the row, and it has the cursor
        rule.onNodeWithText("Film 6").assertIsFocused()
        WatchProgress.clear(films[5].url)
    }

    @Test
    fun withoutAnyProgressDownGoesToTheFirstCardOfTheGrid() {
        show()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTextCountSafe("Continue watching"))
        rule.onNodeWithText("Open").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText("Film 2").assertIsFocused()
    }

    private fun episode(show: Int, n: Int) = Channel(
        "Serie $show S1E$n", "http://x/s$show/$n.mp4", "Serie TV", type = ContentType.SERIES, seriesTitle = "Serie $show", season = 1, episode = n,
    )
    private val shows = (1..4).map { Show("Serie $it", null, "Serie TV", listOf(episode(it, 1), episode(it, 2), episode(it, 3))) }

    private class FakeSeries(private val shows: List<Show>) :
        Catalog(emptyList(), emptyList(), listOf(SeriesCategory("Serie TV", shows)), null, false, shows.size) {
        override fun recentShows(sinceMs: Long, limit: Int): List<Show> = shows
    }

    @Test
    fun theSeriesPageContinuesTheLatestEpisodeOfEachSeriesLeftHalfWay() {
        WatchProgress.init(ApplicationProvider.getApplicationContext())
        WatchProgress.save(shows[2].episodes[1].url, 600_000L, 2_400_000L)
        rule.setContent {
            CompositionLocalProvider(LocalHighlights provides HighlightsData.Empty) {
                HighlightsView(nav = nav, onNav = { change -> nav = change(nav) }, onPlay = { _, _, _ -> }, catalog = FakeSeries(shows))
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCountSafe("Continue watching") == 1 }
        rule.onNodeWithText("Serie 3").assertIsDisplayed()
        rule.onNodeWithText("S1 E2").assertIsDisplayed()
        // the series with the cursor memory is shown once: not repeated in the grid
        assertEquals(1, rule.onAllNodesWithTextCountSafe("Serie 3"))
        WatchProgress.clear(shows[2].episodes[1].url)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCountSafe(text: String): Int =
    onAllNodesWithText(text).fetchSemanticsNodes().size
