package dev.red74.prismplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.HighlightEvent
import dev.red74.prismplayer.data.HighlightKind
import dev.red74.prismplayer.data.HighlightRow
import dev.red74.prismplayer.data.HighlightsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The featured events page: cards show, OK plays the channel, and the cursor returns to the card just watched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class HighlightsViewTest {
    @get:Rule
    val rule = createComposeRule()

    private val TOLERANCE = 8f

    private val sky = Channel("Sky Sport Uno", "http://x/1", "Sport", tvgId = "s1")
    private val dazn = Channel("DAZN 1", "http://x/2", "Sport", tvgId = "d1")
    private val cinema = Channel("Sky Cinema Uno", "http://x/3", "Cinema", tvgId = "f1")

    private val soon = System.currentTimeMillis() + 3_600_000L
    private val sport = listOf(
        HighlightEvent(HighlightKind.SPORT, "Roma - Lazio", null, soon, soon + 7_200_000L, 50, listOf(sky)),
        HighlightEvent(HighlightKind.SPORT, "Inter - Juventus", null, soon + 600_000L, soon + 7_800_000L, 40, listOf(dazn)),
    )
    private val film = listOf(HighlightEvent(HighlightKind.FILM, "Heat", null, soon, soon + 7_000_000L, 40, listOf(cinema)))
    private val data = HighlightsData(mapOf(HighlightKind.SPORT to sport, HighlightKind.FILM to film))

    private var nav by mutableStateOf(LibraryNav(tab = LibraryTab.HIGHLIGHTS))
    private var played: List<Channel>? = null

    private fun show(data: HighlightsData = this.data) {
        rule.setContent {
            CompositionLocalProvider(LocalHighlights provides data) {
                HighlightsView(nav = nav, onNav = { change -> nav = change(nav) }, onPlay = { items, _, _ -> played = items })
            }
        }
    }

    @Test
    fun showsOnlyTheKindsThatHaveEventsAndTheirCards() {
        show()
        rule.onNodeWithText("Sport").assertIsDisplayed()
        rule.onNodeWithText("Films").assertIsDisplayed()
        rule.onNodeWithText("Roma - Lazio").assertIsDisplayed()
        rule.onNodeWithText("Inter - Juventus").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount("Intrattenimento") == 0 && rule.onAllNodesWithTextCount("Entertainment") == 0)
    }

    @Test
    fun okOnACardPlaysItsChannelAndAsksToComeBackToIt() {
        show()
        rule.onNodeWithText("Roma - Lazio").performClick()
        rule.waitForIdle()
        assertEquals(listOf(sky), played)
        // The test keeps the page on screen, so it takes the cursor back at once, as it does when coming back from the player.
        rule.onNodeWithText("Roma - Lazio").assertIsFocused()
    }

    @Test
    fun theKindIsRememberedInTheNavigation() {
        nav = nav.copy(highlightKind = 1)
        show()
        rule.onNodeWithText("Heat").assertIsDisplayed()
    }

    @Test
    fun anOutOfRangeKindIsClamped() {
        nav = nav.copy(highlightKind = 9)
        show()
        rule.onNodeWithText("Heat").assertIsDisplayed()
    }

    @Test
    fun comingBackPutsTheCursorOnTheCardWatchedAndNothingMoves() {
        show()
        // The focused card is drawn 4% larger around its centre, which moves the title by a few dp inside it; a real
        // jump (a scrolled list, a moved row) is tens of dp. Compare with a tolerance of 8 dp.
        fun centre(title: String): Pair<Float, Float> =
            rule.onNodeWithText(title).getBoundsInRoot().let { (it.left.value + it.right.value) / 2 to (it.top.value + it.bottom.value) / 2 }
        val first = centre("Roma - Lazio")
        val second = centre("Inter - Juventus")
        for (url in listOf("http://x/2", "http://x/1", "http://x/2")) {
            nav = nav.copy(played = url, resume = true)
            rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
            rule.onNodeWithText(if (url.endsWith("2")) "Inter - Juventus" else "Roma - Lazio").assertIsFocused()
            assertEquals("card moved", first.first, centre("Roma - Lazio").first, TOLERANCE)
            assertEquals("card moved", first.second, centre("Roma - Lazio").second, TOLERANCE)
            assertEquals("card moved", second.first, centre("Inter - Juventus").first, TOLERANCE)
            assertEquals("card moved", second.second, centre("Inter - Juventus").second, TOLERANCE)
        }
    }

    @Test
    fun emptyDataShowsAnExplanationAndNoCards() {
        show(HighlightsData.Empty)
        assertEquals(0, rule.onAllNodesWithTextCount("Sport"))
        assertEquals(1, rule.onAllNodesWithTextCount("No featured events for now. The guide may still be loading, or your list has no guide for the next 36 hours."))
    }

    @Test
    fun locateFindsTheCardOrFallsBackToTheFirst() {
        val rows = listOf(HighlightRow(true, 0, sport.take(1)), HighlightRow(false, 0, sport.drop(1) + film))
        assertEquals(0 to 0, locate(rows, null))
        assertEquals(1 to 1, locate(rows, "http://x/3"))
        assertEquals(0 to 0, locate(rows, "http://nowhere"))
        assertEquals(0 to 0, locate(emptyList(), "http://x/1"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
