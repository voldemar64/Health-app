package io.github.voldemar64.healthapp.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.speech.OfflineSpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ReminderSpeechService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val output by lazy { OfflineSpeechOutput(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE).orEmpty()
        if (message.isBlank()) { stopSelf(startId); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Озвучивание напоминаний", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_medications)
            .setContentTitle("Напоминание")
            .setContentText(message)
            .build())
        scope.launch {
            try { output.speak(message) }
            catch (failure: Exception) { Log.w("HealthReminder", "TTS unavailable", failure) }
            finally { output.release(); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        output.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL = "reminder_speech"
        private const val NOTIFICATION_ID = 199733
    }
}
