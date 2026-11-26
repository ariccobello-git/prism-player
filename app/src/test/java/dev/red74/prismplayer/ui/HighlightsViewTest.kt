package dev.red74.prismplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.HighlightEvent
import dev.red74.prismplayer.data.HighlightKind
import dev.red74.prismplayer.data.HighlightRow
import dev.red74.prismplayer.data.HighlightsData
import dev.red74.prismplayer.data.SportPrefs
import dev.red74.prismplayer.data.SportPrefsStore
import dev.red74.prismplayer.data.SportTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The featured events page: cards show, OK plays the channel, and the cursor returns to the card just watched. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
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
    // The first card is on air (OK plays it); the others have not started (OK sets a reminder).
    private val onAirStart = System.currentTimeMillis() - 600_000L
    private val sport = listOf(
        HighlightEvent(HighlightKind.SPORT, "Roma - Lazio", null, onAirStart, onAirStart + 7_200_000L, 50, listOf(sky)),
        HighlightEvent(HighlightKind.SPORT, "Inter - Juventus", null, soon + 600_000L, soon + 7_800_000L, 40, listOf(dazn)),
    )
    private val film = listOf(HighlightEvent(HighlightKind.FILM, "Heat", null, soon, soon + 7_000_000L, 40, listOf(cinema)))
    private val data = HighlightsData(mapOf(HighlightKind.SPORT to sport, HighlightKind.FILM to film))

    @Before
    fun resetChoices() {
        SportPrefsStore.update { SportPrefs() }
    }

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
    fun whileTheGuideLoadsPlaceholdersShowInsteadOfAnEmptyMessage() {
        rule.setContent {
            CompositionLocalProvider(LocalHighlights provides HighlightsData.Empty, LocalHighlightsLoading provides true) {
                HighlightsView(nav = nav, onNav = { change -> nav = change(nav) }, onPlay = { _, _, _ -> })
            }
        }
        rule.onNodeWithTag("skeleton").assertIsDisplayed()
        rule.onNodeWithText("Looking for the featured events…").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("No featured events for now. The guide may still be loading, or your list has no guide for the next 36 hours."))
    }

    @Test
    fun followTeamsIsOnlyForLiveNotForAnOnDemandOnlyList() {
        val movie = Channel("Heat", "http://p/movie/u/p/1.mkv", "Cinema", type = dev.red74.prismplayer.data.ContentType.MOVIE)
        val onDemand = dev.red74.prismplayer.data.Catalog(
            emptyList(), listOf(dev.red74.prismplayer.data.Category("Cinema", listOf(movie))), emptyList(), null, false, 1,
        )
        rule.setContent {
            CompositionLocalProvider(LocalHighlights provides HighlightsData.Empty) {
                HighlightsView(nav = nav, onNav = { change -> nav = change(nav) }, onPlay = { _, _, _ -> }, catalog = onDemand)
            }
        }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTextCount("Follow teams"))
    }

    @Test
    fun followTeamsStaysForALiveList() {
        show()
        assertEquals(1, rule.onAllNodesWithTextCount("Follow teams"))
    }

    @Test
    fun customizeOpensTheEditorAndSavesTheChoice() {
        show()
        rule.onNodeWithText("Follow teams").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Done").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Roma - Lazio"))
        rule.onNodeWithText("Football").performClick()
        rule.waitForIdle()
        assertTrue(SportTopic.FOOTBALL in SportPrefsStore.current.topics)
        rule.onNodeWithText("Football").performClick()
        rule.waitForIdle()
        assertTrue(SportPrefsStore.current.topics.isEmpty())
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Roma - Lazio").assertIsDisplayed()
    }

    @Test
    fun aFollowedTeamGetsAForYouRowAtTheTop() {
        SportPrefsStore.update { it.withKeyword("Inter") }
        show()
        // the large card is what is on air (Roma - Lazio); the followed match has its own "For you" row
        rule.onNodeWithText("For you").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount("Inter - Juventus") >= 1)
        assertEquals(1, rule.onAllNodesWithTextCount("Roma - Lazio"))
        rule.onNodeWithTag("hero").assertTextContains("Roma - Lazio", substring = true)
    }

    @Test
    fun theFirstEventIsAHeroCardMuchLargerThanTheOthers() {
        show()
        val hero = rule.onNodeWithTag("hero").getBoundsInRoot()
        assertTrue("hero width ${hero.right - hero.left}", (hero.right - hero.left).value > 700f)
        assertTrue("hero height ${hero.bottom - hero.top}", (hero.bottom - hero.top).value >= 200f)
        rule.onNodeWithText("Roma - Lazio").assertIsDisplayed()
        assertEquals("the hero event is not repeated in a row", 1, rule.onAllNodesWithTextCount("Roma - Lazio"))
    }

    @Test
    fun cardsUseDrawnSymbolsNotEmojis() {
        show()
        rule.onNodeWithText("Remind me").assertIsDisplayed()
        for (emoji in listOf("🔔", "▶", "★", "✓", "🔍")) assertEquals("emoji $emoji on screen", 0, rule.onAllNodesWithTextCount(emoji))
    }

    @Test
    fun followingATeamFiltersTheForYouRowToThatTeamOnly() {
        show()
        rule.onNodeWithText("Follow teams").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Inter").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("inter"), SportPrefsStore.current.keywords)
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("For you").assertIsDisplayed()
        rule.onNodeWithTag("hero").assertIsDisplayed()
        // following it again (from the editor) stops following
        rule.onNodeWithText("Follow teams").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Inter").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(SportPrefsStore.current.keywords.isEmpty())
    }

    @Test
    fun noChoicesNoForYouRow() {
        show()
        assertEquals(0, rule.onAllNodesWithTextCount("For you"))
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

    @Test
    fun theMenuIsOnTheLeftAndTheContentOnTheRight() {
        show()
        val menu = rule.onNodeWithText("Sport").getBoundsInRoot()
        val hero = rule.onNodeWithTag("hero").getBoundsInRoot()
        assertTrue("menu ${menu.right} must end before the hero ${hero.left}", menu.right <= hero.left)
        assertTrue("menu width ${menu.right - menu.left}", (menu.right - menu.left).value in 150f..260f)
        rule.onNodeWithText("Follow teams").assertIsDisplayed()
    }

    @Test
    fun theCardsOfARowHaveTheSizeOfTheDesign() {
        show()
        val card = rule.onNodeWithText("Inter - Juventus").getBoundsInRoot()
        // the title sits inside a card 240 x 138 dp, so it is narrower than the card and well inside it
        assertTrue("card title width ${card.right - card.left}", (card.right - card.left).value in 80f..240f)
        val hero = rule.onNodeWithTag("hero").getBoundsInRoot()
        assertTrue("hero must be wider than four cards", (hero.right - hero.left).value > 4 * 200f)
    }

    @Test
    fun aMenuEntryChangesThePageAndTheMenuStays() {
        show()
        rule.onNodeWithText("Films").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Heat").assertIsDisplayed()
        rule.onNodeWithText("Sport").assertIsDisplayed()
        assertEquals(1, nav.highlightKind)
    }

    @Test
    fun leftFromTheContentLandsOnThePageShownInTheMenu() {
        nav = nav.copy(highlightKind = 1)
        show()
        rule.onNodeWithText("Heat").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNodeWithText("Films").assertIsFocused()
    }

    @Test
    fun longTitlesGetSmallerType() {
        assertTrue(titleSp(10, large = true) > titleSp(40, large = true))
        assertTrue(titleSp(40, large = true) > titleSp(90, large = true))
        assertTrue(titleSp(10, large = false) > titleSp(60, large = false))
        // the large card never shouts: at most 30 sp, and never below 20 sp
        for (n in 0..200) assertTrue(titleSp(n, large = true) in 20..30)
        for (n in 0..200) assertTrue(titleSp(n, large = false) in 13..18)
        // with the preview taking room, the same title is never bigger
        for (n in 0..120) assertTrue(titleSp(n, large = true, narrow = true) <= titleSp(n, large = true))
    }

    @Test
    fun theLiveHeroReservesRoomForThePreviewAndStartsNothingYet() {
        show()
        rule.onNodeWithTag("heroPreview", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(dev.red74.prismplayer.player.PreviewPlayers.anyPlaying().not())
    }

    @Test
    fun aLiveCardTheCursorRestsOnTakesTheLargeCardAndTheCursorFollows() {
        val second = listOf(
            HighlightEvent(HighlightKind.SPORT, "Roma - Lazio", null, onAirStart, onAirStart + 7_200_000L, 50, listOf(sky)),
            HighlightEvent(HighlightKind.SPORT, "Inter - Juventus", null, onAirStart, onAirStart + 7_200_000L, 40, listOf(dazn)),
        )
        show(HighlightsData(mapOf(HighlightKind.SPORT to second)))
        rule.waitForIdle()
        rule.onNodeWithText("Inter - Juventus").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        // not yet: a quick pass over a card changes nothing
        rule.onNodeWithTag("hero").assertTextContains("Roma - Lazio", substring = true)
        rule.mainClock.advanceTimeBy(PREVIEW_DWELL_MS + 1000)
        rule.waitForIdle()
        rule.onNodeWithTag("hero").assertTextContains("Inter - Juventus", substring = true)
        rule.onNodeWithText("Inter - Juventus").assertIsFocused()
        // the old large card goes down into the rows
        rule.onNodeWithText("Roma - Lazio").assertIsDisplayed()
        // the cursor stays on the promoted card, and so does the preview, also some seconds later
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        rule.onNodeWithText("Inter - Juventus").assertIsFocused()
        rule.onNodeWithTag("heroPreview", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theLargeCardIsAlwaysSomethingOnAirNow() {
        val mixed = listOf(
            HighlightEvent(HighlightKind.SPORT, "Atalanta - Torino", null, soon, soon + 7_200_000L, 90, listOf(dazn)),
            HighlightEvent(HighlightKind.SPORT, "Roma - Lazio", null, onAirStart, onAirStart + 7_200_000L, 40, listOf(sky)),
        )
        show(HighlightsData(mapOf(HighlightKind.SPORT to mixed)))
        rule.waitForIdle()
        rule.onNodeWithTag("hero").assertTextContains("Roma - Lazio", substring = true)
    }

    @Test
    fun withNothingOnAirTheLargeCardIsTheFirstEvent() {
        show(HighlightsData(mapOf(HighlightKind.FILM to film)))
        nav = nav.copy(highlightKind = 0)
        rule.waitForIdle()
        rule.onNodeWithTag("hero").assertTextContains("Heat", substring = true)
    }

    /** Two long rows of cards not yet started (no preview, no promotion): to check how the page moves under the cursor. */
    private fun manyEvents(): HighlightsData {
        val today = (0 until 12).map { n ->
            HighlightEvent(HighlightKind.SPORT, "Oggi $n - Team $n", null, soon + n * 60_000L, soon + 7_200_000L + n * 60_000L, 60 - n, listOf(sky))
        }
        val tomorrow = (0 until 12).map { n ->
            HighlightEvent(HighlightKind.SPORT, "Domani $n - Club $n", null, soon + 86_400_000L + n * 60_000L, soon + 93_600_000L + n * 60_000L, 30 - n, listOf(dazn))
        }
        return HighlightsData(mapOf(HighlightKind.SPORT to today + tomorrow))
    }

    private fun left(title: String) = rule.onNodeWithText(title).getBoundsInRoot().left.value
    private fun top(title: String) = rule.onNodeWithText(title).getBoundsInRoot().top.value

    // With no event on air the first one is the large card, so the "Oggi" row starts at "Oggi 1".
    @Test
    fun movingAlongARowNeverMovesThePageUpOrDown() {
        show(manyEvents())
        rule.waitForIdle()
        rule.onNodeWithText("Oggi 1 - Team 1").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        val heroTop = rule.onNodeWithTag("hero").getBoundsInRoot().top.value
        val cardTop = top("Oggi 1 - Team 1")
        for (n in 2..8) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            rule.onNodeWithText("Oggi $n - Team $n").assertIsFocused()
            assertEquals("page moved up/down at step $n", heroTop, rule.onNodeWithTag("hero").getBoundsInRoot().top.value, 0.5f)
            assertEquals("row moved up/down at step $n", cardTop, top("Oggi $n - Team $n"), TOLERANCE)
        }
        for (n in 7 downTo 1) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            rule.onNodeWithText("Oggi $n - Team $n").assertIsFocused()
            assertEquals("page moved up/down going back at $n", heroTop, rule.onNodeWithTag("hero").getBoundsInRoot().top.value, 0.5f)
        }
    }

    @Test
    fun theCardWithTheCursorStaysInPlaceWhileTheRowSlides() {
        show(manyEvents())
        rule.waitForIdle()
        rule.onNodeWithText("Oggi 1 - Team 1").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        // From the first step on the cursor card sits at the same horizontal place, whatever the card.
        val place = left("Oggi 2 - Team 2")
        for (n in 3..7) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            rule.onNodeWithText("Oggi $n - Team $n").assertIsFocused()
            assertEquals("cursor card moved at step $n", place, left("Oggi $n - Team $n"), TOLERANCE)
        }
    }

    @Test
    fun goingDownToTheNextRowMovesThePageOnceAndAlongTheRowNoMore() {
        show(manyEvents())
        rule.waitForIdle()
        rule.onNodeWithText("Oggi 1 - Team 1").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onNodeWithText("Domani 0 - Club 0").assertIsFocused()
        val topNow = top("Domani 0 - Club 0")
        for (n in 1..4) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            rule.onNodeWithText("Domani $n - Club $n").assertIsFocused()
            assertEquals("row moved up/down at step $n", topNow, top("Domani $n - Club $n"), TOLERANCE)
        }
    }
}
