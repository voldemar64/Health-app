package io.github.voldemar64.healthapp.domain

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

fun newId(): String = UUID.randomUUID().toString()

enum class InputError { PRESSURE, PULSE, DOSE, NAME, DATES, TIMES, WEEKDAYS, FUTURE, STALE }
class InvalidInput(val code: InputError) : IllegalArgumentException(code.name)
fun checkInput(condition: Boolean, code: InputError) {
    if (!condition) throw InvalidInput(code)
}

data class PressureMeasurement(
    val id: String = newId(),
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val measuredAt: Instant,
    val recordedZoneId: String,
    val note: String = "",
) {
    init {
        // These are input-format bounds, not diagnostic ranges.
        checkInput(systolic in 1..999 && diastolic in 1..999 && systolic > diastolic, InputError.PRESSURE)
        checkInput(pulse == null || pulse in 1..999, InputError.PULSE)
    }
}

enum class DoseUnit { TABLET, MG, G }
data class Dose(val amount: BigDecimal, val unit: DoseUnit) {
    init { checkInput(amount.signum() > 0 && amount.precision() <= 18 && amount.scale() <= 6, InputError.DOSE) }
    val text: String get() = amount.stripTrailingZeros().toPlainString()
    companion object {
        fun parse(text: String, unit: DoseUnit): Dose {
            val normalized = text.trim().replace(',', '.')
            checkInput(normalized.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")), InputError.DOSE)
            return Dose(normalized.toBigDecimal(), unit)
        }
    }
}

enum class Repeat { ONCE, DAILY, WEEKLY }
data class DoseTime(val id: String = newId(), val time: LocalTime, val dose: Dose)
data class ScheduleDraft(
    val name: String,
    val repeat: Repeat,
    val weekdays: Set<DayOfWeek>,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val slots: List<DoseTime>,
) {
    init {
        checkInput(name.isNotBlank() && name.length <= 120, InputError.NAME)
        checkInput(endDate == null || !endDate.isBefore(startDate), InputError.DATES)
        checkInput(slots.isNotEmpty() && slots.map { it.time }.distinct().size == slots.size &&
            slots.map { it.id }.distinct().size == slots.size, InputError.TIMES)
        checkInput(repeat != Repeat.WEEKLY || weekdays.isNotEmpty(), InputError.WEEKDAYS)
    }
}

data class ScheduleRevision(
    val id: String = newId(),
    val seriesId: String = newId(),
    val from: LocalDateTime,
    val until: LocalDateTime? = null,
    val draft: ScheduleDraft,
)

enum class IntakeStatus { TAKEN, SKIPPED }
data class Occurrence(
    val seriesId: String,
    val revisionId: String,
    val slotId: String,
    val date: LocalDate,
    val time: LocalTime,
    val name: String,
    val dose: Dose,
) {
    val key: String get() = "$seriesId/$date/$slotId"
    val localDateTime: LocalDateTime get() = date.atTime(time)
    // java.time shifts gaps forward and selects the earlier offset in overlaps.
    fun instant(zone: ZoneId): Instant = localDateTime.atZone(zone).toInstant()
}
data class IntakeRecord(
    val occurrence: Occurrence,
    val status: IntakeStatus,
    val plannedAt: Instant,
    val recordedZoneId: String,
    val takenAt: Instant?,
)
data class HealthData(
    val pressures: List<PressureMeasurement> = emptyList(),
    val schedules: List<ScheduleRevision> = emptyList(),
    val exceptions: List<Occurrence> = emptyList(),
    val intakes: List<IntakeRecord> = emptyList(),
)

data class PressureSummary(val count: Int, val systolic: Double, val diastolic: Double)
fun pressureInRange(data: List<PressureMeasurement>, start: LocalDate, end: LocalDate, zone: ZoneId) =
    data.filter { it.measuredAt.atZone(zone).toLocalDate() in start..end }.sortedBy { it.measuredAt }
fun summarize(data: List<PressureMeasurement>): PressureSummary? = data.takeIf { it.isNotEmpty() }?.let {
    PressureSummary(it.size, it.map { p -> p.systolic }.average(), it.map { p -> p.diastolic }.average())
}

fun occurrences(data: HealthData, start: LocalDate, end: LocalDate): List<Occurrence> {
    require(!end.isBefore(start))
    val result = linkedMapOf<String, Occurrence>()
    data.schedules.sortedBy { it.from }.forEach { revision ->
        val draft = revision.draft
        var date = maxOf(start, draft.startDate, revision.from.toLocalDate())
        val last = minOf(end, draft.endDate ?: end, revision.until?.toLocalDate() ?: end,
            if (draft.repeat == Repeat.ONCE) draft.startDate else end)
        while (!date.isAfter(last)) {
            if (draft.repeat != Repeat.WEEKLY || date.dayOfWeek in draft.weekdays) {
                draft.slots.forEach { slot ->
                    val local = date.atTime(slot.time)
                    if (local >= revision.from && (revision.until == null || local < revision.until)) {
                        val occurrence = Occurrence(revision.seriesId, revision.id, slot.id, date,
                            slot.time, draft.name, slot.dose)
                        // A time change today must not create a second dose for an already elapsed slot.
                        result.putIfAbsent(occurrence.key, occurrence)
                    }
                }
            }
            date = date.plusDays(1)
        }
    }
    data.exceptions.filter { it.date in start..end }
        .forEach { result[it.key] = it }
    // Facts survive edits, including deletion of their former slot from a future revision.
    data.intakes.filter { it.occurrence.date in start..end }
        .forEach { result[it.occurrence.key] = it.occurrence }
    return result.values.sortedWith(compareBy({ it.date }, { it.time }, { it.name }, { it.key }))
}

interface HealthRepository {
    val data: Flow<HealthData>
    suspend fun savePressure(measurement: PressureMeasurement)
    suspend fun deletePressure(id: String)
    suspend fun saveSchedule(draft: ScheduleDraft, seriesId: String? = null, from: LocalDate? = null)
    suspend fun editOccurrence(original: Occurrence, replacement: Occurrence)
    suspend fun mark(occurrence: Occurrence, status: IntakeStatus, takenAt: Instant?)
    suspend fun unmark(key: String)
}
