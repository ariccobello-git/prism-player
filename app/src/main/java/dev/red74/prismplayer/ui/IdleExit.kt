package dev.red74.prismplayer.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R

/**
 * Closing the app when nobody is using it: after [IDLE_MS] with no key pressed, no list loading and nothing
 * playing, a [COUNTDOWN_S] second countdown appears; any key cancels it, at zero the app closes.
 * Whatever is playing (the full-screen player, even paused, or a preview) keeps the clock at zero, so the
 * countdown can never appear while a content is being watched.
 */
object IdleExit {
    const val IDLE_MS = 5 * 60_000L
    const val COUNTDOWN_S = 15

    /** Last moment the app was in use, on the [SystemClock.elapsedRealtime] clock (set when the app comes to front). */
    @Volatile
    var lastActivityAt: Long = 0L
        private set

    /** True while the countdown is on screen: the next key only cancels it and does nothing else. */
    @Volatile
    var countingDown: Boolean = false

    fun touch(now: Long = SystemClock.elapsedRealtime()) {
        lastActivityAt = now
    }

    /** Seconds left before closing, or null while the app has been idle for less than [IDLE_MS]. */
    fun secondsLeft(now: Long, lastActivity: Long): Int? {
        val idle = now - lastActivity
        if (idle < IDLE_MS) return null
        val left = COUNTDOWN_S - ((idle - IDLE_MS) / 1000L).toInt()
        return left.coerceAtLeast(0)
    }
}

/** The countdown shown over the whole app. */
@Composable
fun IdleCountdown(secondsLeft: Int) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(Modifier.widthIn(max = 720.dp)) {
            Column(
                Modifier.padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PrismText(stringResource(R.string.idle_title), size = 32.sp, weight = FontWeight.Bold)
                PrismText(secondsLeft.toString(), size = 72.sp, weight = FontWeight.Bold)
                PrismText(stringResource(R.string.idle_message), size = 20.sp, color = PrismColors.TextSecondary)
            }
        }
    }
}
