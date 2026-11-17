package io.github.voldemar64.healthapp

import android.app.Application
import io.github.voldemar64.healthapp.data.HealthDatabase
import io.github.voldemar64.healthapp.data.RoomHealthRepository
import io.github.voldemar64.healthapp.reminders.ReminderCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.voldemar64.healthapp.reminders.ReminderSyncWorker
import java.util.concurrent.TimeUnit
import android.util.Log
import io.github.voldemar64.healthapp.speech.OfflineRecognizer
import io.github.voldemar64.healthapp.speech.OfflineSpeechOutput
import kotlinx.coroutines.flow.MutableStateFlow

data class SpeechReadiness(val checking: Boolean = true, val modelReady: Boolean = false,
    val ttsReady: Boolean = false)

open class HealthApplication : Application() {
    protected open val databaseName: String = "health.db"
    val database by lazy { HealthDatabase.open(this, databaseName) }
    val repository by lazy { RoomHealthRepository(database, context = this) }
    val reminderCoordinator by lazy { ReminderCoordinator(this, repository) }
    val speechReadiness = MutableStateFlow(SpeechReadiness())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("reminder_reconciliation",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderSyncWorker>(15, TimeUnit.MINUTES).build())
        scope.launch {
            repository.reminderChanges.collect {
                try { reminderCoordinator.sync() }
                catch (failure: Exception) { Log.e("HealthReminder", "Reconciliation failed", failure) }
            }
        }
    }

    fun checkSpeech() {
        speechReadiness.value = SpeechReadiness()
        scope.launch {
            val recognizer = OfflineRecognizer(this@HealthApplication)
            val modelReady = try { recognizer.prepare(); true }
                catch (failure: Exception) { Log.w("HealthSpeech", "Offline model unavailable", failure); false }
                finally { recognizer.release() }
            val output = OfflineSpeechOutput(this@HealthApplication)
            val ttsReady = try { output.prepare(); true }
                catch (failure: Exception) { Log.w("HealthSpeech", "Russian offline voice unavailable", failure); false }
                finally { output.release() }
            speechReadiness.value = SpeechReadiness(false, modelReady, ttsReady)
        }
    }
}
