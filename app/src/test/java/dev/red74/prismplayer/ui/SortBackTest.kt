package dev.red74.prismplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back from the list (channels or contents) moves the cursor to the sort button; from the sort button it does not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SortBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backFromListGoesToSortButton() {
        val sort = FocusRequester()
        val row = FocusRequester()
        var sortHasFocus = false
        rule.setContent {
            var listFocused by remember { mutableStateOf(false) }
            SortBackHandler(sort, listFocused)
            Column {
                GlassButton(
                    text = "Ordina", onClick = {}, focusRequester = sort,
                    modifier = Modifier.onFocusChanged { sortHasFocus = it.isFocused },
                )
                LazyColumn(Modifier.height(200.dp).onFocusChanged { listFocused = it.hasFocus }) {
                    items(listOf("a", "b", "c")) { name ->
                        GlassButton(
                            text = name, onClick = {}, modifier = Modifier.fillMaxWidth(),
                            focusRequester = if (name == "a") row else null,
                        )
                    }
                }
            }
        }
        rule.runOnIdle { sort.requestFocus() }
        rule.runOnIdle { row.requestFocus() }
        rule.waitForIdle()
        assertFalse("cursor should be on the list", sortHasFocus)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue("Back should put the cursor on Ordina", sortHasFocus)
    }
}
