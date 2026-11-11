package io.github.voldemar64.healthapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MedicationForm(existing: ScheduleRevision?, initialDate: LocalDate, names: List<String>,
    padding: PaddingValues, saving: Boolean, onClose: () -> Unit,
    header: @Composable ColumnScope.() -> Unit = {}, sharedDraft: VoiceDraft? = null,
    onDraft: ((VoiceDraft) -> Unit)? = null,
    onSave: (() -> Pair<ScheduleDraft, LocalDate>) -> Unit) {
    var localDraft by rememberLocalDraft { medicationDraft(existing, initialDate) }
    val draft = sharedDraft ?: localDraft
    fun change(value: VoiceDraft) { if (onDraft != null) onDraft(value) else localDraft = value }
    fun update(field: VoiceField, value: String) = change(draft.withValue(field, value))
    val name = draft.value(VoiceField.NAME)
    val repeat = draft.value(VoiceField.REPEAT)
    val days = draft.value(VoiceField.DAYS).split(',').mapNotNull(String::toIntOrNull).toSet()
    val start = runCatching { LocalDate.parse(draft.value(VoiceField.DATE), dateFormat) }.getOrDefault(LocalDate.now())
    val end = draft.value(VoiceField.END_DATE)
    val slots = draft.doses()
    BackHandler { if (!saving) onClose() }
    ScreenColumn(padding, enabled = !saving) {
        header()
        Heading(if (existing == null) R.string.new_medication else R.string.edit_schedule)
        Input(name, { update(VoiceField.NAME, it) }, R.string.medication_name)
        val suggestions = names.filter { it != name && it.contains(name, ignoreCase = true) }.take(5)
        if (suggestions.isNotEmpty()) {
            Text(stringResource(R.string.previous_names))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    SuggestionChip({ update(VoiceField.NAME, suggestion) }, { Text(suggestion) }, Modifier.heightIn(min = 56.dp), enabled = !saving)
                }
            }
        }
        Text(stringResource(R.string.repeat))
        Choices(Repeat.entries, Repeat.entries.find { it.name == repeat }, { stringResource(repeatResource(it)) }) {
            change(draft.withValue(VoiceField.REPEAT, it.name).let { d -> if (it == Repeat.ONCE) d.withValue(VoiceField.END_DATE, "") else d })
        }
        if (repeat == Repeat.WEEKLY.name) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(day.value in days,
                        { update(VoiceField.DAYS, (if (day.value in days) days - day.value else days + day.value).sorted().joinToString(",")) },
                        { Text(day.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru"))) }, Modifier.heightIn(min = 56.dp), enabled = !saving)
                }
            }
        }
        DateButton(if (existing == null) R.string.start_date else R.string.change_from, start) { update(VoiceField.DATE, it.format(dateFormat)) }
        if (existing != null) Text(stringResource(R.string.revision_hint))
        if (repeat != Repeat.ONCE.name) {
            Choices(listOf(false, true), end.isNotBlank(), { stringResource(if (it) R.string.with_end else R.string.no_end) }) {
                update(VoiceField.END_DATE, if (it) start.format(dateFormat) else "")
            }
            if (end.isNotBlank()) DateButton(R.string.end_date, runCatching { LocalDate.parse(end, dateFormat) }.getOrDefault(start)) {
                update(VoiceField.END_DATE, it.format(dateFormat))
            }
        }
        slots.forEachIndexed { index, slot ->
            key(slot.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.dose_number, index + 1), style = MaterialTheme.typography.titleMedium)
                        fun changeSlot(changed: VoiceDose) = change(draft.withSlots(slots.toMutableList().also { it[index] = changed }))
                        TimeButton(runCatching { LocalTime.parse(slot.time) }.getOrDefault(LocalTime.of(9, 0))) {
                            changeSlot(slot.copy(time = it.toString()))
                        }
                        Input(slot.amount, { changeSlot(slot.copy(amount = it)) }, R.string.dose, KeyboardType.Decimal)
                        Choices(DoseUnit.entries, DoseUnit.entries.find { it.name == slot.unit }, { stringResource(unitResource(it)) }) {
                            changeSlot(slot.copy(unit = it.name))
                        }
                        if (slots.size > 1) ActionButton(R.string.remove_time) { change(draft.withSlots(slots.filterIndexed { i, _ -> i != index })) }
                    }
                }
            }
        }
        ActionButton(R.string.add_time) { change(draft.withSlots(slots + VoiceDose(time = "18:00"))) }
        ActionButton(R.string.save, Modifier.fillMaxWidth(), !saving) {
            onSave { VoiceParser.medication(draft.withSlots(slots), LocalDate.now()) to start }
        }
        ActionButton(R.string.cancel, enabled = !saving, onClick = onClose)
    }
}

internal fun repeatResource(repeat: Repeat) = when (repeat) {
    Repeat.ONCE -> R.string.repeat_once
    Repeat.DAILY -> R.string.repeat_daily
    Repeat.WEEKLY -> R.string.repeat_weekly
}

@Composable
internal fun OccurrenceForm(item: Occurrence, saving: Boolean, padding: PaddingValues,
    onClose: () -> Unit, onSave: (() -> Occurrence) -> Unit) {
    var name by rememberSaveable { mutableStateOf(item.name) }
    var time by rememberSaveable { mutableStateOf(item.time.toString()) }
    var dose by rememberSaveable { mutableStateOf(item.dose.text) }
    var unit by rememberSaveable { mutableStateOf(item.dose.unit.name) }
    BackHandler { if (!saving) onClose() }
    ScreenColumn(padding, enabled = !saving) {
        Heading(R.string.edit_occurrence)
        Text(item.date.format(dateFormat))
        Input(name, { name = it }, R.string.medication_name)
        TimeButton(LocalTime.parse(time)) { time = it.toString() }
        Input(dose, { dose = it }, R.string.dose, KeyboardType.Decimal)
        Choices(DoseUnit.entries, DoseUnit.valueOf(unit), { stringResource(unitResource(it)) }) { unit = it.name }
        ActionButton(R.string.save, Modifier.fillMaxWidth(), !saving) {
            onSave { item.copy(name = name.trim(), time = LocalTime.parse(time), dose = Dose.parse(dose, DoseUnit.valueOf(unit))) }
        }
        ActionButton(R.string.cancel, enabled = !saving, onClick = onClose)
    }
}
