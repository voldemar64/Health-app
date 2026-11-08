package io.github.voldemar64.healthapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MedicationScreen(state: HealthState, model: HealthViewModel, padding: PaddingValues) {
    var mode by rememberSaveable { mutableStateOf("day") }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    var itemKey by rememberSaveable { mutableStateOf<String?>(null) }
    val date = LocalDate.parse(dateText)
    val today = LocalDate.now()
    val dayItems = occurrences(state.data, date, date)
    val selectedItem = dayItems.find { it.key == itemKey }
    val latest = state.data.schedules.groupBy { it.seriesId }.values.map { it.maxBy { r -> r.from } }.sortedBy { it.draft.name }
    when {
        form == "new" || form?.startsWith("series:") == true -> {
            val seriesId = form?.removePrefix("series:")?.takeIf { form != "new" }
            val revision = latest.find { it.seriesId == seriesId }
            if (seriesId == null || revision != null) key(form) {
                MedicationForm(revision, date, state.data.schedules.map { it.draft.name }.distinct(),
                    padding, state.saving, { form = null }) { create ->
                    model.save(onSuccess = { form = null }) {
                        val (draft, from) = create()
                        saveSchedule(draft, seriesId, from)
                    }
                }
            }
            return
        }
        form == "single" && selectedItem != null -> {
            key(itemKey) { OccurrenceForm(selectedItem, state.saving, padding, { form = null }) { create ->
                model.save(onSuccess = { form = null }) { editOccurrence(selectedItem, create()) }
            } }
            return
        }
        form == "taken" && selectedItem != null -> {
            key(itemKey) { TakenForm(selectedItem, state.data.intakes.find { it.occurrence.key == itemKey }?.takenAt,
                state.saving, padding, { form = null }) { instant ->
                model.save(onSuccess = { form = null }) { mark(selectedItem, IntakeStatus.TAKEN, instant) }
            } }
            return
        }
    }
    val start = when (mode) {
        "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        "month" -> date.withDayOfMonth(1)
        else -> date
    }
    val end = when (mode) {
        "week" -> start.plusDays(6)
        "month" -> start.with(TemporalAdjusters.lastDayOfMonth())
        else -> date
    }
    val counts = occurrences(state.data, start, end).groupingBy { it.date }.eachCount()
    ScreenColumn(padding) {
        Heading(R.string.medications_title)
        ActionButton(R.string.add_medication, Modifier.fillMaxWidth(), state.loaded) { form = "new" }
        if (!state.loaded) Text(stringResource(R.string.loading))
        else if (state.data.schedules.isEmpty()) EmptyState(R.string.medications_empty_title, R.string.medications_empty_body)
        Choices(listOf("day", "week", "month"), mode, { stringResource(when (it) {
            "day" -> R.string.today; "week" -> R.string.week; else -> R.string.month
        }) }) {
            mode = it
            if (it == "day") dateText = today.toString()
        }
        DateButton(R.string.selected_date, date) { dateText = it.toString() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(R.string.previous_period) {
                dateText = when (mode) { "week" -> date.minusWeeks(1); "month" -> date.minusMonths(1); else -> date.minusDays(1) }.toString()
            }
            ActionButton(R.string.next_period) {
                dateText = when (mode) { "week" -> date.plusWeeks(1); "month" -> date.plusMonths(1); else -> date.plusDays(1) }.toString()
            }
        }
        if (mode != "day") {
            Text(stringResource(R.string.date_range, start.format(dateFormat), end.format(dateFormat)))
            val formatter = remember { DateTimeFormatter.ofPattern("EEE, d", Locale.forLanguageTag("ru")) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var current = start
                while (!current.isAfter(end)) {
                    val day = current
                    FilterChip(day == date, { dateText = day.toString() },
                        { Text(stringResource(R.string.calendar_day, day.format(formatter), counts[day] ?: 0)) },
                        Modifier.heightIn(min = 56.dp))
                    current = current.plusDays(1)
                }
            }
        }
        Text(date.format(dateFormat), style = MaterialTheme.typography.titleMedium)
        if (dayItems.isEmpty()) Text(stringResource(R.string.no_intakes_day))
        dayItems.forEach { item ->
            val record = state.data.intakes.find { it.occurrence.key == item.key }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.intake_summary, item.time.format(timeFormat),
                        item.dose.text.replace('.', ','), stringResource(unitResource(item.dose.unit))))
                    Text(stringResource(when (record?.status) {
                        IntakeStatus.TAKEN -> R.string.taken
                        IntakeStatus.SKIPPED -> R.string.skipped
                        null -> if (item.instant(ZoneId.systemDefault()) <= Instant.now()) R.string.unmarked else R.string.planned
                    }))
                    record?.takenAt?.let { instant ->
                        val local = instant.atZone(ZoneId.systemDefault())
                        Text(stringResource(R.string.taken_at, local.format(dateFormat), local.format(timeFormat)))
                    }
                    if (date <= today) {
                        ActionButton(if (record?.status == IntakeStatus.TAKEN) R.string.change_actual_time else R.string.taken,
                            enabled = !state.saving) { itemKey = item.key; form = "taken" }
                        ActionButton(R.string.skipped, enabled = !state.saving) { model.save { mark(item, IntakeStatus.SKIPPED, null) } }
                    }
                    if (record != null) ActionButton(R.string.unmark, enabled = !state.saving) { model.save { unmark(item.key) } }
                    if (record == null && item.instant(ZoneId.systemDefault()) > Instant.now()) {
                        ActionButton(R.string.edit_occurrence) { itemKey = item.key; form = "single" }
                    }
                    ActionButton(R.string.edit_schedule) { form = "series:${item.seriesId}" }
                }
            }
        }
        if (latest.isNotEmpty()) {
            Heading(R.string.all_schedules)
            latest.forEach { revision ->
                OutlinedButton({ form = "series:${revision.seriesId}" }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.schedule_name, revision.draft.name))
                }
            }
        }
    }
}
