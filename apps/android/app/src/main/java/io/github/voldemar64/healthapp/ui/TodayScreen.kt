package io.github.voldemar64.healthapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.voldemar64.healthapp.HealthApplication
import androidx.compose.ui.unit.dp
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
internal fun TodayScreen(state: HealthState, model: HealthViewModel, padding: PaddingValues,
    focusedReminderId: String?,
    addPressure: () -> Unit, addMedication: () -> Unit, addReminder: () -> Unit,
    openMedications: () -> Unit, openSettings: () -> Unit, openProfiles: () -> Unit,
    onVoice: () -> Unit) {
    val speech by (LocalContext.current.applicationContext as HealthApplication)
        .speechReadiness.collectAsStateWithLifecycle()
    val today = state.now.atZone(ZoneId.systemDefault()).toLocalDate()
    val upcoming = remember(state.data, state.now) {
        occurrences(state.data, today, today.plusDays(7))
            .filter { it.instant(ZoneId.systemDefault()) >= state.now &&
                state.data.intakes.none { record -> record.occurrence.key == it.key &&
                    record.status in setOf(IntakeStatus.TAKEN, IntakeStatus.SKIPPED) } }
            .minByOrNull { it.instant(ZoneId.systemDefault()) }
    }
    val latest = state.data.pressures.maxByOrNull { it.measuredAt }
    ScreenColumn(padding) {
        Heading(R.string.today)
        state.data.reminders.find { it.id == focusedReminderId }?.let { reminder ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(reminder.title, style = MaterialTheme.typography.titleMedium)
                    Text(reminder.triggerAt.atZone(ZoneId.systemDefault()).format(dateFormat) + " " +
                        reminder.triggerAt.atZone(ZoneId.systemDefault()).format(timeFormat))
                    if (reminder.status == ReminderStatus.UNCONFIRMED)
                        Text(stringResource(R.string.unconfirmed))
                    if (reminder.status == ReminderStatus.FIRED && reminder.type in
                        setOf(ReminderType.INTAKE, ReminderType.ONE_OFF, ReminderType.GENERAL)) {
                        ActionButton(R.string.taken) {
                            model.save { actOnReminder(reminder.id, ReminderAction.TAKEN, model.currentTime()) }
                        }
                        ActionButton(R.string.postpone_intake) {
                            model.save { actOnReminder(reminder.id, ReminderAction.POSTPONE, model.currentTime()) }
                        }
                        ActionButton(R.string.skip_intake) {
                            model.save { actOnReminder(reminder.id, ReminderAction.SKIP, model.currentTime()) }
                        }
                    }
                }
            }
        }
        if (!speech.checking && (!speech.modelReady || !speech.ttsReady))
            TextButton(onClick = openSettings) { Text(stringResource(R.string.voice_setup_needed)) }
        Text(state.profiles.find { it.id == state.activeProfileId }?.name.orEmpty(),
            style = MaterialTheme.typography.titleMedium)
        if (state.activeProfileId == LEGACY_PROFILE_ID &&
            state.profiles.find { it.id == LEGACY_PROFILE_ID }?.name == stringResource(R.string.profile_main))
            TextButton(onClick = openProfiles) { Text(stringResource(R.string.rename_main_profile_offer)) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.latest_pressure), style = MaterialTheme.typography.titleMedium)
                Text(if (latest == null) stringResource(R.string.unknown_value) else
                    stringResource(R.string.pressure_value, latest.systolic, latest.diastolic))
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.next_intake), style = MaterialTheme.typography.titleMedium)
                Text(if (upcoming == null) stringResource(R.string.no_intakes_day) else
                    "${upcoming.name} · ${upcoming.date.format(dateFormat)} ${upcoming.time.format(timeFormat)} · ${upcoming.dose.text} ${stringResource(unitResource(upcoming.dose.unit))}")
                if (upcoming != null) TextButton(onClick = openMedications) { Text(stringResource(R.string.medications_title)) }
            }
        }
        ActionButton(R.string.new_pressure, Modifier.fillMaxWidth(), onClick = addPressure)
        ActionButton(R.string.voice_entry, Modifier.fillMaxWidth(), onClick = onVoice)
        ActionButton(R.string.add_medication, Modifier.fillMaxWidth(), onClick = addMedication)
        ActionButton(R.string.add_reminder, Modifier.fillMaxWidth(), onClick = addReminder)
        state.data.reminders.filter { it.status in setOf(ReminderStatus.ACTIVE, ReminderStatus.FIRED,
            ReminderStatus.UNCONFIRMED) && it.type in setOf(ReminderType.INTAKE, ReminderType.ONE_OFF,
            ReminderType.GENERAL) }
            .sortedBy { it.triggerAt }.take(3).forEach { reminder ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${reminder.title} · ${reminder.triggerAt.atZone(ZoneId.systemDefault()).format(timeFormat)}",
                        Modifier.weight(1f))
                    if (reminder.status == ReminderStatus.UNCONFIRMED)
                        Text(stringResource(R.string.unconfirmed))
                    if (reminder.type in setOf(ReminderType.ONE_OFF, ReminderType.GENERAL))
                        TextButton(onClick = { model.save { cancelReminder(reminder.id) } }) {
                            Text(stringResource(R.string.cancel))
                        }
                }
            }
    }
}

@Composable
internal fun ReminderForm(state: HealthState, model: HealthViewModel, padding: PaddingValues,
    onClose: () -> Unit) {
    val zone = ZoneId.systemDefault()
    var title by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now(zone).toString()) }
    var timeText by rememberSaveable { mutableStateOf(LocalTime.now(zone).plusMinutes(15).withSecond(0).withNano(0).toString()) }
    var medicationId by rememberSaveable { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var relative by rememberSaveable { mutableStateOf(false) }
    var minutes by rememberSaveable { mutableStateOf("15") }
    ScreenColumn(padding) {
        Heading(R.string.add_reminder)
        Input(title, { title = it }, R.string.reminder_title)
        Choices(listOf(false, true), relative,
            { stringResource(if (it) R.string.reminder_after else R.string.reminder_at) }) { relative = it }
        if (relative) Input(minutes, { minutes = it }, R.string.reminder_minutes,
            androidx.compose.ui.text.input.KeyboardType.Number)
        else {
            DateButton(R.string.selected_date, LocalDate.parse(dateText)) { dateText = it.toString() }
            TimeButton(LocalTime.parse(timeText)) { timeText = it.toString() }
        }
        Text(stringResource(R.string.reminder_kind))
        Choices(listOf<String?>(null) + state.data.medications.map { it.id }, medicationId,
            { id -> if (id == null) stringResource(R.string.general_reminder) else
                state.data.medications.find { it.id == id }?.name.orEmpty() }) { medicationId = it }
        if (medicationId != null) Input(amount, { amount = it }, R.string.dose,
            androidx.compose.ui.text.input.KeyboardType.Decimal)
        ActionButton(R.string.save, Modifier.fillMaxWidth(), enabled = title.isNotBlank() &&
            (medicationId == null || amount.isNotBlank()) && !state.saving) {
            model.save(onSuccess = onClose) {
                val quantity = amount.takeIf { it.isNotBlank() }?.let(::parseStockAmount)
                if (relative) {
                    val delay = minutes.toLongOrNull()
                    checkInput(delay != null && delay in 1..10080, InputError.TIMES)
                    createTimerReminder(title, requireNotNull(delay) * 60, medicationId, quantity)
                } else createReminder(title, LocalDate.parse(dateText).atTime(LocalTime.parse(timeText))
                    .atZone(zone).toInstant(), medicationId, quantity)
            }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.cancel))
        }
    }
}
