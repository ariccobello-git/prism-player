package dev.red74.prismplayer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The programme guide: the blocks line up with the time axis, OK plays what is on air and sets a reminder on the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class GuideGridTest {
    @get:Rule
    val rule = createComposeRule()

    private val min = 60_000L

    @Test
    fun cellsFillTheWholeWindowWithGapsAndCuts() {
        val start = 0L
        val end = GuideLayout.WINDOW_MS
        val a = Programme("A", -30 * min, 60 * min, null)          // already running at the left edge: cut there
        val b = Programme("B", 90 * min, 150 * min, null)          // after a gap of 30 minutes
        val c = Programme("C", 340 * min, 400 * min, null)         // runs past the right edge: cut there
        val cells = GuideLayout.cells(listOf(c, a, b), start, end)
        assertEquals(listOf("A", null, "B", null, "C"), cells.map { it.programme?.title })
        assertEquals(start, cells.first().startMs)
        assertEquals(end, cells.last().endMs)
        for (i in 1 until cells.size) assertEquals(cells[i - 1].endMs, cells[i].startMs)
        // 60 minutes at 3.5 dp per minute
        assertEquals(210, cells[0].widthDp)
        assertEquals(60 * 7 / 2, GuideLayout.cells(listOf(Programme("X", 0, 60 * min, null)), 0, 60 * min).first().widthDp)
    }

    @Test
    fun aChannelWithoutGuideIsOneGapAndNonsenseProgrammesAreDropped() {
        val cells = GuideLayout.cells(listOf(Programme("Z", 100, 100, null)), 0, GuideLayout.WINDOW_MS)
        assertEquals(1, cells.size)
        assertNull(cells.first().programme)
        assertTrue(GuideLayout.cells(emptyList(), 0, 0).isEmpty())
    }

    @Test
    fun marksAreEveryHalfHour() {
        assertEquals(12, GuideLayout.marks(0, GuideLayout.WINDOW_MS).size)
    }

    @Test
    fun windowStartIsTheHalfHourJustBegun() {
        assertEquals(1_800_000L * 5, GuideLayout.windowStart(1_800_000L * 5 + 1234))
    }

    @Test
    fun okOnWhatIsOnAirPlaysItAndOnALaterProgrammeAsksForAReminder() {
        val now = System.currentTimeMillis()
        val channel = Channel("Rai Uno", "http://x/1", "Italia", tvgId = "rai1")
        val epg = EpgData(
            mapOf("rai1" to listOf(
                Programme("Telegiornale", now - 20 * min, now + 40 * min, null),
                Programme("Film serale", now + 40 * min, now + 160 * min, null),
            )),
            emptyMap(),
        )
        var played = -1
        var reminded: String? = null
        rule.setContent {
            GuideGrid(
                channels = listOf(channel), epg = epg,
                onPlay = { played = it },
                onReminder = { _, p -> reminded = p.title; true },
            )
        }
        rule.onNodeWithText("Rai Uno").assertIsDisplayed()
        rule.onNodeWithText("Telegiornale").performClick()
        rule.waitForIdle()
        assertEquals(0, played)
        rule.onNodeWithText("Film serale").performClick()
        rule.waitForIdle()
        assertEquals("Film serale", reminded)
    }
}
