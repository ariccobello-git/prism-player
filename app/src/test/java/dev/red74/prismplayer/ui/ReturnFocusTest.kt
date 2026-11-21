package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cursor returns to the item it left when it comes back into a section (seasons <-> episodes). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReturnFocusTest {
    @get:Rule
    val rule = createComposeRule()

    private val seasonRequesters = List(3) { FocusRequester() }

    /** A series page like the real one: three seasons in a row, ten episodes under them. */
    private fun setUpPage() {
        val seasons = ReturnFocus(initial = 1)
        val episodes = ReturnFocus()
        rule.setContent {
            Column(Modifier.width(900.dp).height(500.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(Modifier.returnSection(seasons), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(List(3) { it }) { index, _ ->
                        GlassButton(
                            text = "Stagione ${index + 1}",
                            onClick = {},
                            modifier = Modifier.testTag("s$index").returnItem(seasons, index),
                            focusRequester = seasonRequesters[index],
                        )
                    }
                }
                LazyColumn(Modifier.returnSection(episodes), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(List(10) { it }) { index, _ ->
                        GlassSurface(
                            onClick = {},
                            modifier = Modifier.fillMaxWidth().testTag("e$index").returnItem(episodes, index),
                        ) { BasicText("Episodio $index") }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test
    fun upFromTheFirstEpisodeGoesBackToSeasonTwo() {
        setUpPage()
        rule.runOnUiThread { seasonRequesters[1].requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("s1").assertIsFocused()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        rule.onNodeWithTag("s1").assertIsFocused()
    }

    @Test
    fun seasonThreeIsRememberedToo() {
        setUpPage()
        rule.runOnUiThread { seasonRequesters[2].requestFocus() }
        rule.waitForIdle()
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        rule.onNodeWithTag("s2").assertIsFocused()
    }

    @Test
    fun targetIsTheLastItemOrTheNearestOneStillThere() {
        val memory = ReturnFocus(initial = 5)
        assertNull(memory.targetId())
        listOf(2, 3, 4, 8, 9).forEach { memory.attach(it) }
        // 5 is gone: 4 is nearer than 8.
        assertEquals(4, memory.targetId())
        memory.attach(5)
        assertEquals(5, memory.targetId())
        memory.detach(5)
        memory.last = 7
        // 7 sits between 4 and 8 and is nearer to 8.
        assertEquals(8, memory.targetId())
        memory.last = 0
        assertEquals(2, memory.targetId())
        memory.last = 99
        assertEquals(9, memory.targetId())
    }

    @Test
    fun nothingKnownMeansTheNaturalChoice() {
        assertNull(ReturnFocus().targetId())
        val memory = ReturnFocus(initial = 1)
        assertNull(memory.targetId())
        memory.attach(1)
        memory.attach(1)
        memory.detach(1)
        assertEquals(1, memory.targetId())
        memory.detach(1)
        assertNull(memory.targetId())
    }

    @Test
    fun onlyItemsOnScreenCount() {
        val onScreen = setOf(10, 11, 12)
        val memory = ReturnFocus(initial = 4, visible = { it in onScreen })
        listOf(3, 4, 5, 10, 11, 12).forEach { memory.attach(it) }
        assertEquals(10, memory.targetId())
    }
}
