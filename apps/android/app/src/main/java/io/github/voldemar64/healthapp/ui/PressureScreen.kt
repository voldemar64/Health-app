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
    var startText by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(6).toString()) }
    var endText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    if (editing != null) {
        val existing = state.data.pressures.find { it.id == editing }
        if (editing == "new" || existing != null) {
            key(editing) { PressureForm(existing, state.saving, padding, { editing = null }) { measurement ->
                model.save(onSuccess = { editing = null }) { savePressure(measurement()) }
            } }
        }
        return
    }
    val end = if (period == 0) LocalDate.parse(endText) else LocalDate.now()
    val start = if (period == 0) LocalDate.parse(startText) else end.minusDays(period.toLong() - 1)
    val list = pressureInRange(state.data.pressures, start, end, ZoneId.systemDefault())
    ScreenColumn(padding) {
        Heading(R.string.pressure_title)
        if (!state.loaded) Text(stringResource(R.string.loading))
        else if (state.data.pressures.isEmpty()) EmptyState(R.string.pressure_empty_title, R.string.pressure_empty_body)
        else {
            Choices(listOf(7, 30, 0), period, { stringResource(when (it) {
                7 -> R.string.days_7; 30 -> R.string.days_30; else -> R.string.custom_period
            }) }) { period = it }
            if (period == 0) {
                DateButton(R.string.period_start, start) { startText = it.toString() }
                DateButton(R.string.period_end, end) { endText = it.toString() }
            }
            Text(stringResource(R.string.date_range, start.format(dateFormat), end.format(dateFormat)))
            if (end < start) Text(stringResource(R.string.error_dates), color = MaterialTheme.colorScheme.error)
            else if (list.isEmpty()) Text(stringResource(R.string.no_measurements_period))
            else {
                val summary = summarize(list)!!
                Text(stringResource(R.string.pressure_average, summary.systolic.roundToInt(),
                    summary.diastolic.roundToInt(), summary.count))
                MeasurementChart(list, pulse = false)
                if (list.any { it.pulse != null }) MeasurementChart(list, pulse = true)
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
    onClose: () -> Unit, header: @Composable ColumnScope.() -> Unit = {}, onSave: (() -> PressureMeasurement) -> Unit) {
    val local = remember { (existing?.measuredAt ?: Instant.now()).atZone(ZoneId.systemDefault()) }
    var systolic by rememberSaveable { mutableStateOf(existing?.systolic?.toString() ?: "") }
    var diastolic by rememberSaveable { mutableStateOf(existing?.diastolic?.toString() ?: "") }
    var pulse by rememberSaveable { mutableStateOf(existing?.pulse?.toString() ?: "") }
    var date by rememberSaveable { mutableStateOf(local.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(local.toLocalTime().withSecond(0).withNano(0).toString()) }
    val id = rememberSaveable { existing?.id ?: newId() }
    BackHandler { if (!saving) onClose() }
    ScreenColumn(padding) {
        header()
        Heading(if (existing == null) R.string.new_pressure else R.string.edit_pressure)
        Input(systolic, { systolic = it }, R.string.systolic, KeyboardType.Number)
        Input(diastolic, { diastolic = it }, R.string.diastolic, KeyboardType.Number)
        Input(pulse, { pulse = it }, R.string.pulse, KeyboardType.Number)
        DateButton(R.string.date, LocalDate.parse(date)) { date = it.toString() }
        TimeButton(LocalTime.parse(time)) { time = it.toString() }
        ActionButton(R.string.save, Modifier.fillMaxWidth(), !saving) {
            onSave {
                checkInput(pulse.toIntOrNull() != null, InputError.PULSE)
                PressureMeasurement(id, systolic.toIntOrNull() ?: 0, diastolic.toIntOrNull() ?: 0,
                    pulse.takeIf { it.isNotBlank() }?.toIntOrNull(),
                    LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant(),
                    ZoneId.systemDefault().id, existing?.note.orEmpty())
            }
        }
        ActionButton(R.string.cancel, enabled = !saving, onClick = onClose)
    }
}
