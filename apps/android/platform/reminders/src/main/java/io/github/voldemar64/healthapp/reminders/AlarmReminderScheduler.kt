package io.github.voldemar64.healthapp.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.voldemar64.healthapp.domain.Reminder

interface ReminderScheduler {
    val exactAvailable: Boolean
    fun isDue(reminder: Reminder): Boolean
    fun schedule(reminder: Reminder): Boolean
    fun cancel(reminder: Reminder)
}

class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override val exactAvailable: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun elapsedTarget(reminder: Reminder): Long? {
        val count = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1)
        return reminder.elapsedAtMillis.takeIf { reminder.bootCount != null && reminder.bootCount == count }
    }

    override fun isDue(reminder: Reminder): Boolean = elapsedTarget(reminder)?.let {
        SystemClock.elapsedRealtime() >= it
    } ?: (System.currentTimeMillis() >= reminder.triggerAt.toEpochMilli())

    override fun schedule(reminder: Reminder): Boolean {
        val operation = pending(reminder, PendingIntent.FLAG_UPDATE_CURRENT) ?: return false
        val elapsed = elapsedTarget(reminder)
        val at = elapsed ?: reminder.triggerAt.toEpochMilli()
        val type = if (elapsed != null) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP
        return try {
            if (exactAvailable) {
                alarms.setExactAndAllowWhileIdle(type, at, operation)
                true
            } else {
                alarms.setAndAllowWhileIdle(type, at, operation)
                false
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(type, at, operation)
            false
        }
    }

    override fun cancel(reminder: Reminder) {
        pending(reminder, PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    private fun pending(reminder: Reminder, flag: Int): PendingIntent? {
        val intent = Intent(ACTION_REMINDER).setClassName(context,
            "io.github.voldemar64.healthapp.reminders.ReminderReceiver")
            .setData(Uri.parse("health-reminder://alarm/${Uri.encode(reminder.id)}"))
            .putExtra(EXTRA_REMINDER_ID, reminder.id)
        return PendingIntent.getBroadcast(context, reminder.requestCode,
            intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_REMINDER = "io.github.voldemar64.healthapp.REMINDER"
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}
