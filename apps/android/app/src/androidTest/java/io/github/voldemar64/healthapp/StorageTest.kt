package io.github.voldemar64.healthapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.voldemar64.healthapp.data.HealthDatabase
import io.github.voldemar64.healthapp.data.RoomHealthRepository
import io.github.voldemar64.healthapp.domain.*
import java.time.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "storage-test.db"
    private lateinit var db: HealthDatabase
    private lateinit var repository: RoomHealthRepository
    private val now = Instant.parse("2026-11-09T10:00:00Z")
    private val today = LocalDate.of(2026, 11, 9)
    private var zone: ZoneId = ZoneOffset.UTC
    private fun connect() {
        db = HealthDatabase.open(context, dbName)
        repository = RoomHealthRepository(db, Clock.fixed(now, ZoneOffset.UTC)) { zone }
    }
    @Before fun setUp() { context.deleteDatabase(dbName); connect() }
    @After fun tearDown() { db.close(); context.deleteDatabase(dbName) }
    private fun draft() = ScheduleDraft("Лекарство", Repeat.DAILY, emptySet(), today, null,
        listOf(DoseTime("morning", LocalTime.of(9, 0), Dose.parse("0.5", DoseUnit.TABLET)),
            DoseTime("evening", LocalTime.of(18, 0), Dose.parse("0.000001", DoseUnit.G))))

    @Test fun stockDebitIsAtomicAndReconciliationIsFinal() = runBlocking {
        val schedule = ScheduleDraft("Тест", Repeat.DAILY, emptySet(), today, null,
            listOf(DoseTime("morning", LocalTime.of(9, 0), Dose.parse("0.5", DoseUnit.TABLET))))
        repository.saveMedicationSchedule(schedule, MedicationCardInput("Тест", "Таблетка", null,
            DoseUnit.TABLET, parseStockAmount("30")))
        val initial = repository.data.first()
        val cardId = initial.medications.single().id
        val item = occurrences(initial, today, today).single()
        (1..8).map { async { repository.markTakenNow(item) } }.awaitAll()
        val taken = repository.data.first()
        assertEquals(1, taken.stockMovements.count { it.type == StockMovementType.DEBIT })
        assertEquals("29.5", stockBalance(taken.stockMovements).displayAmount?.stripTrailingZeros()?.toPlainString())
        repository.reconcileStock(cardId, parseStockAmount("27"))
        repository.unmarkToday(item.key)
        val undone = repository.data.first()
        assertEquals(1, undone.stockMovements.count { it.type == StockMovementType.REVERSAL })
        assertEquals("27", stockBalance(undone.stockMovements).displayAmount?.toPlainString())
        repository.markTakenNow(item)
        assertEquals("26.5", stockBalance(repository.data.first().stockMovements).displayAmount?.stripTrailingZeros()?.toPlainString())
    }

    @Test fun reminderActionUsesStoredProfileEvenWhenAnotherIsOpen() = runBlocking {
        val schedule = ScheduleDraft("Тест", Repeat.DAILY, emptySet(), today, null,
            listOf(DoseTime("future", LocalTime.of(11, 0), Dose.parse("1", DoseUnit.TABLET))))
        repository.saveMedicationSchedule(schedule, MedicationCardInput("Тест", "Таблетка", null,
            DoseUnit.TABLET, parseStockAmount("30")))
        val reminder = repository.refreshReminders(now).single { it.type == ReminderType.INTAKE &&
            it.triggerAt == now.plusSeconds(3600) }
        val other = repository.createProfile("Другой")
        assertEquals(LEGACY_PROFILE_ID, repository.fireReminder(reminder.id, now.plusSeconds(3600))?.userId)
        assertTrue(repository.actOnReminder(reminder.id, ReminderAction.TAKEN, now.plusSeconds(3660)))
        assertFalse(repository.actOnReminder(reminder.id, ReminderAction.TAKEN, now.plusSeconds(3661)))
        assertTrue(repository.data.first().intakes.isEmpty())
        repository.selectProfile(LEGACY_PROFILE_ID)
        val state = repository.data.first()
        assertEquals(IntakeStatus.TAKEN, state.intakes.single().status)
        assertEquals("29", stockBalance(state.stockMovements).displayAmount?.toPlainString())
        repository.selectProfile(other.id)
        assertTrue(repository.data.first().stockMovements.isEmpty())
    }

    @Test fun oneOffReminderDoesNotCreateScheduleAndCanExpire() = runBlocking {
        val reminder = repository.createReminder("Проверить воду", now.plusSeconds(900))
        assertTrue(repository.data.first().schedules.isEmpty())
        assertNotNull(repository.fireReminder(reminder.id, now.plusSeconds(900)))
        val expiry = repository.data.first().reminders.single { it.type == ReminderType.EXPIRY }
        assertNull(repository.fireReminder(expiry.id, now.plusSeconds(4500)))
        assertEquals(ReminderStatus.UNCONFIRMED, repository.reminder(reminder.id)?.status)
        assertFalse(repository.actOnReminder(reminder.id, ReminderAction.SKIP, now.plusSeconds(4501)))
    }

    @Test fun personalMedicationCanExistWithoutScheduleOrKnownStock() = runBlocking {
        val card = repository.createMedication(MedicationCardInput("Отдельная карточка", "Капсула",
            "40 мг", DoseUnit.CAPSULE))
        val before = repository.data.first()
        assertTrue(before.schedules.isEmpty())
        assertNull(before.medications.single().initialStock)
        assertNull(stockBalance(before.stockMovements).amount)
        repository.saveScheduleForMedication(card.id, ScheduleDraft(card.name, Repeat.DAILY,
            emptySet(), today, null, listOf(DoseTime(time = LocalTime.of(12, 0),
                dose = Dose.parse("0.5", DoseUnit.CAPSULE)))))
        val after = repository.data.first()
        assertEquals(card.id, after.schedules.single().medicationId)
        assertEquals(1, after.medications.size)
    }

    @Test fun legacyMissingPulseRemainsReadableAndCanBeEdited() = runBlocking {
        db.dao().pressure(io.github.voldemar64.healthapp.data.PressureRow("legacy", 120, 80, null,
            now.minusSeconds(60).toString(), "UTC", "Старый комментарий"))
        val old = repository.data.first().pressures.single()
        assertNull(old.pulse)
        assertEquals("Старый комментарий", old.note)
        repository.savePressure(old)
        assertEquals("Старый комментарий", repository.data.first().pressures.single().note)
    }

    @Test fun periodQueryFiltersProfileAndDatesInStorage() = runBlocking {
        repository.savePressure(PressureMeasurement(id = "old", systolic = 120, diastolic = 80,
            pulse = null, measuredAt = now.minus(Duration.ofDays(10)), recordedZoneId = "UTC"))
        repository.savePressure(PressureMeasurement(id = "current", systolic = 121, diastolic = 81,
            pulse = null, measuredAt = now.minusSeconds(60), recordedZoneId = "UTC"))
        repository.saveSchedule(draft())
        val other = repository.createProfile("Другой")
        repository.savePressure(PressureMeasurement(id = "foreign", systolic = 122, diastolic = 82,
            pulse = null, measuredAt = now.minusSeconds(60), recordedZoneId = "UTC"))
        val first = repository.observeProfile(LEGACY_PROFILE_ID, today, today).first()
        val second = repository.observeProfile(other.id, today, today).first()
        assertEquals(2, repository.observeProfile(LEGACY_PROFILE_ID).first().pressures.size)
        assertEquals(listOf("current"), first.pressures.map { it.id })
        assertEquals(1, first.schedules.size)
        assertEquals(listOf("foreign"), second.pressures.map { it.id })
        assertTrue(second.schedules.isEmpty())
    }

    @Test fun profilesKeepMeasurementsAndSchedulesSeparate() = runBlocking {
        repository.savePressure(PressureMeasurement(systolic = 120, diastolic = 80, pulse = null,
            measuredAt = now.minusSeconds(60), recordedZoneId = "UTC"))
        repository.saveSchedule(draft())
        val second = repository.createProfile("Второй")
        assertTrue(repository.data.first().pressures.isEmpty())
        assertTrue(repository.data.first().schedules.isEmpty())
        repository.savePressure(PressureMeasurement(systolic = 130, diastolic = 90, pulse = 70,
            measuredAt = now.minusSeconds(30), recordedZoneId = "UTC"))
        assertEquals(1, repository.data.first().pressures.size)
        assertEquals(second.id, repository.data.first().pressures.single().userId)
        repository.selectProfile(LEGACY_PROFILE_ID)
        assertEquals(120, repository.data.first().pressures.single().systolic)
        assertEquals(1, repository.data.first().schedules.size)
        repository.deleteProfile(second.id)
        assertEquals(1, repository.profiles.first().size)
    }

    @Test fun newMedicationKeepsCardStrengthInitialStockAndLinkedSlots() = runBlocking {
        val schedule = ScheduleDraft("Капсула тест", Repeat.DAILY, emptySet(), today, null,
            listOf(DoseTime("morning", LocalTime.of(9, 0), Dose.parse("1", DoseUnit.CAPSULE)),
                DoseTime("evening", LocalTime.of(18, 0), Dose.parse("0.5", DoseUnit.CAPSULE))))
        repository.saveMedicationSchedule(schedule, MedicationCardInput("Капсула тест", "Капсула", "40 мг",
            DoseUnit.CAPSULE, parseStockAmount("30")))
        val saved = repository.data.first()
        assertNotEquals(saved.schedules.single().seriesId, saved.medications.single().id)
        assertEquals(saved.schedules.single().medicationId, saved.medications.single().id)
        assertEquals("40 мг", saved.medications.single().strength)
        assertEquals("30", saved.medications.single().initialStock?.toPlainString())
        assertEquals(2, db.dao().slots(LEGACY_PROFILE_ID).size)
        db.close()
        connect()
        val reopened = repository.data.first()
        assertEquals(listOf("1", "0.5"), reopened.schedules.single().draft.slots.map { it.dose.text })
        assertEquals(DoseUnit.CAPSULE, reopened.medications.single().unit)
        val originalSeries = reopened.schedules.single().seriesId
        repository.saveSchedule(schedule.copy(name = "Капсула новое имя"), originalSeries, today)
        val edited = repository.data.first()
        assertEquals(2, edited.schedules.size)
        assertEquals(setOf(reopened.medications.single().id), edited.schedules.map { it.medicationId }.toSet())
        assertEquals("40 мг", edited.medications.single().strength)
        assertEquals("30", edited.medications.single().initialStock?.toPlainString())
        val morning = occurrences(edited, today, today).first { it.time == LocalTime.of(9, 0) }
        assertEquals("Капсула тест", morning.name)
    }

    @Test fun diskReopenKeepsPressureScheduleExactDoseAndIntake() = runBlocking {
        val pressure = PressureMeasurement(systolic = 123, diastolic = 81, pulse = 65,
            measuredAt = now.minusSeconds(60), recordedZoneId = "UTC", note = "Комментарий")
        repository.savePressure(pressure)
        repository.saveSchedule(draft())
        val occurrence = occurrences(repository.data.first(), today, today).first()
        repository.mark(occurrence, IntakeStatus.TAKEN, now)
        db.close()
        connect()
        val state = repository.data.first()
        assertEquals(listOf(pressure), state.pressures)
        assertEquals("0.000001", state.schedules.single().draft.slots.last().dose.text)
        assertEquals(now, state.intakes.single().takenAt)
        repository.savePressure(pressure.copy(systolic = 130))
        assertEquals(1, repository.data.first().pressures.size)
        repository.deletePressure(pressure.id)
        assertTrue(repository.data.first().pressures.isEmpty())
    }

    @Test fun concurrentMarksAreUniqueAndUndoRestoresUnmarked() = runBlocking {
        repository.saveSchedule(draft())
        val item = occurrences(repository.data.first(), today, today).first()
        (1..8).map { async { repository.mark(item, IntakeStatus.TAKEN, now) } }.awaitAll()
        assertEquals(1, repository.data.first().intakes.size)
        repository.unmark(item.key)
        val state = repository.data.first()
        assertTrue(state.intakes.isEmpty())
        assertEquals(2, occurrences(state, today, today).size)
    }

    @Test fun takenNowIsAtomicAndRepeatedCallbacksKeepOriginalTimestamp() = runBlocking {
        repository.saveSchedule(draft())
        val item = occurrences(repository.data.first(), today, today).first()
        (1..8).map { async { repository.markTakenNow(item) } }.awaitAll()
        val initial = repository.data.first().intakes.single()
        assertEquals(now, initial.takenAt)
        val later = RoomHealthRepository(db, Clock.offset(Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(2))) { zone }
        (1..8).map { async { later.markTakenNow(item) } }.awaitAll()
        assertEquals(listOf(initial), repository.data.first().intakes)
    }

    @Test fun takenNowRejectsPastAndFutureWithoutChangingStorage() = runBlocking {
        repository.saveSchedule(draft())
        val initial = repository.data.first()
        val item = occurrences(initial, today, today).first()
        val tomorrow = occurrences(initial, today.plusDays(1), today.plusDays(1)).first()
        try { repository.markTakenNow(tomorrow); fail("Future occurrence must be rejected") }
        catch (expected: InvalidInput) { assertEquals(InputError.FUTURE, expected.code) }
        val nextDay = RoomHealthRepository(db, Clock.offset(Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(1))) { zone }
        try { nextDay.markTakenNow(item); fail("Past occurrence must be rejected") }
        catch (expected: InvalidInput) { assertEquals(InputError.FUTURE, expected.code) }
        assertEquals(initial, repository.data.first())
    }

    @Test fun takenNowUsesLocalDateAndReplacesLegacySkippedMark() = runBlocking {
        repository.saveSchedule(draft())
        val item = occurrences(repository.data.first(), today, today).first()
        repository.mark(item, IntakeStatus.SKIPPED, null)
        val legacy = repository.data.first().intakes.single()
        zone = ZoneId.of("Asia/Tokyo")
        val localMorning = Instant.parse("2026-11-08T23:30:00Z")
        val localRepository = RoomHealthRepository(db, Clock.fixed(localMorning, ZoneOffset.UTC)) { zone }
        localRepository.markTakenNow(item)
        val record = repository.data.first().intakes.single()
        assertEquals(IntakeStatus.TAKEN, record.status)
        assertEquals(localMorning, record.takenAt)
        assertEquals(legacy.plannedAt, record.plannedAt)
        assertEquals(legacy.recordedZoneId, record.recordedZoneId)
    }

    @Test fun undoTodayKeepsOccurrenceSnapshotAndRejectsUndoAfterMidnight() = runBlocking {
        repository.saveSchedule(draft())
        val initial = repository.data.first()
        val item = occurrences(initial, today, today).last()
        repository.markTakenNow(item)
        repository.saveSchedule(draft().copy(name = "Новое название", slots = listOf(draft().slots.first())),
            initial.schedules.single().seriesId, today)
        repository.unmarkToday(item.key)
        val undone = repository.data.first()
        assertTrue(undone.intakes.isEmpty())
        assertEquals(item, undone.exceptions.single())
        assertEquals(item, occurrences(undone, today, today).find { it.key == item.key })
        repository.markTakenNow(item)
        val marked = repository.data.first()
        val nextDay = RoomHealthRepository(db, Clock.offset(Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(1))) { zone }
        try { nextDay.unmarkToday(item.key); fail("Historical marks must be read-only") }
        catch (expected: InvalidInput) { assertEquals(InputError.FUTURE, expected.code) }
        assertEquals(marked, repository.data.first())
    }

    @Test fun revisionsKeepPastAndFactsAndReplaceFutureWithoutDuplicates() = runBlocking {
        repository.saveSchedule(draft())
        val state = repository.data.first()
        val revision = state.schedules.single()
        val morning = occurrences(state, today, today).first()
        repository.mark(morning, IntakeStatus.TAKEN, now)
        repository.saveSchedule(draft().copy(name = "Новое название", slots = listOf(
            draft().slots[0].copy(time = LocalTime.of(12, 0), dose = Dose.parse("2", DoseUnit.TABLET)))), revision.seriesId, today)
        val updated = repository.data.first()
        val items = occurrences(updated, today, today.plusDays(1))
        assertEquals(2, items.size)
        assertEquals("Лекарство", items.first().name)
        assertEquals("0.5", items.first().dose.text)
        assertEquals("Новое название", items.last().name)
        assertEquals("2", items.last().dose.text)
        repository.unmark(morning.key)
        assertEquals(morning, occurrences(repository.data.first(), today, today).single())
    }

    @Test fun singleEditSurvivesReopenButSeriesEditReplacesFutureException() = runBlocking {
        repository.saveSchedule(draft())
        val initial = repository.data.first()
        val evening = occurrences(initial, today, today).last()
        repository.editOccurrence(evening, evening.copy(time = LocalTime.of(19, 0), dose = Dose.parse("0.75", DoseUnit.G)))
        db.close(); connect()
        assertEquals("0.75", occurrences(repository.data.first(), today, today).last().dose.text)
        repository.saveSchedule(draft(), initial.schedules.single().seriesId, today)
        assertTrue(repository.data.first().exceptions.isEmpty())
        assertEquals(LocalTime.of(18, 0), occurrences(repository.data.first(), today, today).last().time)
    }

    @Test fun rejectedEditLeavesAllTablesUnchanged() = runBlocking {
        repository.saveSchedule(draft())
        val state = repository.data.first()
        val morning = occurrences(state, today, today).first()
        try {
            repository.editOccurrence(morning, morning.copy(time = LocalTime.of(15, 0)))
            fail("Past occurrence must not be edited")
        } catch (expected: InvalidInput) { assertEquals(InputError.FUTURE, expected.code) }
        try {
            repository.saveSchedule(draft(), state.schedules.single().seriesId, today.minusDays(1))
            fail("Past revision must not be accepted")
        } catch (expected: InvalidInput) { assertEquals(InputError.DATES, expected.code) }
        assertEquals(state, repository.data.first())
    }

    @Test fun travelChangesFutureInstantButKeepsFactSnapshot() = runBlocking {
        repository.saveSchedule(draft())
        val item = occurrences(repository.data.first(), today, today).first()
        repository.mark(item, IntakeStatus.TAKEN, now)
        val snapshot = repository.data.first().intakes.single()
        zone = ZoneId.of("Asia/Tokyo")
        repository.mark(item, IntakeStatus.SKIPPED, null)
        assertEquals(snapshot.plannedAt, repository.data.first().intakes.single().plannedAt)
        assertEquals("Z", repository.data.first().intakes.single().recordedZoneId)
        assertNotEquals(snapshot.plannedAt, item.instant(zone))
    }
}
