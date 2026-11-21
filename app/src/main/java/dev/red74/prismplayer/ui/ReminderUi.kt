package dev.red74.prismplayer.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.MainActivity
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.FavoriteKey
import dev.red74.prismplayer.data.Reminder
import dev.red74.prismplayer.data.Reminders
import dev.red74.prismplayer.reminders.ReminderStore
import kotlinx.coroutines.delay

/** The reminder for a programme that will be on [channel] (the channel is kept by name and hashed key only). */
fun reminderFor(title: String, startMs: Long, channel: Channel): Reminder {
    val key = FavoriteKey.of(channel)
    return Reminder(Reminders.idOf(title, startMs, key), title, startMs, channel.name, key)
}

/**
 * Sets or removes a reminder; returns true when it is set afterwards. The first reminder asks for the right to show
 * notifications (Android 13 and later); a refusal only means the notice stays inside the app.
 */
@Composable
fun rememberReminderToggle(): (Reminder) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { reminder: Reminder ->
            val on = ReminderStore.toggle(context, reminder)
            if (on) findActivity(context)?.askNotificationPermission()
            on
        }
    }
}

private fun findActivity(context: Context): MainActivity? {
    var c: Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is MainActivity) return c
        c = c.baseContext
    }
    return null
}

/**
 * The notice inside the app when a reminder's moment comes (a few minutes before the start): on top of whatever is on
 * screen, the player included, for ten seconds. It does not take the cursor, so nothing being watched is disturbed.
 */
@Composable
fun ReminderBanner(modifier: Modifier = Modifier, onShown: () -> Unit = {}) {
    var shown by remember { mutableStateOf<Reminder?>(null) }
    val announced = remember { HashSet<String>() }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            ReminderStore.prune(now)
            val due = Reminders.due(ReminderStore.current, now, announced).firstOrNull()
            if (due != null) {
                announced += due.id
                shown = due
                onShown()
                delay(BANNER_MS)
                shown = null
            } else {
                delay(CHECK_MS)
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = shown != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val reminder = shown ?: return@AnimatedVisibility
            val minutes = Reminders.minutesLeft(reminder.startMs, System.currentTimeMillis())
            GlassPanel(Modifier.padding(top = 28.dp), cornerRadius = 22.dp) {
                Column(Modifier.padding(horizontal = 28.dp, vertical = 16.dp)) {
                    PrismText(
                        text = if (minutes <= 0) stringResource(R.string.reminder_banner_now, reminder.title, reminder.channelName)
                        else stringResource(R.string.reminder_banner_in, reminder.title, minutes, reminder.channelName),
                        size = 22.sp,
                        weight = FontWeight.SemiBold,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

private const val BANNER_MS = 10_000L
private const val CHECK_MS = 15_000L
