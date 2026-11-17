package io.github.voldemar64.healthapp.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.util.Log
import io.github.voldemar64.healthapp.domain.DoseUnit
import io.github.voldemar64.healthapp.MainActivity
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.HealthRepository
import io.github.voldemar64.healthapp.domain.Reminder
import io.github.voldemar64.healthapp.domain.ReminderAction
import io.github.voldemar64.healthapp.domain.ReminderStatus
import io.github.voldemar64.healthapp.domain.ReminderType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.Clock

class ReminderCoordinator(private val context: Context, private val repository: HealthRepository,
    private val scheduler: ReminderScheduler = AlarmReminderScheduler(context),
    private val clock: Clock = Clock.systemUTC()) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    val exactAvailable: Boolean get() = scheduler.exactAvailable
    val notificationsAvailable: Boolean get() = (Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        notifications.areNotificationsEnabled()

    suspend fun sync() = mutex.withLock {
        val now = clock.instant()
        val reminders = repository.refreshReminders(now)
        val missed = mutableListOf<Reminder>()
        reminders.filter { it.status == ReminderStatus.ACTIVE && scheduler.isDue(it) }.forEach { reminder ->
            repository.fireReminder(reminder.id, now)?.let(missed::add)
        }
        repository.expireUnconfirmed(now)
        val current = repository.refreshReminders(now)
        val expiredCount = reminders.count { old ->
            old.status == ReminderStatus.ACTIVE && scheduler.isDue(old) &&
                current.any { it.id == old.id && it.status == ReminderStatus.UNCONFIRMED }
        }
        current.forEach { reminder ->
            if (reminder.status == ReminderStatus.ACTIVE && !scheduler.isDue(reminder)) scheduler.schedule(reminder)
            else scheduler.cancel(reminder)
            if (reminder.status in setOf(ReminderStatus.ACTIVE, ReminderStatus.DONE,
                    ReminderStatus.CANCELLED, ReminderStatus.UNCONFIRMED))
                notifications.cancel(reminder.requestCode)
        }
        if (missed.size == 1 && expiredCount == 0) show(missed.single())
        else if (missed.size + expiredCount > 0) showSummary(missed.size + expiredCount)
    }

    suspend fun cancelProfile(profileId: String) {
        repository.refreshReminders(clock.instant()).filter { it.userId == profileId }.forEach {
            scheduler.cancel(it)
            notifications.cancel(it.requestCode)
        }
    }

    suspend fun show(reminder: Reminder) {
        if (!notificationsAvailable || reminder.type == ReminderType.EXPIRY) return
        ensureChannel()
        val profile = repository.profiles.first().find { it.id == reminder.userId }?.name ?: return
        val content = when (reminder.type) {
            ReminderType.STOCK_ZERO -> "${reminder.title}: запас закончился"
            ReminderType.STOCK_LOW -> "${reminder.title}: запас меньше семи дней"
            else -> buildString {
                append(reminder.title)
                reminder.amount?.let {
                    val unit = when (reminder.unit) {
                        DoseUnit.TABLET -> R.string.unit_tablet
                        DoseUnit.CAPSULE -> R.string.unit_capsule
                        DoseUnit.MG -> R.string.unit_mg
                        DoseUnit.G -> R.string.unit_g
                        DoseUnit.OTHER, null -> R.string.unit_other
                    }
                    append(" · ${it.stripTrailingZeros().toPlainString()} ${context.getString(unit)}")
                }
            }
        }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_medications)
            .setContentTitle(profile)
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setContentIntent(openApp(reminder))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (reminder.type in setOf(ReminderType.INTAKE, ReminderType.ONE_OFF, ReminderType.GENERAL)) builder.addAction(
            Notification.Action.Builder(null, "Принял", actionIntent(reminder, ReminderAction.TAKEN)).build())
        if (reminder.type in setOf(ReminderType.INTAKE, ReminderType.ONE_OFF, ReminderType.GENERAL)) {
            builder.addAction(Notification.Action.Builder(null, "Отложить на 15 минут",
                actionIntent(reminder, ReminderAction.POSTPONE)).build())
            builder.addAction(Notification.Action.Builder(null, "Пропустить",
                actionIntent(reminder, ReminderAction.SKIP)).build())
        }
        notifications.notify(reminder.requestCode, builder.build())
    }

    suspend fun speak(reminder: Reminder) {
        if (reminder.type in setOf(ReminderType.EXPIRY, ReminderType.STOCK_LOW, ReminderType.STOCK_ZERO,
                ReminderType.STOCK_OK) || !context.getSharedPreferences("reminder_settings",
                Context.MODE_PRIVATE).getBoolean("speak", true)) return
        val profile = repository.profiles.first().find { it.id == reminder.userId }?.name ?: return
        val unit = when (reminder.unit) {
            DoseUnit.TABLET -> "таблетки"; DoseUnit.CAPSULE -> "капсулы"
            DoseUnit.MG -> "миллиграмма"; DoseUnit.G -> "грамма"
            DoseUnit.OTHER -> "единицы"; null -> ""
        }
        val message = "$profile. ${reminder.title}. " + (reminder.amount?.let {
            "${it.stripTrailingZeros().toPlainString()} $unit."
        } ?: "")
        try {
            context.startForegroundService(Intent(context, ReminderSpeechService::class.java)
                .putExtra(ReminderSpeechService.EXTRA_MESSAGE, message))
        } catch (failure: RuntimeException) {
            Log.w("HealthReminder", "TTS service unavailable", failure)
        }
    }

    private fun showSummary(count: Int) {
        if (!notificationsAvailable) return
        ensureChannel()
        notifications.notify(SUMMARY_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_medications)
            .setContentTitle("Пропущенные напоминания")
            .setContentText("$count напоминаний. Откройте приложение для проверки.")
            .setContentIntent(openApp(SUMMARY_ID))
            .setAutoCancel(true).build())
    }

    private fun ensureChannel() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Напоминания",
            NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp(code: Int): PendingIntent = PendingIntent.getActivity(context, code,
        Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openApp(reminder: Reminder): PendingIntent = PendingIntent.getActivity(context,
        reminder.requestCode, Intent(context, MainActivity::class.java)
            .setData("health-reminder://open/${android.net.Uri.encode(reminder.id)}".toUri())
            .putExtra("reminder_profile_id", reminder.userId)
            .putExtra("reminder_target_id", reminder.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actionIntent(reminder: Reminder, action: ReminderAction): PendingIntent {
        val intent = Intent(ReminderReceiver.ACTION_RESPONSE).setClass(context, ReminderReceiver::class.java)
            .setData("health-reminder://response/${android.net.Uri.encode(reminder.id)}/${action.name}".toUri())
            .putExtra(AlarmReminderScheduler.EXTRA_REMINDER_ID, reminder.id)
            .putExtra(ReminderReceiver.EXTRA_ACTION, action.name)
        return PendingIntent.getBroadcast(context, reminder.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val CHANNEL_ID = "health_reminders"
        private const val SUMMARY_ID = 187654321
    }
}
