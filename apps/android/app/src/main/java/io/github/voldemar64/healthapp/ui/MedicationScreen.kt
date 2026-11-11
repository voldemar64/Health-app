package io.github.voldemar64.healthapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
internal fun MedicationScreen(state: HealthState, model: HealthViewModel, padding: PaddingValues) {
    var mode by rememberSaveable { mutableStateOf("week") }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var page by rememberSaveable { mutableStateOf("calendar") }
    var series by rememberSaveable { mutableStateOf<String?>(null) }
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    var itemKey by rememberSaveable { mutableStateOf<String?>(null) }
    val date = LocalDate.parse(dateText)
    val today = state.now.atZone(ZoneId.systemDefault()).toLocalDate()
    val dayItems = occurrences(state.data, date, date)
    val latest = state.data.schedules.groupBy { it.seriesId }.values.map { it.maxBy { r -> r.from } }.sortedBy { it.draft.name }
    val revision = latest.find { it.seriesId == series }
    val selected = dayItems.find { it.key == itemKey }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val markedText = stringResource(R.string.intake_marked)
    val undoText = stringResource(R.string.undo)
    if (page != "calendar") BackHandler { if (page == "details") page = "list" else page = "calendar" }
    if (form == "series" && revision != null) {
        MedicationForm(revision, maxOf(date, today), latest.map { it.draft.name }, padding, state.saving,
            { form = null }) { create ->
            model.save(onSuccess = { form = null }) { val (draft, from) = create(); saveSchedule(draft, revision.seriesId, from) }
        }
        return
    }
    if (form == "single" && selected != null) {
        OccurrenceForm(selected, state.saving, padding, { form = null }) { create ->
            model.save(onSuccess = { form = null }) { editOccurrence(selected, create()) }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        ScreenColumn(padding) {
            when (page) {
                "list" -> {
                    Heading(R.string.all_medications)
                    ActionButton(R.string.back) { page = "calendar" }
                    if (latest.isEmpty()) Text(stringResource(R.string.medications_empty_title))
                    latest.forEach { r ->
                        Card(onClick = { series = r.seriesId; page = "details" }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScheduleSummary(r)
                            }
                        }
                    }
                }
                "details" -> {
                    ActionButton(R.string.back) { page = "list" }
                    if (revision != null) {
                        ScheduleSummary(revision)
                        ActionButton(R.string.edit_schedule) { form = "series" }
                        DateButton(R.string.selected_date, date) { dateText = it.toString() }
                        val items = dayItems.filter { it.seriesId == series }
                        if (items.isEmpty()) Text(stringResource(R.string.no_intakes_day))
                        items.forEach { item ->
                            val record = state.data.intakes.find { it.occurrence.key == item.key }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IntakeSummary(item, record, state.now)
                                    if (date == today && record != null) ActionButton(R.string.unmark, enabled = !state.saving) {
                                        model.save { unmarkToday(item.key) }
                                    }
                                    if (record == null && item.instant(ZoneId.systemDefault()) > state.now) ActionButton(R.string.edit_occurrence) {
                                        itemKey = item.key; form = "single"
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.medications_title), Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge)
                        val description = stringResource(R.string.all_medications)
                        val color = MaterialTheme.colorScheme.onSurface
                        IconButton({ page = "list" }, Modifier.size(56.dp).semantics { contentDescription = description }) {
                            Canvas(Modifier.size(26.dp)) {
                                listOf(0.2f, 0.5f, 0.8f).forEach { y -> drawLine(color, Offset(0f, size.height * y),
                                    Offset(size.width, size.height * y), strokeWidth = 2.dp.toPx()) }
                            }
                        }
                    }
                    if (!state.loaded) Text(stringResource(R.string.loading))
                    else if (latest.isEmpty()) EmptyState(R.string.medications_empty_title, R.string.medications_empty_body)
                    Choices(listOf("week", "month"), mode, { stringResource(if (it == "week") R.string.week else R.string.month) }) { mode = it }
                    MedicationCalendar(mode, date, today, state.data) { dateText = it.toString() }
                    Text(date.format(dateFormat), style = MaterialTheme.typography.titleMedium)
                    if (dayItems.isEmpty()) Text(stringResource(R.string.no_intakes_day))
                    dayItems.forEach { item ->
                        val record = state.data.intakes.find { it.occurrence.key == item.key }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                IntakeSummary(item, record, state.now)
                                if (record?.status == IntakeStatus.TAKEN) ActionButton(R.string.taken_checked, enabled = false) {}
                                else if (date == today) ActionButton(R.string.taken, enabled = !state.saving) {
                                    model.save(onSuccess = {
                                        scope.launch {
                                            snackbar.currentSnackbarData?.dismiss()
                                            if (snackbar.showSnackbar(markedText, undoText, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
                                                model.save { unmarkToday(item.key) }
                                        }
                                    }) { markTakenNow(item) }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(padding))
    }
}

@Composable
private fun ScheduleSummary(revision: ScheduleRevision) {
    val draft = revision.draft
    Text(draft.name, style = MaterialTheme.typography.titleMedium)
    draft.slots.forEach { slot -> Text(stringResource(R.string.intake_summary, slot.time.format(timeFormat),
        slot.dose.text.replace('.', ','), stringResource(unitResource(slot.dose.unit)))) }
    Text(stringResource(repeatResource(draft.repeat)))
    if (draft.repeat == Repeat.WEEKLY) Text(draft.weekdays.sorted().joinToString(", ") {
        it.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("ru"))
    })
    Text(draft.startDate.format(dateFormat) + " — " + (draft.endDate?.format(dateFormat) ?: stringResource(R.string.no_end)))
}

@Composable
private fun IntakeSummary(item: Occurrence, record: IntakeRecord?, now: Instant) {
    Text(item.name, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.intake_summary, item.time.format(timeFormat), item.dose.text.replace('.', ','), stringResource(unitResource(item.dose.unit))))
    if (record?.status != IntakeStatus.TAKEN) Text(stringResource(when (record?.status) {
        IntakeStatus.SKIPPED -> R.string.skipped
        else -> if (item.instant(ZoneId.systemDefault()) <= now) R.string.unmarked else R.string.planned
    }))
    record?.takenAt?.atZone(ZoneId.systemDefault())?.let { Text(stringResource(R.string.taken_at, it.format(dateFormat), it.format(timeFormat))) }
}

@Composable
private fun MedicationCalendar(mode: String, date: LocalDate, today: LocalDate, data: HealthData, onDate: (LocalDate) -> Unit) {
    val period = calendarPeriod(date, month = mode == "month")
    val start = period.start
    val end = period.end
    val daysWithIntakes = remember(data, start, end) { occurrences(data, start, end).map { it.date }.toSet() }
    val ru = Locale.forLanguageTag("ru")
    Text(date.format(DateTimeFormatter.ofPattern("LLLL yyyy", ru)).replaceFirstChar { it.titlecase(ru) }, style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ onDate(if (mode == "week") date.minusWeeks(1) else date.minusMonths(1)) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = "Предыдущий период" }) { Text("‹") }
        TextButton({ onDate(today) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today)) }
        TextButton({ onDate(if (mode == "week") date.plusWeeks(1) else date.plusMonths(1)) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = "Следующий период" }) { Text("›") }
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        period.cells.chunked(7).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { day ->
                    if (day == null) Spacer(Modifier.weight(1f).height(56.dp))
                    else {
                        val label = day.format(DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", ru)) +
                            (if (day == today) ", сегодня" else "") + (if (day in daysWithIntakes) ", есть приёмы" else ", нет приёмов")
                        Surface(onClick = { onDate(day) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                            .semantics { contentDescription = label; selected = day == date }, shape = CircleShape,
                            color = if (day == date) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = if (day == today) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                            Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge)
                                Text(if (day in daysWithIntakes) "•" else " ", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
