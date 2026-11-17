package io.github.voldemar64.healthapp.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.voldemar64.healthapp.HealthApplication
import io.github.voldemar64.healthapp.R
import androidx.core.net.toUri
import androidx.core.content.edit

@Composable
internal fun SettingsScreen(padding: PaddingValues, onClose: () -> Unit) {
    val context = LocalContext.current
    val coordinator = (context.applicationContext as HealthApplication).reminderCoordinator
    val application = context.applicationContext as HealthApplication
    val speech by application.speechReadiness.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val preferences = remember { context.getSharedPreferences("reminder_settings", android.content.Context.MODE_PRIVATE) }
    var speak by remember { mutableStateOf(preferences.getBoolean("speak", true)) }
    ScreenColumn(padding) {
        Heading(R.string.settings)
        Text(stringResource(R.string.reminder_permission_status), style = MaterialTheme.typography.titleMedium)
        @Suppress("UNUSED_VARIABLE") val version = refresh
        Text(if (coordinator.notificationsAvailable) stringResource(R.string.reminder_notifications_ready)
            else stringResource(R.string.reminder_notifications_limited))
        if (!coordinator.notificationsAvailable)
            ActionButton(R.string.allow_notifications) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        Text(if (coordinator.exactAvailable) stringResource(R.string.reminder_exact_ready)
            else stringResource(R.string.reminder_exact_limited))
        if (!coordinator.exactAvailable) ActionButton(R.string.allow_exact_alarms) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.speak_reminders), Modifier.weight(1f))
            Switch(checked = speak, onCheckedChange = {
                speak = it
                preferences.edit { putBoolean("speak", it) }
            })
        }
        Text(if (speech.checking) stringResource(R.string.voice_checking) else
            stringResource(if (speech.modelReady) R.string.voice_model_ready else R.string.voice_model_missing))
        Text(if (speech.checking) stringResource(R.string.voice_checking) else
            stringResource(if (speech.ttsReady) R.string.voice_tts_ready else R.string.voice_tts_missing))
        ActionButton(R.string.voice_check_again) { application.checkSpeech() }
        Heading(R.string.voice_help)
        Text(stringResource(R.string.voice_help_body))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.back))
        }
    }
}
