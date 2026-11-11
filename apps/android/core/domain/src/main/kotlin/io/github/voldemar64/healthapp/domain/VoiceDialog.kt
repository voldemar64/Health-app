package io.github.voldemar64.healthapp.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DialogPhase { COMMAND, FIELD, CONFIRM, CORRECTION, CHOICE, COMMIT, PAUSED, CANCELLED, NAVIGATE, READ }
data class DialogState(
    val phase: DialogPhase = DialogPhase.COMMAND,
    val draft: VoiceDraft? = null,
    val field: VoiceField? = null,
    val choices: List<Occurrence> = emptyList(),
    val selected: Occurrence? = null,
    val error: String? = null,
    val destination: String? = null,
    val readDate: LocalDate? = null,
    val pressure: PressureMeasurement? = null,
    val schedule: ScheduleDraft? = null,
)

/** No IO. Entries require confirmation; an unambiguous intake is marked immediately. */
class VoiceDialog {
    var state = DialogState()
        private set

    fun seed(draft: VoiceDraft) { state = DialogState(draft = draft) }

    fun edit(field: VoiceField, value: String) {
        val draft = state.draft ?: return
        state = DialogState(DialogPhase.CORRECTION, draft.copy(values = draft.values + (field to value)))
    }

    fun review(data: HealthData, now: Instant, zone: ZoneId) {
        val draft = state.draft ?: return
        state = DialogState(draft = draft)
        validate(data, now, zone)
    }

    fun accept(raw: String, data: HealthData, now: Instant, zone: ZoneId) {
        if (state.phase == DialogPhase.COMMIT || state.phase == DialogPhase.CANCELLED) return
        val text = RussianNumbers.normalize(raw).trim(' ', '.', ',')
        val today = now.atZone(zone).toLocalDate()
        if (text in setOf("отмена", "отменить", "стоп", "завершить", "закончить")) {
            state = DialogState(DialogPhase.CANCELLED); return
        }
        if (state.phase == DialogPhase.CONFIRM) {
            when (text) {
                "сохранить", "сохрани", "подтверждаю", "подтвердить", "да сохранить", "да" ->
                    state = state.copy(phase = DialogPhase.COMMIT, error = null)
                "нет" -> state = state.copy(phase = DialogPhase.PAUSED, error = null)
                "исправить", "исправь", "изменить" -> state = state.copy(phase = DialogPhase.CORRECTION, error = null)
                else -> state = state.copy(error = "CONFIRM")
            }
            return
        }
        if (state.phase == DialogPhase.CHOICE) {
            if (text in setOf("исправить", "исправь", "изменить")) {
                state = state.copy(phase = DialogPhase.CORRECTION, error = null)
                return
            }
            val index = text.removePrefix("номер ").toIntOrNull()?.minus(1)
            val chosen = index?.let { state.choices.getOrNull(it) }
            if (chosen == null) state = state.copy(error = "CHOICE")
            else state = state.copy(phase = DialogPhase.COMMIT, selected = chosen, error = null)
            return
        }
        if (state.phase == DialogPhase.CORRECTION) {
            val field = when {
                text.contains("верхн") -> VoiceField.SYSTOLIC
                text.contains("нижн") -> VoiceField.DIASTOLIC
                text.contains("пульс") -> VoiceField.PULSE
                text.contains("назван") -> VoiceField.NAME
                text.contains("колич") || text.contains("доз") -> VoiceField.AMOUNT
                text.contains("единиц") -> VoiceField.UNIT
                text.contains("врем") -> VoiceField.TIMES
                text.contains("повтор") -> VoiceField.REPEAT
                text.contains("дн") || text.contains("день") -> VoiceField.DAYS
                text.contains("окончан") -> VoiceField.END_DATE
                text.contains("статус") || text.contains("отметк") -> VoiceField.STATUS
                text.contains("дат") -> VoiceField.DATE
                else -> null
            }
            if (field == null || field !in editableFields(state.draft!!.kind)) state = state.copy(error = "FIELD")
            else state = state.copy(phase = DialogPhase.FIELD, field = field, error = null, selected = null)
            return
        }
        // Explicit navigation/read/mark commands remain available from either entry form.
        val explicit = VoiceParser.parse(raw, today)
        when (explicit) {
            is VoiceCommand.Navigate -> { state = state.copy(phase = DialogPhase.NAVIGATE, destination = explicit.destination); return }
            is VoiceCommand.ReadSchedule -> { state = state.copy(phase = DialogPhase.READ, readDate = explicit.date); return }
            is VoiceCommand.Draft -> if (explicit.value.kind == VoiceKind.MARK) state = DialogState(draft = explicit.value)
            else -> Unit
        }
        if (text.contains("пропус") || text.contains("пропущ")) { state = state.copy(error = "UNKNOWN"); return }
        if (state.draft != null) {
            try {
                state = state.copy(draft = VoiceParser.fill(state.draft!!, raw, today, state.field), error = null)
            } catch (failure: InvalidInput) {
                state = state.copy(phase = DialogPhase.FIELD, field = state.field ?: VoiceField.TIMES, error = failure.code.name)
                return
            }
            if (state.field != null && state.draft!!.values[state.field].isNullOrBlank()) {
                state = state.copy(phase = DialogPhase.FIELD, error = "FORMAT")
                return
            }
        } else when (val command = VoiceParser.parse(raw, today)) {
            is VoiceCommand.Draft -> state = DialogState(draft = command.value)
            is VoiceCommand.Navigate -> { state = DialogState(DialogPhase.NAVIGATE, destination = command.destination); return }
            is VoiceCommand.ReadSchedule -> { state = DialogState(DialogPhase.READ, readDate = command.date); return }
            VoiceCommand.Unknown -> { state = DialogState(error = "UNKNOWN"); return }
        }
        validate(data, now, zone)
    }

