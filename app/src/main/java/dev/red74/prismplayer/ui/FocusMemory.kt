package dev.red74.prismplayer.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged

/**
 * Where the cursor was on each screen, so that coming back to a screen puts it back where it was: on the playlist
 * just closed, on the button that opened the page now left. In memory only, for the life of the app.
 */
class FocusMemory {
    internal val last = HashMap<String, String>()
}

val LocalFocusMemory = staticCompositionLocalOf { FocusMemory() }

/** The screen the composition below belongs to (one name per screen kind). */
val LocalScreenKey = staticCompositionLocalOf { "" }

/**
 * Marks a focusable item of the current screen with a stable [id]: the item is remembered when it gets the
 * cursor, and gets the cursor back when the screen is shown again.
 */
@Composable
fun Modifier.focusMemory(id: String): Modifier {
    val memory = LocalFocusMemory.current
    val screen = LocalScreenKey.current
    val requester = remember { FocusRequester() }
    val restore = remember(screen, id) { memory.last[screen] == id }
    if (restore) {
        LaunchedEffect(Unit) {
            // One frame after the screen's own first request, so the remembered item is the one that stays.
            withFrameNanos { }
            requester.requestWhenReady()
        }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.last[screen] = id }
}

/**
 * Remembers which item of a section the cursor was on last, so that coming back into the section (an arrow pressed
 * from the row above or below, from the next column) puts the cursor on that same item: from "Stagione 2" down to the
 * episodes and up again, the cursor lands on "Stagione 2", not on whichever item is geometrically nearest.
 * Items are numbered by [id]; if the remembered item is no longer on screen (list refreshed, scrolled out) the
 * nearest one still there is used. [visible] narrows "on screen" further for lazy lists.
 */
class ReturnFocus(initial: Int = NONE, private val visible: (Int) -> Boolean = { true }) {
    private val requesters = HashMap<Int, FocusRequester>()
    private val attached = java.util.TreeMap<Int, Int>()

    /** The id of the item the cursor was on last. */
    var last: Int = initial

    fun requester(id: Int): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    // Counted, because a recycled item may come up before the old one with the same id has gone.
    internal fun attach(id: Int) { attached[id] = (attached[id] ?: 0) + 1 }

    internal fun detach(id: Int) {
        val left = (attached[id] ?: 0) - 1
        if (left > 0) {
            attached[id] = left
        } else {
            attached.remove(id)
        }
    }

    /** The item to go back to: the last one, else the nearest one on screen; null when nothing is known. */
    fun targetId(): Int? {
        if (last == NONE) return null
        val ids = attached.keys.filter(visible)
        if (ids.isEmpty()) return null
        if (last in ids) return last
        val below = ids.lastOrNull { it < last }
        val above = ids.firstOrNull { it > last }
        return when {
            below == null -> above
            above == null -> below
            last - below <= above - last -> below
            else -> above
        }
    }

    fun target(): FocusRequester? = targetId()?.let { requester(it) }

    companion object {
        const val NONE = Int.MIN_VALUE
    }
}

/** A section (row, column, grid) that gives the cursor back to the item it was on, see [ReturnFocus]. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.returnSection(memory: ReturnFocus): Modifier = this
    .focusProperties { enter = { memory.target() ?: FocusRequester.Default } }
    .focusGroup()

/** Marks a focusable item of a [returnSection] under [id]. */
@Composable
fun Modifier.returnItem(memory: ReturnFocus, id: Int): Modifier {
    DisposableEffect(memory, id) {
        memory.attach(id)
        onDispose { memory.detach(id) }
    }
    return this
        .focusRequester(memory.requester(id))
        .onFocusChanged { if (it.hasFocus) memory.last = id }
}
