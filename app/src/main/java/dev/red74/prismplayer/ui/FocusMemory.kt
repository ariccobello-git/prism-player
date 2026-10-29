package dev.red74.prismplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
