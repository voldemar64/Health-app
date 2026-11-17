package io.github.voldemar64.healthapp.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.voldemar64.healthapp.HealthApplication
import io.github.voldemar64.healthapp.domain.ReminderAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as HealthApplication
                when (intent.action) {
                    AlarmReminderScheduler.ACTION_REMINDER -> {
                        val id = intent.getStringExtra(AlarmReminderScheduler.EXTRA_REMINDER_ID)
                        if (id != null) app.repository.fireReminder(id, Instant.now())?.let {
                            app.reminderCoordinator.show(it)
                            app.reminderCoordinator.speak(it)
                        }
                    }
                    ACTION_RESPONSE -> {
                        val id = intent.getStringExtra(AlarmReminderScheduler.EXTRA_REMINDER_ID)
                        val action = intent.getStringExtra(EXTRA_ACTION)?.let(ReminderAction::valueOf)
                        if (id != null && action != null) app.repository.actOnReminder(id, action, Instant.now())
                    }
                }
                app.reminderCoordinator.sync()
            } catch (failure: Exception) {
                Log.e("HealthReminder", "Reminder processing failed", failure)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESPONSE = "io.github.voldemar64.healthapp.REMINDER_RESPONSE"
        const val EXTRA_ACTION = "response_action"
    }
}
