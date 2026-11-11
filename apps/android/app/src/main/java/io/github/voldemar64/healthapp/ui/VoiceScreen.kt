package io.github.voldemar64.healthapp.ui

import android.Manifest
import android.content.Intent
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
import androidx.lifecycle.Lifecycle
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.voldemar64.healthapp.R

@Composable
internal fun VoiceControls(model: VoiceViewModel, state: VoiceUiState, enabled: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.start() else model.permissionDenied()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.pause() }
    }
    ActionButton(if (state.active) state.status else R.string.voice_entry, Modifier.fillMaxWidth(), enabled) {
        if (state.active) model.pause()
        else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.start()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    state.error?.let { code ->
        Text(stringResource(when (code) {
            "VOICE_MISSING" -> R.string.voice_missing
            "PERMISSION" -> R.string.voice_permission
            "MODEL" -> R.string.voice_model_error
            "TTS" -> R.string.voice_tts_error
            else -> R.string.voice_error
        }), color = MaterialTheme.colorScheme.error)
        if (code == "VOICE_MISSING") ActionButton(R.string.voice_settings) {
            model.pause()
            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        if (code == "PERMISSION") ActionButton(R.string.voice_app_settings) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }
}
