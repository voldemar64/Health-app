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

    @Test fun legacyMissingPulseRemainsReadableButCannotBeWrittenAgain() = runBlocking {
        db.dao().pressure(io.github.voldemar64.healthapp.data.PressureRow("legacy", 120, 80, null,
            now.minusSeconds(60).toString(), "UTC", "Старый комментарий"))
        val old = repository.data.first().pressures.single()
        assertNull(old.pulse)
        assertEquals("Старый комментарий", old.note)
        try { repository.savePressure(old); fail("Pulse is required on every write") }
        catch (expected: InvalidInput) { assertEquals(InputError.PULSE, expected.code) }
        repository.savePressure(old.copy(pulse = 65))
        assertEquals("Старый комментарий", repository.data.first().pressures.single().note)
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
