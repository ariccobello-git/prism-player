package dev.red74.prismplayer.reminders

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.red74.prismplayer.data.Reminder
import dev.red74.prismplayer.data.Reminders

/**
 * The reminders the viewer set, kept on the device (names and hashed channel keys only) and watched by the screens:
 * a card shows a bell when its reminder is set. Every change also sets or cancels the alarm (see [ReminderScheduler]).
 */
object ReminderStore {
    private var prefs: SharedPreferences? = null

    var current by mutableStateOf<List<Reminder>>(emptyList())
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        prefs = store
        current = Reminders.pruned(Reminders.decode(store.getString("list", null)), System.currentTimeMillis())
        save()
    }

    /** Loads the saved list without a screen (the alarm receivers run before the app does). */
    fun read(context: Context): List<Reminder> =
        Reminders.pruned(
            Reminders.decode(context.applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE).getString("list", null)),
            System.currentTimeMillis(),
        )

    fun isSet(id: String): Boolean = Reminders.isSet(current, id)

    /** Sets the reminder, or takes it away when it was set. Returns true when it is set now. */
    fun toggle(context: Context, reminder: Reminder, nowMs: Long = System.currentTimeMillis()): Boolean {
        val was = isSet(reminder.id)
        if (!was && !Reminders.canSet(reminder.startMs, nowMs)) return false
        current = Reminders.toggled(Reminders.pruned(current, nowMs), reminder)
        save()
        if (was) ReminderScheduler.cancel(context, reminder) else ReminderScheduler.schedule(context, reminder, nowMs)
        return !was
    }

    /** Drops the reminders that are over (called now and then while the app is open). */
    fun prune(nowMs: Long = System.currentTimeMillis()) {
        val kept = Reminders.pruned(current, nowMs)
        if (kept.size != current.size) {
            current = kept
            save()
        }
    }

    private fun save() {
        prefs?.edit()?.putString("list", Reminders.encode(current))?.apply()
    }
}
