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
import kotlinx.coroutines.flow.StateFlow

fun newId(): String = UUID.randomUUID().toString()
const val LEGACY_PROFILE_ID = "legacy-profile"
data class UserProfile(val id: String, val name: String, val createdAt: Instant)
enum class InputSource { UNKNOWN, MANUAL, VOICE }

enum class InputError { PRESSURE, PULSE, DOSE, NAME, DATES, TIMES, WEEKDAYS, FUTURE, STALE, MEDICATION }
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
    val userId: String = LEGACY_PROFILE_ID,
    val inputSource: InputSource = InputSource.UNKNOWN,
) {
    init {
        // These are input-format bounds, not diagnostic ranges.
        checkInput(systolic in 1..999 && diastolic in 1..999, InputError.PRESSURE)
        checkInput(pulse == null || pulse in 1..999, InputError.PULSE)
    }
}

enum class DoseUnit { TABLET, CAPSULE, MG, G, OTHER }
data class UserMedication(
    val id: String, val userId: String, val name: String, val form: String,
    val strength: String?, val unit: DoseUnit, val active: Boolean,
    val initialStock: BigDecimal?,
)
data class MedicationCardInput(
    val name: String, val form: String, val strength: String?,
    val unit: DoseUnit, val initialStock: BigDecimal? = null,
) {
    init {
        checkInput(name.isNotBlank() && name.length <= 120 && form.isNotBlank(), InputError.MEDICATION)
        checkInput(initialStock == null || initialStock.signum() >= 0 && initialStock.precision() <= 18 &&
            initialStock.scale() <= 6, InputError.DOSE)
    }
}
fun parseStockAmount(text: String): BigDecimal {
    val normalized = text.trim().replace(',', '.')
    checkInput(normalized.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")), InputError.DOSE)
    return normalized.toBigDecimal()
}
fun validatePressureWrite(measurement: PressureMeasurement) {
    checkInput(measurement.systolic in 1..999 && measurement.diastolic in 1..999, InputError.PRESSURE)
}

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
    val userId: String = LEGACY_PROFILE_ID,
    val medicationId: String = seriesId,
)

enum class IntakeStatus { PENDING, POSTPONED, TAKEN, SKIPPED, UNCONFIRMED }
data class Occurrence(
    val seriesId: String,
    val revisionId: String,
    val slotId: String,
    val date: LocalDate,
    val time: LocalTime,
    val name: String,
    val dose: Dose,
    val userId: String = LEGACY_PROFILE_ID,
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
    val strength: String? = null,
)
enum class ReminderType { INTAKE, ONE_OFF, GENERAL, EXPIRY, STOCK_LOW, STOCK_ZERO, STOCK_OK }
enum class ReminderStatus { ACTIVE, FIRED, DONE, CANCELLED, UNCONFIRMED }
enum class ReminderAction { TAKEN, SKIP, POSTPONE }
data class Reminder(
    val id: String, val userId: String, val intakeKey: String?, val type: ReminderType,
    val triggerAt: Instant, val status: ReminderStatus, val requestCode: Int,
    val title: String, val amount: BigDecimal? = null, val unit: DoseUnit? = null,
    val medicationId: String? = null,
    val elapsedAtMillis: Long? = null, val bootCount: Int? = null,
)
data class HealthData(
    val pressures: List<PressureMeasurement> = emptyList(),
    val schedules: List<ScheduleRevision> = emptyList(),
    val exceptions: List<Occurrence> = emptyList(),
    val intakes: List<IntakeRecord> = emptyList(),
    val medications: List<UserMedication> = emptyList(),
    val stockMovements: List<StockMovement> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
)

enum class StockMovementType { INITIAL, TOPUP, DEBIT, RECONCILE, REVERSAL }
data class StockMovement(
    val id: String, val userId: String, val medicationId: String,
    val type: StockMovementType, val amount: BigDecimal, val occurredAt: Instant,
    val intakeKey: String? = null, val reversesId: String? = null,
)
data class StockBalance(val amount: BigDecimal?, val reliable: Boolean) {
    val displayAmount: BigDecimal? get() = amount?.takeIf { reliable && it.signum() >= 0 }
}

