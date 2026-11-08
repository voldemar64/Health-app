package io.github.voldemar64.healthapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun VoiceScreen(model: VoiceViewModel, padding: PaddingValues, onClose: () -> Unit, onNavigate: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.start() else model.permissionDenied()
    }
    fun begin() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.start()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.pause() }
    }
    BackHandler { model.pause(); onClose() }
    LaunchedEffect(state.destination) { state.destination?.let(onNavigate) }
    ScreenColumn(padding) {
        Heading(R.string.voice_entry)
        Text(stringResource(R.string.voice_privacy))
        Text(stringResource(state.status), style = MaterialTheme.typography.titleMedium)
        if (state.active) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.prompt.isNotBlank()) Card(Modifier.fillMaxWidth()) {
            Text(state.prompt, Modifier.padding(20.dp))
        }
        if (state.transcript.isNotBlank()) Text(stringResource(R.string.voice_heard, state.transcript))
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
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri()))
            }
        }
        if (state.active) ActionButton(R.string.voice_pause, Modifier.fillMaxWidth(), onClick = model::pause)
        else ActionButton(R.string.voice_start, Modifier.fillMaxWidth(), onClick = ::begin)
        val draft = state.dialog.draft
        if (draft != null && !state.finished && !state.active) {
            Text(stringResource(R.string.voice_manual_hint))
            VoiceDialog.editableFields(draft.kind).forEach { field ->
                val value = draft.values[field].orEmpty()
                when (field) {
                    VoiceField.UNIT -> Choices(DoseUnit.entries, DoseUnit.entries.find { it.name == value },
                        { stringResource(unitResource(it)) }) { model.edit(field, it.name) }
                    VoiceField.REPEAT -> Choices(Repeat.entries, Repeat.entries.find { it.name == value },
                        { stringResource(repeatResource(it)) }) { model.edit(field, it.name) }
                    VoiceField.STATUS -> Choices(IntakeStatus.entries, IntakeStatus.entries.find { it.name == value },
                        { stringResource(if (it == IntakeStatus.TAKEN) R.string.taken else R.string.skipped) }) { model.edit(field, it.name) }
                    VoiceField.DAYS -> if (draft.values[VoiceField.REPEAT] == "WEEKLY") {
                        Text(stringResource(R.string.voice_days_label))
                        DayOfWeek.entries.forEach { day ->
                            val selected = value.split(',').mapNotNull(String::toIntOrNull).toSet()
                            FilterChip(selected = day.value in selected, onClick = {
                                model.edit(field, (if (day.value in selected) selected - day.value else selected + day.value).sorted().joinToString(","))
                            }, label = { Text(day.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru"))) }, modifier = Modifier.heightIn(min = 56.dp))
                        }
                    }
                    else -> Input(value, { model.edit(field, it) }, fieldLabel(field))
                }
            }
            ActionButton(R.string.voice_review, Modifier.fillMaxWidth(), onClick = model::review)
        }
        ActionButton(R.string.close) { model.pause(); onClose() }
    }
}

private fun fieldLabel(field: VoiceField) = when (field) {
    VoiceField.SYSTOLIC -> R.string.systolic; VoiceField.DIASTOLIC -> R.string.diastolic; VoiceField.PULSE -> R.string.pulse
    VoiceField.NAME -> R.string.medication_name; VoiceField.AMOUNT -> R.string.dose
    VoiceField.TIMES -> R.string.voice_times_label; VoiceField.DATE -> R.string.voice_date_label
    VoiceField.END_DATE -> R.string.voice_end_label
    else -> R.string.repeat
}
