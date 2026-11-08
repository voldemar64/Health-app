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

private data class DoseDraft(val id: String, val time: String, val amount: String, val unit: String)
private val doseDraftSaver = listSaver<List<DoseDraft>, String>(
    save = { slots -> slots.flatMap { listOf(it.id, it.time, it.amount, it.unit) } },
    restore = { values -> values.chunked(4).map { DoseDraft(it[0], it[1], it[2], it[3]) } },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MedicationForm(existing: ScheduleRevision?, initialDate: LocalDate, names: List<String>,
    padding: PaddingValues, saving: Boolean, onClose: () -> Unit,
    header: @Composable ColumnScope.() -> Unit = {},
    onSave: (() -> Pair<ScheduleDraft, LocalDate>) -> Unit) {
    val draft = existing?.draft
    var name by rememberSaveable { mutableStateOf(draft?.name ?: "") }
    var repeat by rememberSaveable { mutableStateOf(draft?.repeat?.name ?: Repeat.DAILY.name) }
    var mask by rememberSaveable { mutableIntStateOf(draft?.weekdays?.fold(0) { m, d -> m or (1 shl d.ordinal) } ?: 0) }
    var start by rememberSaveable { mutableStateOf(maxOf(initialDate, LocalDate.now()).toString()) }
    var hasEnd by rememberSaveable { mutableStateOf(draft?.endDate != null) }
    var end by rememberSaveable { mutableStateOf(maxOf(draft?.endDate ?: initialDate, LocalDate.parse(start)).toString()) }
    var slots by rememberSaveable(stateSaver = doseDraftSaver) { mutableStateOf(draft?.slots?.map {
        DoseDraft(it.id, it.time.toString(), it.dose.text, it.dose.unit.name)
    } ?: listOf(DoseDraft(newId(), "09:00", "1", "TABLET"))) }
    BackHandler { if (!saving) onClose() }
    ScreenColumn(padding) {
        header()
        Heading(if (existing == null) R.string.new_medication else R.string.edit_schedule)
        Input(name, { name = it }, R.string.medication_name)
        val suggestions = names.filter { it != name && it.contains(name, ignoreCase = true) }.take(5)
        if (suggestions.isNotEmpty()) {
            Text(stringResource(R.string.previous_names))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    SuggestionChip({ name = suggestion }, { Text(suggestion) }, Modifier.heightIn(min = 56.dp))
                }
            }
        }
        Text(stringResource(R.string.repeat))
        Choices(Repeat.entries, Repeat.valueOf(repeat), { stringResource(repeatResource(it)) }) { repeat = it.name }
        if (repeat == Repeat.WEEKLY.name) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(mask and (1 shl day.ordinal) != 0,
                        { mask = mask xor (1 shl day.ordinal) },
                        { Text(day.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru"))) },
                        Modifier.heightIn(min = 56.dp))
                }
            }
        }
        DateButton(if (existing == null) R.string.start_date else R.string.change_from, LocalDate.parse(start)) { start = it.toString() }
        if (existing != null) Text(stringResource(R.string.revision_hint))
        if (repeat != Repeat.ONCE.name) {
            Choices(listOf(false, true), hasEnd, { stringResource(if (it) R.string.with_end else R.string.no_end) }) { hasEnd = it }
            if (hasEnd) DateButton(R.string.end_date, LocalDate.parse(end)) { end = it.toString() }
        }
        slots.forEachIndexed { index, slot ->
            key(slot.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.dose_number, index + 1), style = MaterialTheme.typography.titleMedium)
                        fun update(changed: DoseDraft) {
                            slots = slots.toMutableList().also { it[index] = changed }
                        }
                        TimeButton(LocalTime.parse(slot.time)) { update(slot.copy(time = it.toString())) }
                        Input(slot.amount, { update(slot.copy(amount = it)) }, R.string.dose, KeyboardType.Decimal)
                        Choices(DoseUnit.entries, DoseUnit.valueOf(slot.unit), { stringResource(unitResource(it)) }) { update(slot.copy(unit = it.name)) }
                        if (slots.size > 1) ActionButton(R.string.remove_time) { slots = slots.filterIndexed { i, _ -> i != index } }
                    }
                }
            }
        }
        ActionButton(R.string.add_time) { slots = slots + DoseDraft(newId(), "18:00", "1", "TABLET") }
        ActionButton(R.string.save, Modifier.fillMaxWidth(), !saving) {
            onSave {
                val date = LocalDate.parse(start)
                ScheduleDraft(name.trim(), Repeat.valueOf(repeat),
                    DayOfWeek.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet(), date,
                    if (hasEnd && repeat != Repeat.ONCE.name) LocalDate.parse(end) else null,
                    slots.map { slot ->
                        DoseTime(slot.id, LocalTime.parse(slot.time), Dose.parse(slot.amount, DoseUnit.valueOf(slot.unit)))
                    }) to date
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
    ScreenColumn(padding) {
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

@Composable
internal fun TakenForm(item: Occurrence, original: Instant?, saving: Boolean, padding: PaddingValues,
    onClose: () -> Unit, onSave: (Instant) -> Unit) {
    val initial = remember { (original ?: Instant.now()).atZone(ZoneId.systemDefault()) }
    var date by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(initial.toLocalTime().withSecond(0).withNano(0).toString()) }
    BackHandler { if (!saving) onClose() }
    ScreenColumn(padding) {
        Heading(R.string.actual_time)
        Text(item.name)
        DateButton(R.string.date, LocalDate.parse(date)) { date = it.toString() }
        TimeButton(LocalTime.parse(time)) { time = it.toString() }
        ActionButton(R.string.confirm_taken, Modifier.fillMaxWidth(), !saving) {
            onSave(LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant())
        }
        ActionButton(R.string.cancel, enabled = !saving, onClick = onClose)
    }
}
