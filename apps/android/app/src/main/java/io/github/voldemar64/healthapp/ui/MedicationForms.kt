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
    header: @Composable ColumnScope.() -> Unit = {}, sharedDraft: MedicationFormDraft? = null,
    onDraft: ((MedicationFormDraft) -> Unit)? = null, existingCard: UserMedication? = null,
    onSave: (() -> Triple<ScheduleDraft, LocalDate, MedicationCardInput?>) -> Unit) {
    var localDraft by rememberMedicationFormDraft(existing, initialDate, existingCard)
    val draft = sharedDraft ?: localDraft
    fun change(value: MedicationFormDraft) { if (onDraft != null) onDraft(value) else localDraft = value }
    val start = runCatching { LocalDate.parse(draft.start, dateFormat) }.getOrDefault(LocalDate.now())
    val slots = draft.slots
    BackHandler { if (!saving) onClose() }
    ScreenColumn(padding, enabled = !saving) {
        header()
        Heading(if (existing == null) R.string.new_medication else R.string.edit_schedule)
        Input(draft.name, { change(draft.copy(name = it)) }, R.string.medication_name)
        if (existing == null && existingCard == null) {
            Input(draft.form, { change(draft.copy(form = it)) }, R.string.medication_form)
            Input(draft.strength, { change(draft.copy(strength = it)) }, R.string.medication_strength)
            Text(stringResource(R.string.medication_strength_unknown))
            Input(draft.stock, { change(draft.copy(stock = it)) }, R.string.medication_initial_stock, KeyboardType.Decimal)
        }
        val suggestions = names.filter { it != draft.name && it.contains(draft.name, ignoreCase = true) }.take(5)
        if (suggestions.isNotEmpty()) {
            Text(stringResource(R.string.previous_names))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    SuggestionChip({ change(draft.copy(name = suggestion)) }, { Text(suggestion) }, Modifier.heightIn(min = 56.dp), enabled = !saving)
                }
            }
        }
        Text(stringResource(R.string.repeat))
        Choices(Repeat.entries, draft.repeat, { stringResource(repeatResource(it)) }) {
            change(draft.copy(repeat = it, end = if (it == Repeat.ONCE) "" else draft.end))
        }
        if (draft.repeat == Repeat.WEEKLY) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(day in draft.weekdays,
                        { change(draft.copy(weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day)) },
                        { Text(day.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru"))) }, Modifier.heightIn(min = 56.dp), enabled = !saving)
                }
            }
        }
        DateButton(if (existing == null) R.string.start_date else R.string.change_from, start) {
            change(draft.copy(start = it.format(dateFormat)))
        }
        if (existing != null) Text(stringResource(R.string.revision_hint))
        if (draft.repeat != Repeat.ONCE) {
            Choices(listOf(false, true), draft.end.isNotBlank(), { stringResource(if (it) R.string.with_end else R.string.no_end) }) {
                change(draft.copy(end = if (it) start.format(dateFormat) else ""))
            }
            if (draft.end.isNotBlank()) DateButton(R.string.end_date, runCatching { LocalDate.parse(draft.end, dateFormat) }.getOrDefault(start)) {
                change(draft.copy(end = it.format(dateFormat)))
            }
        }
        slots.forEachIndexed { index, slot ->
            key(slot.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.dose_number, index + 1), style = MaterialTheme.typography.titleMedium)
                        fun changeSlot(changed: MedicationSlotDraft) = change(draft.copy(slots = slots.toMutableList().also { it[index] = changed }))
                        TimeButton(runCatching { LocalTime.parse(slot.time) }.getOrDefault(LocalTime.of(9, 0))) {
                            changeSlot(slot.copy(time = it.toString()))
                        }
                        Input(slot.amount, { changeSlot(slot.copy(amount = it)) }, R.string.dose, KeyboardType.Decimal)
                        Choices(DoseUnit.entries, slot.unit, { stringResource(unitResource(it)) }) {
                            changeSlot(slot.copy(unit = it))
                        }
                        if (slots.size > 1) ActionButton(R.string.remove_time) {
                            change(draft.copy(slots = slots.filterIndexed { i, _ -> i != index }))
                        }
                    }
                }
            }
        }
        ActionButton(R.string.add_time) { change(draft.copy(slots = slots + MedicationSlotDraft(newId(), "18:00", "1", slots.first().unit))) }
        ActionButton(R.string.save, Modifier.fillMaxWidth(), !saving) {
            onSave {
                val schedule = draft.schedule()
                val card = if (existing == null && existingCard == null) MedicationCardInput(schedule.name, draft.form.trim(),
                    draft.strength.trim().takeUnless { it.isBlank() || it.contains("не знаю", true) || it.contains("неизвест", true) },
                    slots.first().unit, draft.stock.takeIf(String::isNotBlank)?.let(::parseStockAmount)) else null
                Triple(schedule, start, card)
            }
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
