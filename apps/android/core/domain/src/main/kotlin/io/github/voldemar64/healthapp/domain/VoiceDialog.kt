package io.github.voldemar64.healthapp.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigDecimal

enum class DialogPhase { COMMAND, FIELD, CONFIRM, CORRECTION, CHOICE, MEDICATION_CHOICE, COMMIT, PAUSED, CANCELLED, NAVIGATE, READ }
data class DialogState(
    val phase: DialogPhase = DialogPhase.COMMAND,
    val draft: VoiceDraft? = null,
    val field: VoiceField? = null,
    val choices: List<Occurrence> = emptyList(),
    val selected: Occurrence? = null,
    val selectedMany: List<Occurrence> = emptyList(),
    val error: String? = null,
    val destination: String? = null,
    val readDate: LocalDate? = null,
    val pressure: PressureMeasurement? = null,
    val schedule: ScheduleDraft? = null,
    val card: MedicationCardInput? = null,
    val timerReminder: VoiceCommand.TimerReminder? = null,
    val atReminder: VoiceCommand.AtReminder? = null,
    val stockChange: VoiceCommand.StockChange? = null,
    val medicationChoices: List<UserMedication> = emptyList(),
    val selectedMedicationId: String? = null,
    val timerAmount: BigDecimal? = null,
    val postponeCommand: VoiceCommand.Postpone? = null,
)

