package dev.red74.prismplayer.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.red74.prismplayer.data.Reminder
import dev.red74.prismplayer.data.Reminders

/** Sets and cancels the alarm of each reminder. The alarm may come a little late when the device sleeps: minutes of lead make that harmless. */
object ReminderScheduler {
    const val ACTION = "dev.red74.prismplayer.REMINDER"
    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_CHANNEL = "channel"
    const val EXTRA_START = "start"

    private fun intent(context: Context, reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_ID, reminder.id)
            .putExtra(EXTRA_TITLE, reminder.title)
            .putExtra(EXTRA_CHANNEL, reminder.channelName)
            .putExtra(EXTRA_START, reminder.startMs)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, reminder: Reminder, nowMs: Long = System.currentTimeMillis()) {
        val alarms = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Reminders.triggerAt(reminder.startMs, nowMs), intent(context, reminder))
        }
    }

    fun cancel(context: Context, reminder: Reminder) {
        val alarms = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarms.cancel(intent(context, reminder)) }
    }

    /** After a restart of the device the alarms are gone: set them again. */
    fun scheduleAll(context: Context, list: List<Reminder>, nowMs: Long = System.currentTimeMillis()) {
        list.forEach { schedule(context, it, nowMs) }
    }
}
