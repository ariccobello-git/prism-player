package dev.red74.prismplayer.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.red74.prismplayer.MainActivity
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Reminders
import dev.red74.prismplayer.ui.Appearance

/** The alarm of a reminder went off: a notification says what starts soon and on which channel. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION) return
        // With the app in front the banner inside it says the same: no second notice.
        if (MainActivity.inFront) return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: return
        val channel = intent.getStringExtra(ReminderScheduler.EXTRA_CHANNEL).orEmpty()
        val start = intent.getLongExtra(ReminderScheduler.EXTRA_START, 0L)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val text = Appearance.localized(context)
        val minutes = Reminders.minutesLeft(start, System.currentTimeMillis())
        val body = if (minutes <= 0) text.getString(R.string.reminder_now, channel) else text.getString(R.string.reminder_in, minutes, channel)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, text.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(intent.getStringExtra(ReminderScheduler.EXTRA_ID).hashCode(), notification) }
    }

    private companion object {
        const val CHANNEL_ID = "reminders"
    }
}

/** After a restart the alarms are set again from the saved reminders. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReminderScheduler.scheduleAll(context, ReminderStore.read(context))
    }
}
