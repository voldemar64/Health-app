package io.github.voldemar64.healthapp.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.voldemar64.healthapp.HealthApplication

class ReminderSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as HealthApplication).reminderCoordinator.sync()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