/** A reconciliation is an observed balance. Earlier corrections remain in history only. */
fun stockBalance(movements: List<StockMovement>): StockBalance {
    val ordered = movements.sortedBy(StockMovement::occurredAt)
    val baseline = ordered.indexOfLast { it.type == StockMovementType.INITIAL || it.type == StockMovementType.RECONCILE }
    if (baseline < 0) return StockBalance(null, false)
    var amount = ordered[baseline].amount
    var reliable = true
    ordered.drop(baseline + 1).forEach { movement ->
        when (movement.type) {
            StockMovementType.TOPUP -> amount += movement.amount
            StockMovementType.DEBIT -> amount -= movement.amount
            StockMovementType.REVERSAL -> {
                val original = ordered.find { it.id == movement.reversesId }
                if (original != null && ordered.indexOf(original) > baseline) amount += movement.amount
            }
            else -> Unit
        }
        if (amount.signum() < 0) reliable = false
    }
    return StockBalance(amount, reliable)
}

fun stockRunoutDate(data: HealthData, medicationId: String, now: Instant, zone: ZoneId): LocalDate? {
    val today = now.atZone(zone).toLocalDate()
    val card = data.medications.find { it.id == medicationId } ?: return null
    if (card.unit !in setOf(DoseUnit.TABLET, DoseUnit.CAPSULE)) return null
    var balance = stockBalance(data.stockMovements.filter { it.medicationId == medicationId }).displayAmount ?: return null
    if (balance.signum() == 0) return today
    val seriesIds = data.schedules.filter { it.medicationId == medicationId }.map { it.seriesId }.toSet()
    val taken = data.intakes.filter { it.status == IntakeStatus.TAKEN }.map { it.occurrence.key }.toSet()
    return occurrences(data, today, today.plusYears(1)).firstOrNull { occurrence ->
        if (occurrence.seriesId !in seriesIds || occurrence.dose.unit != card.unit ||
            occurrence.key in taken || occurrence.instant(zone) <= now) false
        else {
            balance -= occurrence.dose.amount
            balance.signum() <= 0
        }
    }?.date
}

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
                            slot.time, draft.name, slot.dose, revision.userId)
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
    val reminderChanges: Flow<Unit>
    fun observeProfile(id: String): Flow<HealthData>
    fun observeProfile(id: String, start: LocalDate, end: LocalDate): Flow<HealthData>
    val profiles: Flow<List<UserProfile>>
    val activeProfileId: StateFlow<String>
    suspend fun createProfile(name: String): UserProfile
    suspend fun renameProfile(id: String, name: String)
    suspend fun selectProfile(id: String)
    suspend fun deleteProfile(id: String)
    val data: Flow<HealthData>
    suspend fun savePressure(measurement: PressureMeasurement)
    suspend fun deletePressure(id: String)
    suspend fun saveSchedule(draft: ScheduleDraft, seriesId: String? = null, from: LocalDate? = null)
    suspend fun saveMedicationSchedule(draft: ScheduleDraft, card: MedicationCardInput)
    suspend fun createMedication(card: MedicationCardInput): UserMedication
    suspend fun saveScheduleForMedication(medicationId: String, draft: ScheduleDraft)
    suspend fun editOccurrence(original: Occurrence, replacement: Occurrence)
    suspend fun mark(occurrence: Occurrence, status: IntakeStatus, takenAt: Instant?)
    /** Marks a current local-day occurrence once, using the repository clock. */
    suspend fun markTakenNow(occurrence: Occurrence)
    suspend fun markAllNow(items: List<Occurrence>)
    suspend fun unmark(key: String)
    /** Cancels a mark only while its occurrence belongs to the current local day. */
    suspend fun unmarkToday(key: String)
    suspend fun addStock(medicationId: String, amount: BigDecimal)
    suspend fun reconcileStock(medicationId: String, amount: BigDecimal)
    suspend fun postpone(occurrence: Occurrence, until: Instant)
    suspend fun expireUnconfirmed(now: Instant)
    suspend fun createReminder(title: String, at: Instant, medicationId: String? = null,
        amount: BigDecimal? = null): Reminder
    suspend fun createTimerReminder(title: String, delaySeconds: Long, medicationId: String? = null,
        amount: BigDecimal? = null): Reminder
    suspend fun cancelReminder(id: String)
    suspend fun refreshReminders(now: Instant): List<Reminder>
    suspend fun reminder(id: String): Reminder?
    suspend fun fireReminder(id: String, now: Instant): Reminder?
    suspend fun actOnReminder(id: String, action: ReminderAction, now: Instant): Boolean
}
