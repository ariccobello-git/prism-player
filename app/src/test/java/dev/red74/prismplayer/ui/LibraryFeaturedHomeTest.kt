package dev.red74.prismplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
    private val catalog: Catalog = CatalogBuilder.build(
        listOf(
            sky,
            Channel("Film di prova", "http://x/m/1.mp4", "Cinema", type = dev.red74.prismplayer.data.ContentType.MOVIE),
            Channel("Serie di prova S1E1", "http://x/s/1.mp4", "Serie TV", type = dev.red74.prismplayer.data.ContentType.SERIES, seriesTitle = "Serie di prova", season = 1, episode = 1),
        ),
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
        // The window's own view is drawn into a bitmap (captureToImage waits for an idle that the animated
        // background never gives).
        var bitmap: android.graphics.Bitmap? = null
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val w = view.width.coerceAtLeast(1)
            val h = view.height.coerceAtLeast(1)
            bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val picture = bitmap!!
        val colours = HashSet<Int>()
        for (x in 0 until picture.width step 24) for (y in 0 until picture.height step 24) colours += picture.getPixel(x, y)
        assertTrue("page looks empty: ${colours.size} colours", colours.size > 12)
        val out = File("build/test-results/testDebugUnitTest/library/featured.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 1000)
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