/** No IO. Every write requires confirmation. */
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
            if (text.startsWith("исправь ") || text.startsWith("измени ")) {
                state = state.copy(phase = DialogPhase.CORRECTION, error = null)
                accept(raw, data, now, zone)
                return
            }
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
            else state = state.copy(phase = DialogPhase.CONFIRM, selected = chosen, error = null)
            return
        }
        if (state.phase == DialogPhase.MEDICATION_CHOICE) {
            val index = text.removePrefix("номер ").toIntOrNull()?.minus(1)
            val chosen = index?.let { state.medicationChoices.getOrNull(it) }
            state = if (chosen == null) state.copy(error = "CHOICE") else
                state.copy(phase = DialogPhase.CONFIRM, selectedMedicationId = chosen.id, error = null)
            return
        }
        if (state.phase == DialogPhase.CORRECTION) {
            val field = when {
                text.contains("верхн") -> VoiceField.SYSTOLIC
                text.contains("нижн") -> VoiceField.DIASTOLIC
                text.contains("пульс") -> VoiceField.PULSE
                text.contains("назван") -> VoiceField.NAME
                text.contains("форм") -> VoiceField.FORM
                text.contains("дозировк") || text.contains("сила") -> VoiceField.STRENGTH
                text.contains("остат") || text.contains("запас") -> VoiceField.STOCK
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
            else {
                state = state.copy(phase = DialogPhase.FIELD, field = field, error = null, selected = null)
                if (text.matches(Regex(".*\\d+.*")) && field in setOf(VoiceField.SYSTOLIC, VoiceField.DIASTOLIC, VoiceField.PULSE)) {
                    accept(raw, data, now, zone)
                }
            }
            return
        }
        // Explicit navigation/read/mark commands remain available from either entry form.
        val explicit = VoiceParser.parse(raw, today)
        when (explicit) {
            is VoiceCommand.Postpone -> {
                val matches = occurrences(data, today, today).filter {
                    RussianNumbers.normalize(it.name) == RussianNumbers.normalize(explicit.name) &&
                    data.intakes.none { record -> record.occurrence.key == it.key &&
                        record.status in setOf(IntakeStatus.TAKEN, IntakeStatus.SKIPPED) }
                }
                state = when (matches.size) {
                    0 -> DialogState(error = "NOT_FOUND")
                    1 -> DialogState(phase = DialogPhase.CONFIRM, postponeCommand = explicit,
                        selected = matches.single())
                    else -> DialogState(phase = DialogPhase.CHOICE, postponeCommand = explicit,
                        choices = matches)
                }
                return
            }
            is VoiceCommand.StockChange -> {
                val matches = data.medications.filter { RussianNumbers.normalize(it.name) ==
                    RussianNumbers.normalize(explicit.name) }
                state = when (matches.size) {
                    0 -> DialogState(error = "NOT_FOUND")
                    1 -> DialogState(phase = DialogPhase.CONFIRM, stockChange = explicit,
                        selectedMedicationId = matches.single().id)
                    else -> DialogState(phase = DialogPhase.MEDICATION_CHOICE, stockChange = explicit,
                        medicationChoices = matches)
                }
                return
            }
            is VoiceCommand.TimerReminder -> {
                val dose = Regex("(\\d+(?:[.,]\\d+)?)\\s*(таблет[а-я]*|капсул[а-я]*)")
                    .find(explicit.title)
                val unit = if (dose?.groupValues?.get(2)?.startsWith("таблет") == true) DoseUnit.TABLET
                    else if (dose != null) DoseUnit.CAPSULE else null
                val cards = if (unit == null) emptyList() else data.medications.filter {
                    it.unit == unit && RussianNumbers.normalize(explicit.title)
                        .contains(RussianNumbers.normalize(it.name))
                }
                state = when {
                    dose != null && cards.isEmpty() -> DialogState(error = "NOT_FOUND")
                    cards.size > 1 -> DialogState(phase = DialogPhase.MEDICATION_CHOICE,
                        timerReminder = explicit, timerAmount = dose?.groupValues?.get(1)?.replace(',', '.')?.toBigDecimal(),
                        medicationChoices = cards)
                    else -> DialogState(phase = DialogPhase.CONFIRM, timerReminder = explicit,
                        selectedMedicationId = cards.singleOrNull()?.id,
                        timerAmount = dose?.groupValues?.get(1)?.replace(',', '.')?.toBigDecimal())
                }
                return
            }
            is VoiceCommand.AtReminder -> {
                if (explicit.at.atZone(zone).toInstant() <= now) {
                    state = DialogState(error = "FUTURE")
                    return
                }
                val dose = Regex("(\\d+(?:[.,]\\d+)?)\\s*(таблет[а-я]*|капсул[а-я]*)")
                    .find(explicit.title)
                val unit = if (dose?.groupValues?.get(2)?.startsWith("таблет") == true) DoseUnit.TABLET
                    else if (dose != null) DoseUnit.CAPSULE else null
                val cards = if (unit == null) emptyList() else data.medications.filter {
                    it.unit == unit && RussianNumbers.normalize(explicit.title)
                        .contains(RussianNumbers.normalize(it.name))
                }
                state = when {
                    dose != null && cards.isEmpty() -> DialogState(error = "NOT_FOUND")
                    cards.size > 1 -> DialogState(phase = DialogPhase.MEDICATION_CHOICE,
                        atReminder = explicit, timerAmount = dose?.groupValues?.get(1)?.replace(',', '.')?.toBigDecimal(),
                        medicationChoices = cards)
                    else -> DialogState(phase = DialogPhase.CONFIRM, atReminder = explicit,
                        selectedMedicationId = cards.singleOrNull()?.id,
                        timerAmount = dose?.groupValues?.get(1)?.replace(',', '.')?.toBigDecimal())
                }
                return
            }
            VoiceCommand.MarkAll -> {
                val completed = data.intakes.filter { it.status in setOf(IntakeStatus.TAKEN, IntakeStatus.SKIPPED) }
                    .map { it.occurrence.key }.toSet()
                val items = occurrences(data, today, today).filter { it.key !in completed }
                state = if (items.isEmpty()) DialogState(error = "NOT_FOUND")
                    else DialogState(phase = DialogPhase.CONFIRM,
                        draft = VoiceDraft(VoiceKind.MARK, mapOf(VoiceField.STATUS to "TAKEN")),
                        selectedMany = items)
                return
            }
            is VoiceCommand.Navigate -> { state = state.copy(phase = DialogPhase.NAVIGATE, destination = explicit.destination); return }
            is VoiceCommand.ReadSchedule -> { state = state.copy(phase = DialogPhase.READ, readDate = explicit.date); return }
            is VoiceCommand.Draft -> if (explicit.value.kind == VoiceKind.MARK) state = DialogState(draft = explicit.value)
            else -> Unit
        }
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
            is VoiceCommand.TimerReminder -> error("Handled above")
            is VoiceCommand.AtReminder -> error("Handled above")
            is VoiceCommand.StockChange -> error("Handled above")
            is VoiceCommand.Postpone -> error("Handled above")
            VoiceCommand.MarkAll -> error("Handled above")
            VoiceCommand.Unknown -> { state = DialogState(error = "UNKNOWN"); return }
        }
        validate(data, now, zone)
    }

    private fun validate(data: HealthData, now: Instant, zone: ZoneId) {
        val draft = state.draft ?: return
        draft.missing()?.let { state = state.copy(phase = DialogPhase.FIELD, field = it); return }
        if (draft.kind == VoiceKind.MEDICATION) {
            listOf(VoiceField.FORM, VoiceField.STRENGTH, VoiceField.STOCK).firstOrNull {
                draft.values[it].isNullOrBlank()
            }?.let { state = state.copy(phase = DialogPhase.FIELD, field = it); return }
        }
        try {
            when (draft.kind) {
                VoiceKind.PRESSURE -> {
                    val local = now.atZone(zone)
                    val pulseText = draft.values[VoiceField.PULSE].orEmpty()
                    checkInput(pulseText.isBlank() || pulseText.toIntOrNull() != null, InputError.PULSE)
                    val date = draft.values[VoiceField.DATE]?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it, dateFormat) } ?: local.toLocalDate()
                    val time = draft.values[VoiceField.TIMES]?.takeIf(String::isNotBlank)?.let { LocalTime.parse(it) } ?: local.toLocalTime().withSecond(0).withNano(0)
                    val measurement = PressureMeasurement(draft.id,
                        draft.values[VoiceField.SYSTOLIC]?.toIntOrNull() ?: 0,
                        draft.values[VoiceField.DIASTOLIC]?.toIntOrNull() ?: 0,
                        draft.values[VoiceField.PULSE]?.takeIf(String::isNotBlank)?.toIntOrNull(),
                        date.atTime(time).atZone(zone).toInstant(), zone.id, inputSource = InputSource.VOICE)
                    checkInput(measurement.measuredAt <= now, InputError.FUTURE)
                    state = state.copy(phase = DialogPhase.CONFIRM, field = null, pressure = measurement)
                }
                VoiceKind.MEDICATION -> {
                    val schedule = VoiceParser.medication(draft, now.atZone(zone).toLocalDate())
                    checkInput(schedule.startDate >= now.atZone(zone).toLocalDate(), InputError.DATES)
                    val form = draft.values[VoiceField.FORM].orEmpty().let {
                        if (it.contains("не знаю", ignoreCase = true)) "Не указана" else it
                    }
                    val strength = draft.values[VoiceField.STRENGTH]?.takeUnless {
                        it == "UNKNOWN" || it.contains("не знаю", ignoreCase = true) ||
                            it.contains("неизвест", ignoreCase = true)
                    }
                    val stock = draft.values[VoiceField.STOCK]?.takeUnless { it == "UNKNOWN" }
                        ?.let(::parseStockAmount)
                    val card = MedicationCardInput(schedule.name, form, strength,
                        schedule.slots.first().dose.unit, stock)
                    checkInput(schedule.slots.all { it.dose.unit == card.unit }, InputError.MEDICATION)
                    state = state.copy(phase = DialogPhase.CONFIRM, field = null, schedule = schedule,
                        card = card)
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
                        1 -> state.copy(phase = DialogPhase.CONFIRM, field = null, selected = matches.single())
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
            VoiceKind.MEDICATION -> listOf(VoiceField.NAME, VoiceField.FORM, VoiceField.STRENGTH,
                VoiceField.STOCK, VoiceField.AMOUNT, VoiceField.UNIT, VoiceField.TIMES, VoiceField.REPEAT,
                VoiceField.DAYS, VoiceField.DATE, VoiceField.END_DATE)
            VoiceKind.MARK -> listOf(VoiceField.NAME, VoiceField.DATE, VoiceField.TIMES, VoiceField.STATUS)
        }
    }
}
