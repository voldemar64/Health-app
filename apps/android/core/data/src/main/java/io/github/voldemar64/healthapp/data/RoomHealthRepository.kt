package io.github.voldemar64.healthapp.data

import androidx.room.withTransaction
import io.github.voldemar64.healthapp.domain.*
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class RoomHealthRepository(
    private val database: HealthDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : HealthRepository {
    private val dao = database.dao()
    override val data: Flow<HealthData> = database.invalidationTracker
        .createFlow("pressures", "schedules", "occurrence_edits", "intakes")
        .map { database.withTransaction { snapshot() } }

    private suspend fun snapshot() = HealthData(
        dao.pressures().map { it.model() }, dao.schedules().map { it.model() },
        dao.exceptions().map { it.model() }, dao.intakes().map { it.model() },
    )

    override suspend fun savePressure(measurement: PressureMeasurement) {
        validatePressureWrite(measurement)
        checkInput(!measurement.measuredAt.isAfter(clock.instant()), InputError.FUTURE)
        dao.pressure(PressureRow(measurement.id, measurement.systolic, measurement.diastolic,
            measurement.pulse, measurement.measuredAt.toString(), measurement.recordedZoneId, measurement.note))
    }

    override suspend fun deletePressure(id: String) = dao.deletePressure(id)

    override suspend fun saveSchedule(draft: ScheduleDraft, seriesId: String?, from: LocalDate?) {
        database.withTransaction {
            val now = LocalDateTime.ofInstant(clock.instant(), zone())
            val effectiveDate = from ?: draft.startDate
            checkInput(effectiveDate >= now.toLocalDate(), InputError.DATES)
            val cutoff = if (seriesId == null) effectiveDate.atStartOfDay() else maxOf(effectiveDate.atStartOfDay(), now)
            val id = seriesId ?: newId()
            val state = snapshot()
            if (seriesId != null) {
                checkInput(state.schedules.any { it.seriesId == id }, InputError.STALE)
                state.schedules.filter { it.seriesId == id }.forEach { revision ->
                    when {
                        revision.from >= cutoff -> dao.deleteSchedule(revision.id)
                        revision.until?.let { it > cutoff } != false ->
                            dao.schedule(revision.copy(until = cutoff).row())
                    }
                }
                // New series settings replace future single-occurrence edits; elapsed edits stay intact.
                state.exceptions.filter { it.seriesId == id && it.localDateTime >= cutoff }
                    .forEach { dao.deleteException(it.key) }
            }
            dao.schedule(ScheduleRevision(seriesId = id, from = cutoff, draft = draft).row())
        }
    }

    override suspend fun editOccurrence(original: Occurrence, replacement: Occurrence) {
        database.withTransaction {
            val state = snapshot()
            val current = occurrences(state, original.date, original.date).find { it.key == original.key }
            checkInput(current == original && state.intakes.none { it.occurrence.key == original.key }, InputError.STALE)
            checkInput(original.instant(zone()) > clock.instant() && replacement.instant(zone()) > clock.instant(), InputError.FUTURE)
            checkInput(replacement.key == original.key && replacement.revisionId == original.revisionId, InputError.STALE)
            checkInput(replacement.name.isNotBlank() && replacement.name.length <= 120, InputError.NAME)
            checkInput(occurrences(state, original.date, original.date).none {
                it.key != original.key && it.seriesId == original.seriesId && it.time == replacement.time
            }, InputError.TIMES)
            dao.exception(replacement.row())
        }
    }

    override suspend fun mark(occurrence: Occurrence, status: IntakeStatus, takenAt: Instant?) {
        database.withTransaction {
            val state = snapshot()
            checkInput(occurrences(state, occurrence.date, occurrence.date).any { it == occurrence }, InputError.STALE)
            checkInput(occurrence.date <= clock.instant().atZone(zone()).toLocalDate(), InputError.FUTURE)
            checkInput(status != IntakeStatus.TAKEN || takenAt != null && takenAt <= clock.instant(), InputError.FUTURE)
            val old = state.intakes.find { it.occurrence.key == occurrence.key }
            dao.intake(IntakeRow(occurrence.key, occurrence.seriesId, occurrence.revisionId,
                occurrence.slotId, occurrence.date.toString(), occurrence.time.toString(), occurrence.name,
                occurrence.dose.text, occurrence.dose.unit.name, status.name,
                (old?.plannedAt ?: occurrence.instant(zone())).toString(), old?.recordedZoneId ?: zone().id,
                if (status == IntakeStatus.TAKEN) takenAt.toString() else null))
        }
    }

    override suspend fun markTakenNow(occurrence: Occurrence) {
        database.withTransaction {
            val now = clock.instant()
            val currentZone = zone()
            checkInput(occurrence.date == now.atZone(currentZone).toLocalDate(), InputError.FUTURE)
            val state = snapshot()
            checkInput(occurrences(state, occurrence.date, occurrence.date).any { it == occurrence }, InputError.STALE)
            val old = state.intakes.find { it.occurrence.key == occurrence.key }
            if (old?.status == IntakeStatus.TAKEN) return@withTransaction
            dao.intake(IntakeRow(occurrence.key, occurrence.seriesId, occurrence.revisionId,
                occurrence.slotId, occurrence.date.toString(), occurrence.time.toString(), occurrence.name,
                occurrence.dose.text, occurrence.dose.unit.name, IntakeStatus.TAKEN.name,
                (old?.plannedAt ?: occurrence.instant(currentZone)).toString(),
                old?.recordedZoneId ?: currentZone.id, now.toString()))
        }
    }

    override suspend fun unmark(key: String) {
        database.withTransaction {
            val old = dao.intakes().find { it.key == key } ?: return@withTransaction
            removeMark(old)
        }
    }

    override suspend fun unmarkToday(key: String) {
        database.withTransaction {
            val today = clock.instant().atZone(zone()).toLocalDate()
            val old = dao.intakes().find { it.key == key } ?: return@withTransaction
            checkInput(LocalDate.parse(old.date) == today, InputError.FUTURE)
            removeMark(old)
        }
    }

    private suspend fun removeMark(old: IntakeRow) {
        // Preserve the displayed snapshot even if its series was edited after the mark.
        dao.exception(old.model().occurrence.row())
        dao.deleteIntake(old.key)
    }
}

private fun PressureRow.model() = PressureMeasurement(id, systolic, diastolic, pulse,
    Instant.parse(measuredAt), zoneId, note)

private fun ScheduleRow.model(): ScheduleRevision {
    val slots = JSONArray(slotsJson)
    return ScheduleRevision(id, seriesId, LocalDateTime.parse(effectiveFrom),
        effectiveUntil?.let(LocalDateTime::parse), ScheduleDraft(name, Repeat.valueOf(repeat),
            DayOfWeek.entries.filter { weekdayMask and (1 shl it.ordinal) != 0 }.toSet(),
            LocalDate.parse(startDate), endDate?.let(LocalDate::parse),
            (0 until slots.length()).map { index ->
                val slot = slots.getJSONObject(index)
                DoseTime(slot.getString("id"), LocalTime.parse(slot.getString("time")),
                    Dose.parse(slot.getString("amount"), DoseUnit.valueOf(slot.getString("unit"))))
            }))
}

private fun ScheduleRevision.row() = ScheduleRow(id, seriesId, from.toString(), until?.toString(),
    draft.name.trim(), draft.repeat.name, draft.weekdays.fold(0) { mask, day -> mask or (1 shl day.ordinal) },
    draft.startDate.toString(), draft.endDate?.toString(), JSONArray().apply {
        draft.slots.forEach { slot -> put(JSONObject().apply {
            put("id", slot.id); put("time", slot.time.toString())
            put("amount", slot.dose.text); put("unit", slot.dose.unit.name)
        }) }
    }.toString())

private fun OccurrenceRow.model() = Occurrence(seriesId, revisionId, slotId, LocalDate.parse(date),
    LocalTime.parse(time), name, Dose.parse(amount, DoseUnit.valueOf(unit)))
private fun Occurrence.row() = OccurrenceRow(key, seriesId, revisionId, slotId, date.toString(),
    time.toString(), name.trim(), dose.text, dose.unit.name)
private fun IntakeRow.model() = IntakeRecord(Occurrence(seriesId, revisionId, slotId,
    LocalDate.parse(date), LocalTime.parse(time), name, Dose.parse(amount, DoseUnit.valueOf(unit))),
    IntakeStatus.valueOf(status), Instant.parse(plannedAt), zoneId, takenAt?.let(Instant::parse))
