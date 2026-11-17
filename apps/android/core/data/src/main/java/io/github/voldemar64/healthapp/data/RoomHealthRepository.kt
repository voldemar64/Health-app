package io.github.voldemar64.healthapp.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import io.github.voldemar64.healthapp.domain.*
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class RoomHealthRepository(
    private val database: HealthDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val context: Context? = null,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : HealthRepository {
    private val dao = database.dao()
    private val selection = context?.getSharedPreferences("profile_selection", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(selection?.getString("selected", null) ?: LEGACY_PROFILE_ID)
    override val activeProfileId: StateFlow<String> = selected
    override val reminderChanges: Flow<Unit> = database.invalidationTracker.createFlow("profiles", "schedules",
        "schedule_slots", "occurrence_edits", "intakes", "reminders", "stock_movements").map { Unit }
    override val profiles: Flow<List<UserProfile>> = database.invalidationTracker.createFlow("profiles")
        .map { database.withTransaction { ensureDefaultProfile(); dao.profiles().map { it.model() } } }
    override val data: Flow<HealthData> = combine(selected,
        database.invalidationTracker.createFlow("profiles", "pressures", "schedules", "schedule_slots",
            "user_medications", "stock_movements", "occurrence_edits", "intakes", "reminders")) { id, _ -> id }
        .map { id -> database.withTransaction { snapshot(id) } }

    override fun observeProfile(id: String): Flow<HealthData> =
        database.invalidationTracker.createFlow("profiles", "pressures", "schedules",
            "schedule_slots", "user_medications", "stock_movements", "occurrence_edits", "intakes",
            "reminders").map {
            database.withTransaction {
                ensureDefaultProfile()
                if (dao.profile(id) == null) HealthData() else snapshot(id)
            }
        }

    override fun observeProfile(id: String, start: LocalDate, end: LocalDate): Flow<HealthData> {
        require(!end.isBefore(start))
        return database.invalidationTracker.createFlow("profiles", "pressures", "schedules",
            "schedule_slots", "user_medications", "stock_movements", "occurrence_edits", "intakes",
            "reminders").map {
            database.withTransaction {
                ensureDefaultProfile()
                if (dao.profile(id) == null) HealthData() else snapshot(id, start, end)
            }
        }
    }

    private suspend fun ensureDefaultProfile() {
        if (dao.profiles().isEmpty()) dao.profile(ProfileRow(LEGACY_PROFILE_ID, "Основной", Instant.EPOCH.toString()))
        if (dao.profile(selected.value) == null) {
            val fallback = dao.profiles().first().id
            selection?.edit()?.putString("selected", fallback)?.apply()
            selected.value = fallback
        }
    }

    override suspend fun createProfile(name: String): UserProfile {
        checkInput(name.trim().isNotEmpty() && name.trim().length <= 120, InputError.NAME)
        val profile = UserProfile(newId(), name.trim(), clock.instant())
        database.withTransaction { ensureDefaultProfile(); dao.profile(ProfileRow(profile.id, profile.name, profile.createdAt.toString())) }
        selectProfile(profile.id)
        return profile
    }

    override suspend fun renameProfile(id: String, name: String) {
        checkInput(name.trim().isNotEmpty() && name.trim().length <= 120, InputError.NAME)
        database.withTransaction {
            checkInput(dao.profile(id) != null, InputError.STALE)
            dao.renameProfile(id, name.trim())
        }
    }

    override suspend fun selectProfile(id: String) {
        database.withTransaction { ensureDefaultProfile(); checkInput(dao.profile(id) != null, InputError.STALE) }
        selection?.edit()?.putString("selected", id)?.apply()
        selected.value = id
    }

    override suspend fun deleteProfile(id: String) {
        val next = database.withTransaction {
            val all = dao.profiles()
            checkInput(all.size > 1 && all.any { it.id == id }, InputError.STALE)
            dao.deleteProfilePressures(id)
            dao.deleteProfileReminders(id)
            dao.deleteProfileStockMovements(id)
            dao.deleteProfileSlots(id)
            dao.deleteProfileSchedules(id)
            dao.deleteProfileMedications(id)
            dao.deleteProfileExceptions(id)
            dao.deleteProfileIntakes(id)
            dao.deleteProfile(id)
            all.first { it.id != id }.id
        }
        if (selected.value == id) selectProfile(next)
    }

    private suspend fun snapshot(id: String = selected.value, start: LocalDate? = null,
        end: LocalDate? = null): HealthData {
        ensureDefaultProfile()
        val slots = dao.slots(id).groupBy { it.revisionId }
        val movements = dao.stockMovements(id).groupBy { it.medicationId }
        val first = start?.toString()
        val last = end?.toString()
        val pressureRows = if (start != null && end != null) dao.pressuresBetween(id,
            start.atStartOfDay(zone()).toInstant().toString(),
            end.plusDays(1).atStartOfDay(zone()).toInstant().toString()) else dao.pressures(id)
        val scheduleRows = if (first != null && last != null) dao.schedulesBetween(id,
            first, last, start.atStartOfDay().toString(), end.plusDays(1).atStartOfDay().toString())
            else dao.schedules(id)
        val exceptionRows = if (first != null && last != null) dao.exceptionsBetween(id, first, last)
            else dao.exceptions(id)
        val intakeRows = if (first != null && last != null) dao.intakesBetween(id, first, last)
            else dao.intakes(id)
        return HealthData(
        pressureRows.map { it.model() }, scheduleRows.map { it.model(slots[it.id].orEmpty()) },
        exceptionRows.map { it.model() }, intakeRows.map { it.model() },
        dao.medications(id).map { it.model(movements[it.id].orEmpty()) },
        movements.values.flatten().map { it.model() },
        dao.profileReminders(id).map { it.model() },
    )
    }

    override suspend fun savePressure(measurement: PressureMeasurement) {
        validatePressureWrite(measurement)
        checkInput(!measurement.measuredAt.isAfter(clock.instant()), InputError.FUTURE)
        database.withTransaction {
            val id = selected.value
            checkInput(dao.pressureOwner(measurement.id)?.let { it == id } != false, InputError.STALE)
            dao.pressure(PressureRow(measurement.id, measurement.systolic, measurement.diastolic,
                measurement.pulse, measurement.measuredAt.toString(), measurement.recordedZoneId, measurement.note, id,
                measurement.inputSource.name))
        }
    }

    override suspend fun deletePressure(id: String) = dao.deletePressure(id, selected.value)

    override suspend fun saveSchedule(draft: ScheduleDraft, seriesId: String?, from: LocalDate?) {
        database.withTransaction { saveScheduleLocked(draft, seriesId, from, null) }
    }

    override suspend fun saveMedicationSchedule(draft: ScheduleDraft, card: MedicationCardInput) {
        checkInput(draft.name.trim() == card.name.trim() && draft.slots.all { it.dose.unit == card.unit }, InputError.MEDICATION)
        database.withTransaction { saveScheduleLocked(draft, null, null, card) }
    }

    override suspend fun createMedication(card: MedicationCardInput): UserMedication = database.withTransaction {
        val id = newId()
        val profileId = selected.value
        dao.medication(UserMedicationRow(id, profileId, card.name.trim(), card.form.trim(), card.strength,
            card.unit.name, true, clock.instant().toString()))
        card.initialStock?.let { amount -> dao.stockMovement(StockMovementRow(newId(), profileId, id,
            "INITIAL", amount.stripTrailingZeros().toPlainString(), clock.instant().toString(), null, null)) }
        dao.medication(id)!!.model(dao.stockMovements(profileId).filter { it.medicationId == id })
    }

    override suspend fun saveScheduleForMedication(medicationId: String, draft: ScheduleDraft) {
        database.withTransaction {
            val card = dao.medication(medicationId)
            checkInput(card?.userId == selected.value && draft.name.trim() == card.name &&
                draft.slots.all { it.dose.unit.name == card.unit }, InputError.MEDICATION)
            saveScheduleLocked(draft, null, null, null, medicationId)
        }
    }

    private suspend fun saveScheduleLocked(draft: ScheduleDraft, seriesId: String?, from: LocalDate?,
        card: MedicationCardInput?, existingMedicationId: String? = null) {
            val now = LocalDateTime.ofInstant(clock.instant(), zone())
            val effectiveDate = from ?: draft.startDate
            checkInput(effectiveDate >= now.toLocalDate(), InputError.DATES)
            val cutoff = if (seriesId == null) effectiveDate.atStartOfDay() else maxOf(effectiveDate.atStartOfDay(), now)
            val id = seriesId ?: newId()
            val state = snapshot()
            val medicationId = existingMedicationId ?: if (card != null) newId()
                else state.schedules.firstOrNull { it.seriesId == id }?.medicationId ?: id
            if (seriesId != null) {
                checkInput(state.schedules.any { it.seriesId == id }, InputError.STALE)
                state.schedules.filter { it.seriesId == id }.forEach { revision ->
                    when {
                        revision.from >= cutoff -> { dao.deleteSlots(revision.id); dao.deleteSchedule(revision.id) }
                        revision.until?.let { it > cutoff } != false ->
                            writeRevision(revision.copy(until = cutoff))
                    }
                }
                // New series settings replace future single-occurrence edits; elapsed edits stay intact.
                state.exceptions.filter { it.seriesId == id && it.localDateTime >= cutoff }
                    .forEach { dao.deleteException(it.key) }
            }
            val existing = dao.medication(medicationId)
            if (existingMedicationId == null) dao.medication(UserMedicationRow(medicationId, selected.value,
                draft.name.trim(), card?.form ?: existing?.form ?: "Не указана", card?.strength ?: existing?.strength,
                card?.unit?.name ?: existing?.unit ?: "OTHER", existing?.active ?: true,
                existing?.createdAt ?: clock.instant().toString()))
            card?.initialStock?.let { amount -> dao.stockMovement(StockMovementRow(newId(), selected.value, medicationId,
                "INITIAL", amount.stripTrailingZeros().toPlainString(), clock.instant().toString(), null, null)) }
            writeRevision(ScheduleRevision(seriesId = id, from = cutoff, draft = draft,
                userId = selected.value, medicationId = medicationId))
    }

    private suspend fun writeRevision(revision: ScheduleRevision) {
        dao.schedule(revision.row())
        dao.deleteSlots(revision.id)
        revision.draft.slots.forEach { slot ->
            dao.slot(ScheduleSlotRow(revision.id, slot.id, slot.time.toString(), slot.dose.text, slot.dose.unit.name))
        }
    }

    override suspend fun editOccurrence(original: Occurrence, replacement: Occurrence) {
        database.withTransaction {
            val state = snapshot()
            val current = occurrences(state, original.date, original.date).find { it.key == original.key }
            checkInput(current == original && state.intakes.none { it.occurrence.key == original.key }, InputError.STALE)
            checkInput(original.instant(zone()) > clock.instant() && replacement.instant(zone()) > clock.instant(), InputError.FUTURE)
            checkInput(replacement.key == original.key && replacement.revisionId == original.revisionId &&
                replacement.userId == original.userId && original.userId == selected.value, InputError.STALE)
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
            checkInput(status == IntakeStatus.TAKEN || status == IntakeStatus.SKIPPED, InputError.STALE)
            checkInput(status != IntakeStatus.TAKEN || takenAt != null && takenAt <= clock.instant(), InputError.FUTURE)
            val old = state.intakes.find { it.occurrence.key == occurrence.key }
            writeIntake(occurrence, status, takenAt, old, state)
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
            writeIntake(occurrence, IntakeStatus.TAKEN, now, old, state)
        }
    }

    override suspend fun markAllNow(items: List<Occurrence>) {
        checkInput(items.isNotEmpty() && items.map { it.key }.distinct().size == items.size, InputError.STALE)
        database.withTransaction {
            val now = clock.instant()
            val today = now.atZone(zone()).toLocalDate()
            val state = snapshot()
            val available = occurrences(state, today, today)
            checkInput(items.all { it.userId == selected.value && it.date == today && it in available },
                InputError.STALE)
            items.forEach { occurrence ->
                val old = state.intakes.find { it.occurrence.key == occurrence.key }
                if (old?.status != IntakeStatus.TAKEN)
                    writeIntake(occurrence, IntakeStatus.TAKEN, now, old, state)
            }
        }
    }

    private suspend fun writeIntake(occurrence: Occurrence, status: IntakeStatus, takenAt: Instant?,
        old: IntakeRecord?, state: HealthData, dueAt: Instant? = null, profileId: String = selected.value) {
        if (old?.status == status && status in setOf(IntakeStatus.TAKEN, IntakeStatus.SKIPPED)) return
        if (old?.status == IntakeStatus.TAKEN) reverseDebit(occurrence.key, state, profileId)
        val planned = dueAt ?: old?.plannedAt ?: occurrence.instant(zone())
        dao.intake(IntakeRow(occurrence.key, occurrence.seriesId, occurrence.revisionId,
            occurrence.slotId, occurrence.date.toString(), occurrence.time.toString(), occurrence.name,
            occurrence.dose.text, occurrence.dose.unit.name, status.name, planned.toString(),
            old?.recordedZoneId ?: zone().id, takenAt?.toString(), profileId,
            old?.strength ?: state.schedules.find { it.seriesId == occurrence.seriesId }?.medicationId?.let { medId ->
                state.medications.find { it.id == medId }?.strength
            }))
        if (status == IntakeStatus.TAKEN) {
            val medicationId = state.schedules.find { it.seriesId == occurrence.seriesId }?.medicationId
            val card = state.medications.find { it.id == medicationId }
            if (card != null && card.unit in setOf(DoseUnit.TABLET, DoseUnit.CAPSULE) &&
                occurrence.dose.unit == card.unit) {
                dao.stockMovement(StockMovementRow(newId(), profileId, card.id, "DEBIT",
                    occurrence.dose.text, (takenAt ?: clock.instant()).toString(), occurrence.key, null))
            }
        }
    }

    private suspend fun reverseDebit(key: String, state: HealthData, profileId: String = selected.value) {
        val original = state.stockMovements.lastOrNull { it.intakeKey == key &&
            it.type == StockMovementType.DEBIT && state.stockMovements.none { reversal ->
                reversal.type == StockMovementType.REVERSAL && reversal.reversesId == it.id
            } } ?: return
        dao.stockMovement(StockMovementRow(newId(), profileId, original.medicationId, "REVERSAL",
            original.amount.toPlainString(), clock.instant().toString(), key, original.id))
    }

    override suspend fun addStock(medicationId: String, amount: BigDecimal) = changeStock(medicationId, amount, "TOPUP")
    override suspend fun reconcileStock(medicationId: String, amount: BigDecimal) = changeStock(medicationId, amount, "RECONCILE")

    private suspend fun changeStock(medicationId: String, amount: BigDecimal, type: String) {
        checkInput(amount.signum() >= (if (type == "TOPUP") 1 else 0) && amount.precision() <= 18 &&
            amount.scale() <= 6, InputError.DOSE)
        database.withTransaction {
            checkInput(dao.medication(medicationId)?.userId == selected.value, InputError.STALE)
            dao.stockMovement(StockMovementRow(newId(), selected.value, medicationId, type,
                amount.stripTrailingZeros().toPlainString(), clock.instant().toString(), null, null))
        }
    }

    override suspend fun postpone(occurrence: Occurrence, until: Instant) {
        database.withTransaction {
            val state = snapshot()
            checkInput(occurrence.userId == selected.value &&
                occurrences(state, occurrence.date, occurrence.date).any { it == occurrence }, InputError.STALE)
            checkInput(until > clock.instant(), InputError.FUTURE)
            val old = state.intakes.find { it.occurrence.key == occurrence.key }
            checkInput(old?.status != IntakeStatus.TAKEN && old?.status != IntakeStatus.SKIPPED, InputError.STALE)
            writeIntake(occurrence, IntakeStatus.POSTPONED, null, old, state, until)
        }
    }

    override suspend fun expireUnconfirmed(now: Instant) {
        database.withTransaction {
            val today = now.atZone(zone()).toLocalDate()
            dao.profiles().forEach { profile ->
                val state = snapshot(profile.id)
                occurrences(state, today.minusDays(2), today).forEach { occurrence ->
                    val old = state.intakes.find { it.occurrence.key == occurrence.key }
                    val due = old?.plannedAt ?: occurrence.instant(zone())
                    if (due.plusSeconds(3600) <= now && old?.status !in setOf(IntakeStatus.TAKEN,
                        IntakeStatus.SKIPPED, IntakeStatus.UNCONFIRMED)) {
                        writeIntake(occurrence, IntakeStatus.UNCONFIRMED, null, old, state,
                            profileId = profile.id)
                    }
                }
            }
        }
    }

    override suspend fun createReminder(title: String, at: Instant, medicationId: String?,
        amount: BigDecimal?): Reminder = insertReminder(title, at, medicationId, amount, null, null)

    override suspend fun createTimerReminder(title: String, delaySeconds: Long,
        medicationId: String?, amount: BigDecimal?): Reminder {
        checkInput(delaySeconds in 1..604800, InputError.TIMES)
        return insertReminder(title, clock.instant().plusSeconds(delaySeconds), medicationId, amount,
            SystemClock.elapsedRealtime() + delaySeconds * 1000, currentBootCount())
    }

    private suspend fun insertReminder(title: String, at: Instant, medicationId: String?,
        amount: BigDecimal?, elapsedAt: Long?, bootCount: Int?): Reminder {
        checkInput(title.trim().isNotEmpty() && title.length <= 120, InputError.NAME)
        checkInput(at > clock.instant(), InputError.FUTURE)
        return database.withTransaction {
            val profileId = selected.value
            val card = medicationId?.let { dao.medication(it) }
            checkInput(medicationId == null || card?.userId == profileId, InputError.STALE)
            checkInput(medicationId == null || amount != null, InputError.DOSE)
            checkInput(amount == null || amount.signum() > 0 && amount.precision() <= 18 && amount.scale() <= 6,
                InputError.DOSE)
            val id = newId()
            val row = ReminderRow(id, profileId, null,
                if (medicationId == null) ReminderType.GENERAL.name else ReminderType.ONE_OFF.name,
                at.toString(), ReminderStatus.ACTIVE.name, requestCode(id, dao.reminders()), title.trim(),
                amount?.stripTrailingZeros()?.toPlainString(), card?.unit, medicationId, elapsedAt, bootCount)
            dao.reminder(row)
            row.model()
        }
    }

    override suspend fun cancelReminder(id: String) {
        database.withTransaction {
            val row = dao.reminder(id) ?: return@withTransaction
            checkInput(row.userId == selected.value, InputError.STALE)
            dao.reminderStatus(id, ReminderStatus.CANCELLED.name)
        }
    }

    override suspend fun reminder(id: String): Reminder? = dao.reminder(id)?.model()

    override suspend fun refreshReminders(now: Instant): List<Reminder> = database.withTransaction {
        ensureDefaultProfile()
        val existing = dao.reminders()
        val byId = existing.associateBy { it.id }
        val wanted = mutableSetOf<String>()
        val today = now.atZone(zone()).toLocalDate()
        dao.profiles().forEach { profile ->
            val state = snapshot(profile.id)
            val series = state.schedules.associateBy { it.seriesId }
            occurrences(state, today.minusDays(1), today.plusDays(7)).forEach { occurrence ->
                val oldIntake = state.intakes.find { it.occurrence.key == occurrence.key }
                val due = oldIntake?.plannedAt ?: occurrence.instant(zone())
                if (oldIntake?.status in setOf(IntakeStatus.TAKEN, IntakeStatus.SKIPPED,
                        IntakeStatus.UNCONFIRMED) || due.plusSeconds(3600) <= now) return@forEach
                val id = "intake:${occurrence.key}"
                wanted += id
                val old = byId[id]
                if (old == null || old.triggerAt != due.toString() || old.status in
                    setOf(ReminderStatus.CANCELLED.name, ReminderStatus.DONE.name)) {
                    val card = state.medications.find { it.id == series[occurrence.seriesId]?.medicationId }
                    dao.reminder(ReminderRow(id, profile.id, occurrence.key, ReminderType.INTAKE.name,
                        due.toString(), ReminderStatus.ACTIVE.name, old?.requestCode ?: requestCode(id, dao.reminders()),
                        occurrence.name, occurrence.dose.text, occurrence.dose.unit.name, card?.id))
                }
            }
            state.medications.forEach { card ->
                val balance = stockBalance(state.stockMovements.filter { it.medicationId == card.id })
                val runout = stockRunoutDate(state, card.id, now, zone())
                val currentAmount = balance.displayAmount
                val level = when {
                    currentAmount == null -> ReminderType.STOCK_OK
                    currentAmount.signum() == 0 -> ReminderType.STOCK_ZERO
                    runout != null && !runout.isAfter(today.plusDays(7)) -> ReminderType.STOCK_LOW
                    else -> ReminderType.STOCK_OK
                }
                val previous = existing.lastOrNull { it.medicationId == card.id && it.type in
                    setOf(ReminderType.STOCK_LOW.name, ReminderType.STOCK_ZERO.name, ReminderType.STOCK_OK.name) }
                if (previous?.type != level.name && (previous != null || level != ReminderType.STOCK_OK)) {
                    if (previous != null && previous.status != ReminderStatus.DONE.name)
                        dao.reminderStatus(previous.id, ReminderStatus.CANCELLED.name)
                    val warningId = newId()
                    dao.reminder(ReminderRow(warningId, profile.id, null, level.name, now.toString(),
                        if (level == ReminderType.STOCK_OK) ReminderStatus.DONE.name else ReminderStatus.ACTIVE.name,
                        requestCode(warningId, dao.reminders()), card.name, medicationId = card.id))
                }
            }
        }
        existing.filter { it.type == ReminderType.INTAKE.name && it.status in
            setOf(ReminderStatus.ACTIVE.name, ReminderStatus.FIRED.name) && it.id !in wanted &&
            Instant.parse(it.triggerAt).plusSeconds(3600) > now }
            .forEach { dao.reminderStatus(it.id, ReminderStatus.CANCELLED.name) }
        dao.reminders().map { it.model() }
    }

    override suspend fun fireReminder(id: String, now: Instant): Reminder? = database.withTransaction {
        val row = dao.reminder(id) ?: return@withTransaction null
        if (row.status != ReminderStatus.ACTIVE.name || !row.isDue(now)) return@withTransaction null
        if (dao.profile(row.userId) == null) return@withTransaction null
        if (row.type == ReminderType.EXPIRY.name) {
            val original = dao.reminder(id.removePrefix("expire:"))
            val state = snapshot(row.userId)
            val occurrence = state.intakes.find { it.occurrence.key == row.intakeKey }?.occurrence
            if (occurrence != null && original?.status == ReminderStatus.FIRED.name) {
                val old = state.intakes.find { it.occurrence.key == row.intakeKey }
                if (old?.status == IntakeStatus.PENDING && old.plannedAt.plusSeconds(3600) <= now)
                    writeIntake(occurrence, IntakeStatus.UNCONFIRMED, null, old, state,
                        profileId = row.userId)
            }
            if (original?.status == ReminderStatus.FIRED.name)
                dao.reminderStatus(original.id, ReminderStatus.UNCONFIRMED.name)
            dao.reminderStatus(id, ReminderStatus.DONE.name)
            return@withTransaction null
        }
        if (row.type in setOf(ReminderType.STOCK_LOW.name, ReminderType.STOCK_ZERO.name)) {
            dao.reminderStatus(id, ReminderStatus.FIRED.name)
            return@withTransaction row.copy(status = ReminderStatus.FIRED.name).model()
        }
        if (row.type in setOf(ReminderType.ONE_OFF.name, ReminderType.GENERAL.name) && row.isExpired(now)) {
            dao.reminderStatus(id, ReminderStatus.UNCONFIRMED.name)
            return@withTransaction null
        }
        if (row.type == ReminderType.INTAKE.name) {
            val state = snapshot(row.userId)
            val occurrence = findReminderOccurrence(state, row) ?: run {
                dao.reminderStatus(id, ReminderStatus.CANCELLED.name)
                return@withTransaction null
            }
            val old = state.intakes.find { it.occurrence.key == row.intakeKey }
            if (row.isExpired(now)) {
                if (old?.status !in setOf(IntakeStatus.TAKEN, IntakeStatus.SKIPPED))
                    writeIntake(occurrence, IntakeStatus.UNCONFIRMED, null, old, state,
                        profileId = row.userId)
                dao.reminderStatus(id, ReminderStatus.UNCONFIRMED.name)
                return@withTransaction null
            }
            if (old?.status in setOf(IntakeStatus.TAKEN, IntakeStatus.SKIPPED, IntakeStatus.UNCONFIRMED)) {
                dao.reminderStatus(id, ReminderStatus.CANCELLED.name)
                return@withTransaction null
            }
            writeIntake(occurrence, IntakeStatus.PENDING, null, old, state,
                profileId = row.userId)
        }
        val expiryId = "expire:$id"
        dao.reminder(ReminderRow(expiryId, row.userId, row.intakeKey, ReminderType.EXPIRY.name,
            Instant.parse(row.triggerAt).plusSeconds(3600).toString(), ReminderStatus.ACTIVE.name,
            requestCode(expiryId, dao.reminders()), row.title,
            elapsedAtMillis = row.elapsedAtMillis?.plus(3600_000), bootCount = row.bootCount))
        dao.reminderStatus(id, ReminderStatus.FIRED.name)
        row.copy(status = ReminderStatus.FIRED.name).model()
    }

    override suspend fun actOnReminder(id: String, action: ReminderAction, now: Instant): Boolean =
        database.withTransaction {
            val row = dao.reminder(id) ?: return@withTransaction false
            if (row.status != ReminderStatus.FIRED.name || dao.profile(row.userId) == null) return@withTransaction false
            if (action == ReminderAction.POSTPONE) {
                val until = now.plusSeconds(900)
                if (row.type == ReminderType.INTAKE.name) {
                    val state = snapshot(row.userId)
                    val occurrence = findReminderOccurrence(state, row) ?: return@withTransaction false
                    val old = state.intakes.find { it.occurrence.key == row.intakeKey }
                    if (old?.status != IntakeStatus.PENDING) return@withTransaction false
                    writeIntake(occurrence, IntakeStatus.POSTPONED, null, old, state, until, row.userId)
                }
                dao.reminder(row.copy(triggerAt = until.toString(), status = ReminderStatus.ACTIVE.name,
                    elapsedAtMillis = SystemClock.elapsedRealtime() + 900_000, bootCount = currentBootCount()))
            } else {
                val state = snapshot(row.userId)
                if (row.type == ReminderType.INTAKE.name) {
                    val occurrence = findReminderOccurrence(state, row) ?: return@withTransaction false
                    val old = state.intakes.find { it.occurrence.key == row.intakeKey }
                    if (old?.status !in setOf(IntakeStatus.PENDING, IntakeStatus.POSTPONED)) return@withTransaction false
                    writeIntake(occurrence, if (action == ReminderAction.TAKEN) IntakeStatus.TAKEN else IntakeStatus.SKIPPED,
                        if (action == ReminderAction.TAKEN) now else null, old, state, profileId = row.userId)
                } else if (row.type == ReminderType.ONE_OFF.name) {
                    val occurrence = Occurrence(row.id, row.id, row.id, now.atZone(zone()).toLocalDate(),
                        now.atZone(zone()).toLocalTime().withSecond(0).withNano(0), row.title,
                        Dose(row.amount!!.toBigDecimal(), DoseUnit.valueOf(row.unit!!)), row.userId)
                    dao.intake(IntakeRow(occurrence.key, occurrence.seriesId, occurrence.revisionId,
                        occurrence.slotId, occurrence.date.toString(), occurrence.time.toString(), occurrence.name,
                        occurrence.dose.text, occurrence.dose.unit.name,
                        if (action == ReminderAction.TAKEN) IntakeStatus.TAKEN.name else IntakeStatus.SKIPPED.name,
                        row.triggerAt, zone().id, if (action == ReminderAction.TAKEN) now.toString() else null,
                        row.userId, state.medications.find { it.id == row.medicationId }?.strength))
                    if (action == ReminderAction.TAKEN) {
                        val card = state.medications.find { it.id == row.medicationId }
                        if (card?.unit in setOf(DoseUnit.TABLET, DoseUnit.CAPSULE))
                            dao.stockMovement(StockMovementRow(newId(), row.userId, card!!.id, "DEBIT",
                                occurrence.dose.text, now.toString(), occurrence.key, null))
                    }
                }
                dao.reminderStatus(id, ReminderStatus.DONE.name)
            }
            dao.reminder("expire:$id")?.let { dao.reminderStatus(it.id, ReminderStatus.CANCELLED.name) }
            true
        }

    private fun findReminderOccurrence(state: HealthData, row: ReminderRow): Occurrence? {
        val date = row.intakeKey?.split('/')?.getOrNull(1)?.let(LocalDate::parse) ?: return null
        val occurrence = occurrences(state, date, date).find {
            it.key == row.intakeKey && it.userId == row.userId
        } ?: return null
        val due = state.intakes.find { it.occurrence.key == row.intakeKey }?.plannedAt ?: occurrence.instant(zone())
        return occurrence.takeIf { due.toString() == row.triggerAt }
    }

    private fun requestCode(id: String, existing: List<ReminderRow>): Int {
        var code = id.hashCode() and Int.MAX_VALUE
        val used = existing.filter { it.id != id }.map { it.requestCode }.toSet()
        while (code in used) code = (code + 1) and Int.MAX_VALUE
        return code
    }

    private fun currentBootCount(): Int? = context?.let {
        runCatching { Settings.Global.getInt(it.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1).takeIf { value -> value >= 0 }
    }

    private fun ReminderRow.isDue(now: Instant): Boolean =
        if (bootCount != null && bootCount == currentBootCount() && elapsedAtMillis != null)
            SystemClock.elapsedRealtime() >= elapsedAtMillis
        else Instant.parse(triggerAt) <= now

    private fun ReminderRow.isExpired(now: Instant): Boolean =
        if (bootCount != null && bootCount == currentBootCount() && elapsedAtMillis != null)
            SystemClock.elapsedRealtime() >= elapsedAtMillis + 3600_000
        else Instant.parse(triggerAt).plusSeconds(3600) <= now

    override suspend fun unmark(key: String) {
        database.withTransaction {
            val old = dao.intakes(selected.value).find { it.key == key } ?: return@withTransaction
            removeMark(old)
        }
    }

    override suspend fun unmarkToday(key: String) {
        database.withTransaction {
            val today = clock.instant().atZone(zone()).toLocalDate()
            val old = dao.intakes(selected.value).find { it.key == key } ?: return@withTransaction
            checkInput(LocalDate.parse(old.date) == today, InputError.FUTURE)
            removeMark(old)
        }
    }

    private suspend fun removeMark(old: IntakeRow) {
        // Preserve the displayed snapshot even if its series was edited after the mark.
        if (old.status == IntakeStatus.TAKEN.name) reverseDebit(old.key, snapshot())
        dao.exception(old.model().occurrence.row())
        dao.deleteIntake(old.key)
    }
}

private fun ProfileRow.model() = UserProfile(id, name, Instant.parse(createdAt))
private fun PressureRow.model() = PressureMeasurement(id, systolic, diastolic, pulse,
    Instant.parse(measuredAt), zoneId, note, userId, InputSource.valueOf(inputSource))

private fun UserMedicationRow.model(movements: List<StockMovementRow>) = UserMedication(id, userId, name,
    form, strength, DoseUnit.valueOf(unit), active,
    movements.firstOrNull { it.type == "INITIAL" }?.amount?.toBigDecimal())
private fun ScheduleRow.model(slots: List<ScheduleSlotRow>): ScheduleRevision {
    return ScheduleRevision(id, seriesId, LocalDateTime.parse(effectiveFrom),
        effectiveUntil?.let(LocalDateTime::parse), ScheduleDraft(name, Repeat.valueOf(repeat),
            DayOfWeek.entries.filter { weekdayMask and (1 shl it.ordinal) != 0 }.toSet(),
            LocalDate.parse(startDate), endDate?.let(LocalDate::parse),
            slots.map { slot ->
                DoseTime(slot.id, LocalTime.parse(slot.time),
                    Dose.parse(slot.amount, DoseUnit.valueOf(slot.unit)))
            }), userId, medicationId)
}

private fun ScheduleRevision.row() = ScheduleRow(id, seriesId, from.toString(), until?.toString(),
    draft.name.trim(), draft.repeat.name, draft.weekdays.fold(0) { mask, day -> mask or (1 shl day.ordinal) },
    draft.startDate.toString(), draft.endDate?.toString(), JSONArray().apply {
        draft.slots.forEach { slot -> put(JSONObject().apply {
            put("id", slot.id); put("time", slot.time.toString())
            put("amount", slot.dose.text); put("unit", slot.dose.unit.name)
        }) }
    }.toString(), userId, medicationId)

private fun OccurrenceRow.model() = Occurrence(seriesId, revisionId, slotId, LocalDate.parse(date),
    LocalTime.parse(time), name, Dose.parse(amount, DoseUnit.valueOf(unit)), userId)
private fun Occurrence.row() = OccurrenceRow(key, seriesId, revisionId, slotId, date.toString(),
    time.toString(), name.trim(), dose.text, dose.unit.name, userId)
private fun IntakeRow.model() = IntakeRecord(Occurrence(seriesId, revisionId, slotId,
    LocalDate.parse(date), LocalTime.parse(time), name, Dose.parse(amount, DoseUnit.valueOf(unit)), userId),
    IntakeStatus.valueOf(status), Instant.parse(plannedAt), zoneId, takenAt?.let(Instant::parse), strength)
private fun StockMovementRow.model() = StockMovement(id, userId, medicationId,
    StockMovementType.valueOf(type), amount.toBigDecimal(), Instant.parse(occurredAt), intakeKey, reversesId)
private fun ReminderRow.model() = Reminder(id, userId, intakeKey, ReminderType.valueOf(type),
    Instant.parse(triggerAt), ReminderStatus.valueOf(status), requestCode, title,
    amount?.toBigDecimal(), unit?.let(DoseUnit::valueOf), medicationId, elapsedAtMillis, bootCount)
