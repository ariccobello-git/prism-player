package dev.red74.prismplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryState
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.CatalogBuilder
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.HighlightEvent
import dev.red74.prismplayer.data.HighlightKind
import dev.red74.prismplayer.data.HighlightsData
import dev.red74.prismplayer.data.SportPrefs
import dev.red74.prismplayer.data.SportPrefsStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The featured page is what a list opens on, right under the tab row (Live, Movies, Series, Favorites), which no
 * longer has an "In evidenza" tab. Going back up from it lands on the tab the cursor came from.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-xhdpi")
class LibraryFeaturedHomeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val sky = Channel("Sky Sport Uno", "http://x/1", "Sport", tvgId = "s1")
    private val soon = System.currentTimeMillis() + 3_600_000L
    private val sport = listOf(
        HighlightEvent(HighlightKind.SPORT, "Roma - Lazio", null, soon, soon + 7_200_000L, 50, listOf(sky)),
        HighlightEvent(HighlightKind.SPORT, "Inter - Juventus", null, soon + 600_000L, soon + 7_800_000L, 40, listOf(sky)),
    )
    private val data = HighlightsData(mapOf(HighlightKind.SPORT to sport))
    private val catalog: Catalog = CatalogBuilder.build(listOf(sky), null, false)

    private var nav by mutableStateOf(LibraryNav())

    @Before
    fun reset() {
        SportPrefsStore.update { SportPrefs() }
    }

    private fun show() {
        rule.setContent {
            LibraryScreen(
                state = LibraryState(playlistId = "p", playlistName = "Lista", loading = false, catalog = catalog),
                nav = nav,
                epg = EpgData.Empty,
                favorites = emptySet(),
                onNav = { change -> nav = change(nav) },
                onPlay = { _, _, _ -> },
                onToggleFavorite = {},
                onRetry = {},
                onBack = {},
                highlights = data,
            )
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun focus(label: String) {
        rule.onNodeWithText(label).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
    }

    private fun isFocused(label: String): Boolean =
        rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test
    fun aListOpensOnTheFeaturedPageWithNoFeaturedTab() {
        assertEquals(LibraryTab.HIGHLIGHTS, LibraryNav().tab)
        show()
        rule.onNodeWithText("Roma - Lazio").assertIsDisplayed()
        for (tab in listOf("Live", "Movies", "Series", "Favorites")) rule.onNodeWithText(tab).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Featured").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("In evidenza").fetchSemanticsNodes().size)
        // the page sits under the tab row
        val tabs = rule.onNodeWithText("Series").getBoundsInRoot()
        val card = rule.onNodeWithText("Roma - Lazio").getBoundsInRoot()
        assertTrue("tabs ${tabs.bottom} card ${card.top}", tabs.bottom <= card.top)
    }

    @Test
    fun theFeaturedPageIsDrawnAndSavedAsAPicture() {
        show()
        val image = rule.onRoot().captureToImage()
        val bitmap = image.asAndroidBitmap()
        val colours = HashSet<Int>()
        for (x in 0 until bitmap.width step 24) for (y in 0 until bitmap.height step 24) colours += bitmap.getPixel(x, y)
        assertTrue("page looks empty: ${colours.size} colours", colours.size > 12)
        val out = File("build/test-results/testDebugUnitTest/library/featured.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 1000)
    }

    @Test
    fun upFromTheFeaturedPageReturnsToTheTabTheCursorCameFrom() {
        show()
        for (tab in listOf("Series", "Movies", "Live")) {
            focus(tab)
            rule.onNodeWithText(tab).assertIsFocused()
            press(Key.DirectionDown)
            // down from a tab lands inside the page, not on a tab
            assertTrue("$tab still focused after Down", !isFocused(tab))
            press(Key.DirectionUp)
            rule.onNodeWithText(tab).assertIsFocused()
        }
    }

    @Test
    fun backFromTheTabRowOfAnotherTabReturnsToTheFeaturedPage() {
        nav = LibraryNav(tab = LibraryTab.MOVIES)
        show()
        focus("Movies")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(LibraryTab.HIGHLIGHTS, nav.tab)
        rule.onNodeWithText("Roma - Lazio").assertIsDisplayed()
    }
}