    private fun validate(data: HealthData, now: Instant, zone: ZoneId) {
        val draft = state.draft ?: return
        draft.missing()?.let { state = state.copy(phase = DialogPhase.FIELD, field = it); return }
        try {
            when (draft.kind) {
                VoiceKind.PRESSURE -> {
                    val local = now.atZone(zone)
                    val date = draft.values[VoiceField.DATE]?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it, dateFormat) } ?: local.toLocalDate()
                    val time = draft.values[VoiceField.TIMES]?.takeIf(String::isNotBlank)?.let { LocalTime.parse(it) } ?: local.toLocalTime().withSecond(0).withNano(0)
                    val measurement = PressureMeasurement(draft.id,
                        draft.values[VoiceField.SYSTOLIC]?.toIntOrNull() ?: 0,
                        draft.values[VoiceField.DIASTOLIC]?.toIntOrNull() ?: 0,
                        draft.values[VoiceField.PULSE]?.toIntOrNull() ?: 0,
                        date.atTime(time).atZone(zone).toInstant(), zone.id)
                    checkInput(measurement.measuredAt <= now, InputError.FUTURE)
                    state = state.copy(phase = DialogPhase.CONFIRM, field = null, pressure = measurement)
                }
                VoiceKind.MEDICATION -> {
                    val schedule = VoiceParser.medication(draft, now.atZone(zone).toLocalDate())
                    checkInput(schedule.startDate >= now.atZone(zone).toLocalDate(), InputError.DATES)
                    state = state.copy(phase = DialogPhase.CONFIRM, field = null, schedule = schedule)
                }
                VoiceKind.MARK -> {
                    val date = draft.values[VoiceField.DATE]?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it, dateFormat) } ?: now.atZone(zone).toLocalDate()
                    checkInput(date == now.atZone(zone).toLocalDate(), InputError.FUTURE)
                    val name = RussianNumbers.normalize(draft.values[VoiceField.NAME].orEmpty())
                    val times = draft.values[VoiceField.TIMES].orEmpty().split(';').map { it.trim() }
                    val matches = occurrences(data, date, date).filter {
                        RussianNumbers.normalize(it.name) == name && (times == listOf("") || it.time.toString() in times)
                    }
                    state = when (matches.size) {
                        0 -> state.copy(phase = DialogPhase.FIELD, field = VoiceField.NAME, error = "NOT_FOUND")
                        1 -> state.copy(phase = DialogPhase.COMMIT, field = null, selected = matches.single())
                        else -> state.copy(phase = DialogPhase.CHOICE, field = null, choices = matches)
                    }
                }
            }
        } catch (failure: IllegalArgumentException) {
            state = state.copy(phase = DialogPhase.CORRECTION, error = (failure as? InvalidInput)?.code?.name ?: "FORMAT")
        } catch (_: java.time.DateTimeException) {
            state = state.copy(phase = DialogPhase.CORRECTION, error = "FORMAT")
        }
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
        fun editableFields(kind: VoiceKind) = when (kind) {
            VoiceKind.PRESSURE -> listOf(VoiceField.SYSTOLIC, VoiceField.DIASTOLIC, VoiceField.PULSE, VoiceField.DATE, VoiceField.TIMES)
            VoiceKind.MEDICATION -> listOf(VoiceField.NAME, VoiceField.AMOUNT, VoiceField.UNIT, VoiceField.TIMES, VoiceField.REPEAT, VoiceField.DAYS, VoiceField.DATE, VoiceField.END_DATE)
            VoiceKind.MARK -> listOf(VoiceField.NAME, VoiceField.DATE, VoiceField.TIMES, VoiceField.STATUS)
        }
    }
}
