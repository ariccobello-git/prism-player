package dev.red74.prismplayer.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.data.EpgHit
import dev.red74.prismplayer.data.Programme
import dev.red74.prismplayer.data.SearchAllResult
import dev.red74.prismplayer.data.Show
import dev.red74.prismplayer.reminders.ReminderStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one search page: results of the four kinds, the cursor, and what OK does on each. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class SearchAllScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val sky = Channel("Sky Sport Uno", "http://h/live/u/p/1.ts", "Sport")
    private val film = Channel("Il Gladiatore", "http://h/movie/u/p/2.mp4", "Film", type = ContentType.MOVIE)
    private val show = Show("Gomorra", null, "Serie", emptyList<Channel>())
    private val now = System.currentTimeMillis()
    private val later = EpgHit(Programme("Roma - Lazio", now + 3_600_000L, now + 9_000_000L, null), listOf(sky))
    private val onAir = EpgHit(Programme("Roma - Inter", now - 600_000L, now + 3_000_000L, null), listOf(sky))

    private val everything = SearchAllResult(listOf(sky), listOf(film), listOf(show), listOf(onAir, later))

    private fun focusedTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "?" }

    private class Taps {
        var played: Pair<List<Channel>, Int>? = null
        var movie: Channel? = null
        var show: Show? = null
    }

    private fun show(page: SearchPage, taps: Taps, result: SearchAllResult = everything) {
        rule.setContent {
            SearchAllScreen(
                page = page,
                search = { result },
                dataKey = "k",
                onPlay = { items, index -> taps.played = items to index },
                onOpenMovie = { taps.movie = it },
                onOpenShow = { taps.show = it },
                onBack = {},
            )
        }
        settle()
    }

    /** The search starts after a pause (a delay in the page): the test clock is moved past it. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    @Test
    fun aSearchShowsEveryKindWithItsTitle() {
        val page = SearchPage().apply { query = "ro" }
        show(page, Taps())
        rule.waitForIdle()
        for (text in listOf("Channels", "Programmes", "Films", "Series", "Sky Sport Uno", "Il Gladiatore", "Gomorra", "Roma - Lazio", "Roma - Inter")) {
            rule.onNodeWithText(text, substring = false).assertExists()
        }
    }

    @Test
    fun nothingTypedShowsNoResultsAndNoNote() {
        show(SearchPage(), Taps())
        assertEquals(0, rule.onAllNodesWithText("No results.").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Channels").fetchSemanticsNodes().size)
    }

    @Test
    fun aSearchWithNothingFoundSaysSo() {
        show(SearchPage().apply { query = "zzzz" }, Taps(), SearchAllResult.Empty)
        rule.onNodeWithText("No results.").assertIsDisplayed()
    }

    @Test
    fun okOnAChannelPlaysItAndOnAFilmOrASeriesOpensItsPage() {
        val taps = Taps()
        show(SearchPage().apply { query = "ro" }, taps)
        rule.onNodeWithText("Sky Sport Uno").performScrollTo().performClick()
        assertEquals(0, taps.played?.second)
        rule.onNodeWithText("Il Gladiatore").performScrollTo().performClick()
        assertEquals(film, taps.movie)
        rule.onNodeWithText("Gomorra").performScrollTo().performClick()
        assertEquals("Gomorra", taps.show?.title)
    }

    @Test
    fun okOnAProgrammeOnAirPlaysItsChannelAndOnALaterOneSetsAReminder() {
        val taps = Taps()
        ReminderStore.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        show(SearchPage().apply { query = "ro" }, taps)
        rule.onNodeWithText("Roma - Inter").performScrollTo().performClick()
        assertEquals(listOf(sky), taps.played?.first)
        rule.onNodeWithText("Roma - Lazio").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("I will remind you 5 minutes before it starts").assertIsDisplayed()
        assertTrue(ReminderStore.current.any { it.title == "Roma - Lazio" })
        // a second OK takes it away
        rule.onNodeWithText("Roma - Lazio").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Reminder removed").assertIsDisplayed()
        assertTrue(ReminderStore.current.none { it.title == "Roma - Lazio" })
    }

    @Test
    fun theCursorStartsOnTheFieldAndDownMovesToTheFirstResult() {
        show(SearchPage().apply { query = "ro" }, Taps())
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        val focused = focusedTexts().joinToString()
        assertTrue("Down should leave the field and reach a result or the button row, the cursor is on: $focused", focused.isNotBlank())
    }

    @Test
    fun theSameQueryForTheSameDataIsNotSearchedAgainWhenTheScreenComesBack() {
        var calls = 0
        val page = SearchPage().apply { query = "ro" }
        var visible by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            if (visible) SearchAllScreen(page, { calls++; everything }, "k", { _, _ -> }, {}, {}, {})
        }
        settle()
        visible = false
        rule.waitForIdle()
        visible = true
        settle()
        assertEquals("the second visit must reuse the result", 1, calls)
    }
}
