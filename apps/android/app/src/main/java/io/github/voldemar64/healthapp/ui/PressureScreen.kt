package io.github.voldemar64.healthapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
internal fun PressureScreen(state: HealthState, model: HealthViewModel, padding: PaddingValues) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableIntStateOf(7) }
    if (editing != null) {
        val existing = state.data.pressures.find { it.id == editing }
        if (editing == "new" || existing != null) {
            key(editing) { PressureForm(existing, state.saving, padding, { editing = null }) { measurement ->
                model.save(onSuccess = { editing = null }) { savePressure(measurement()) }
            } }
        }
        return
    }
    val end = state.now.atZone(ZoneId.systemDefault()).toLocalDate()
    val start = end.minusDays(period.toLong() - 1)
    val list = pressureInRange(state.data.pressures, start, end, ZoneId.systemDefault())
    ScreenColumn(padding) {
        Heading(R.string.pressure_title)
        Choices(listOf(7, 30), period, { stringResource(if (it == 7) R.string.days_7 else R.string.days_30) }) { period = it }
        if (!state.loaded) Text(stringResource(R.string.loading))
        else {
            if (state.data.pressures.isEmpty()) EmptyState(R.string.pressure_empty_title, R.string.pressure_empty_body)
            else if (list.isEmpty()) Text(stringResource(R.string.no_measurements_period))
            if (list.isNotEmpty()) {
                MeasurementChart(list, pulse = false)
                if (list.any { it.pulse != null }) MeasurementChart(list, pulse = true)
            }
            if (state.data.pressures.isNotEmpty() || state.data.schedules.isNotEmpty() || state.data.intakes.isNotEmpty()) ReportExport(state.data, period)
            if (list.isNotEmpty()) {
                list.asReversed().forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.pressure_value, item.systolic, item.diastolic),
                                style = MaterialTheme.typography.titleMedium)
                            val local = item.measuredAt.atZone(ZoneId.systemDefault())
                            Text(stringResource(R.string.date_time, local.format(dateFormat), local.format(timeFormat)))
                            item.pulse?.let { Text(stringResource(R.string.pulse_value, it)) }
                            if (item.note.isNotBlank()) Text(item.note)
                            ActionButton(R.string.edit) { editing = item.id }
                            OutlinedButton({ deleting = item.id }, Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_pressure)) },
            text = { Text(stringResource(R.string.delete_pressure_body)) },
            confirmButton = { ActionButton(R.string.delete, enabled = !state.saving) {
                model.save(onSuccess = { deleting = null }) { deletePressure(id) }
            } }, dismissButton = { ActionButton(R.string.cancel) { deleting = null } })
    }
}

@Composable
internal fun PressureForm(existing: PressureMeasurement?, saving: Boolean, padding: PaddingValues,
    onClose: () -> Unit, header: @Composable ColumnScope.() -> Unit = {},
    sharedDraft: VoiceDraft? = null, onDraft: ((VoiceDraft) -> Unit)? = null,
    onSave: (() -> PressureMeasurement) -> Unit) {
    var localDraft by rememberLocalDraft { pressureDraft(existing) }
    val draft = sharedDraft ?: localDraft
    fun update(field: VoiceField, value: String) {
        val changed = draft.withValue(field, value)
        if (onDraft != null) onDraft(changed) else localDraft = changed
    }
    BackHandler { if (!saving) onClose() }
    ScreenColumn(padding, enabled = !saving) {
        header()
        Heading(if (existing == null) R.string.new_pressure else R.string.edit_pressure)
        Input(draft.value(VoiceField.SYSTOLIC), { update(VoiceField.SYSTOLIC, it) }, R.string.systolic, KeyboardType.Number)
        Input(draft.value(VoiceField.DIASTOLIC), { update(VoiceField.DIASTOLIC, it) }, R.string.diastolic, KeyboardType.Number)
        Input(draft.value(VoiceField.PULSE), { update(VoiceField.PULSE, it) }, R.string.pulse, KeyboardType.Number)
        DateButton(R.string.date, runCatching { LocalDate.parse(draft.value(VoiceField.DATE), dateFormat) }.getOrDefault(LocalDate.now())) {
            update(VoiceField.DATE, it.format(dateFormat))
        }
        TimeButton(runCatching { LocalTime.parse(draft.value(VoiceField.TIMES)) }.getOrDefault(LocalTime.now())) {
            update(VoiceField.TIMES, it.format(timeFormat))
        }
        ActionButton(R.string.save, Modifier.fillMaxWidth(), !saving) {
            onSave {
                checkInput(draft.value(VoiceField.PULSE).toIntOrNull() != null, InputError.PULSE)
                PressureMeasurement(draft.id, draft.value(VoiceField.SYSTOLIC).toIntOrNull() ?: 0,
                    draft.value(VoiceField.DIASTOLIC).toIntOrNull() ?: 0, draft.value(VoiceField.PULSE).toIntOrNull(),
                    LocalDate.parse(draft.value(VoiceField.DATE), dateFormat).atTime(LocalTime.parse(draft.value(VoiceField.TIMES)))
                        .atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault().id, existing?.note.orEmpty())
            }
        }
        ActionButton(R.string.cancel, enabled = !saving, onClick = onClose)
    }
}
