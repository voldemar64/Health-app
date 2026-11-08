package io.github.voldemar64.healthapp.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class HealthRulesTest {
    private val monday = LocalDate.of(2026, 11, 9)
    private fun slot(time: String = "09:00", amount: String = "0,5") = DoseTime("morning", LocalTime.parse(time), Dose.parse(amount, DoseUnit.TABLET))
    private fun revision(repeat: Repeat = Repeat.DAILY, days: Set<DayOfWeek> = emptySet(),
        start: LocalDate = monday, end: LocalDate? = null) = ScheduleRevision("v1", "series", start.atStartOfDay(),
        draft = ScheduleDraft("Лекарство", repeat, days, start, end, listOf(slot())))

    @Test fun decimalDoseKeepsExactValueAndRejectsAmbiguousOrNonPositiveInput() {
        assertEquals("0.5", Dose.parse("0,500", DoseUnit.TABLET).text)
        assertEquals("0.000001", Dose.parse("0.000001", DoseUnit.G).text)
        listOf("0", "-1", "1e3", "1,2.3", "0.0000001", "9999999999999").forEach { bad ->
            assertThrows(InvalidInput::class.java) { Dose.parse(bad, DoseUnit.MG) }
        }
    }

    @Test fun validatesPressureWithoutApplyingDiagnosticThresholds() {
        assertThrows(InvalidInput::class.java) { PressureMeasurement(systolic = 80, diastolic = 120, pulse = null, measuredAt = Instant.EPOCH, recordedZoneId = "UTC") }
        assertThrows(InvalidInput::class.java) { PressureMeasurement(systolic = 120, diastolic = 80, pulse = 0, measuredAt = Instant.EPOCH, recordedZoneId = "UTC") }
        assertEquals(null, PressureMeasurement(systolic = 120, diastolic = 80, pulse = null, measuredAt = Instant.EPOCH, recordedZoneId = "UTC").pulse)
    }

    @Test fun weeklySupportsMultipleDaysAndInclusiveEnd() {
        val revision = revision(Repeat.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), end = monday.plusDays(7))
        val items = occurrences(HealthData(schedules = listOf(revision)), monday, monday.plusDays(30))
        assertEquals(listOf(monday, monday.plusDays(3), monday.plusDays(7)), items.map { it.date })
    }

    @Test fun onceAndDailyHandleMonthAndLeapDayBoundaries() {
        val start = LocalDate.of(2028, 2, 28)
        assertEquals(1, occurrences(HealthData(schedules = listOf(revision(Repeat.ONCE, start = start))), start, start.plusMonths(1)).size)
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), occurrences(
            HealthData(schedules = listOf(revision(start = start, end = start.plusDays(2)))), start, start.plusMonths(1)).map { it.date })
    }

    @Test fun separateTimesAndRevisionsDoNotRepeatAnElapsedDoseToday() {
        val old = revision().copy(until = monday.atTime(12, 0))
        val newer = old.copy(id = "v2", from = monday.atTime(12, 0), until = null,
            draft = old.draft.copy(slots = listOf(slot("18:00", "1"), DoseTime("night", LocalTime.of(21, 0), Dose.parse("2", DoseUnit.MG)))))
        val items = occurrences(HealthData(schedules = listOf(old, newer)), monday, monday.plusDays(1))
        assertEquals(4, items.size)
        assertEquals(LocalTime.of(9, 0), items.first().time)
        assertEquals("0.5", items.first().dose.text)
        assertEquals(LocalTime.of(18, 0), items[2].time)
        assertEquals(items.size, items.map { it.key }.distinct().size)
    }

    @Test fun exceptionAndFactOverrideGeneratedOccurrenceWithoutDuplicatingIt() {
        val revision = revision()
        val original = occurrences(HealthData(schedules = listOf(revision)), monday, monday).single()
        val edit = original.copy(time = LocalTime.NOON)
        val fact = IntakeRecord(original, IntakeStatus.TAKEN, original.instant(ZoneOffset.UTC), "UTC", Instant.EPOCH)
        assertEquals(listOf(original), occurrences(HealthData(schedules = listOf(revision), exceptions = listOf(edit), intakes = listOf(fact)), monday, monday))
        assertEquals(listOf(edit), occurrences(HealthData(exceptions = listOf(edit)), monday, monday))
    }

    @Test fun dstGapMovesForwardOverlapOccursOnceAndTravelKeepsWallTime() {
        val template = Occurrence("s", "r", "t", LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), "Лекарство", slot().dose)
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(LocalTime.of(3, 30), template.instant(berlin).atZone(berlin).toLocalTime())
        val autumn = template.copy(date = LocalDate.of(2026, 10, 25))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), autumn.instant(berlin))
        val normal = template.copy(date = monday, time = LocalTime.of(9, 0))
        assertEquals(LocalTime.of(9, 0), normal.instant(ZoneId.of("Asia/Tokyo")).atZone(ZoneId.of("Asia/Tokyo")).toLocalTime())
        assertNotEquals(normal.instant(berlin), normal.instant(ZoneId.of("Asia/Tokyo")))
    }

    @Test fun statisticsUseLocalInclusiveDatesAndEveryMeasurement() {
        val measurements = listOf(
            PressureMeasurement(systolic = 120, diastolic = 80, pulse = null, measuredAt = Instant.parse("2026-11-08T22:00:00Z"), recordedZoneId = "Europe/Moscow"),
            PressureMeasurement(systolic = 140, diastolic = 90, pulse = null, measuredAt = Instant.parse("2026-11-09T20:59:59Z"), recordedZoneId = "Europe/Moscow"),
            PressureMeasurement(systolic = 180, diastolic = 100, pulse = null, measuredAt = Instant.parse("2026-11-09T21:00:00Z"), recordedZoneId = "Europe/Moscow"))
        val summary = summarize(pressureInRange(measurements, monday, monday, ZoneId.of("Europe/Moscow")))!!
        assertEquals(2, summary.count)
        assertEquals(130.0, summary.systolic, 0.0)
        assertEquals(85.0, summary.diastolic, 0.0)
        assertNull(summarize(emptyList()))
    }

    @Test fun scheduleRejectsDuplicateTimesEmptyWeekAndReversedDates() {
        val draft = revision().draft
        assertThrows(InvalidInput::class.java) { draft.copy(slots = listOf(slot(), slot().copy(id = "second"))) }
        assertThrows(InvalidInput::class.java) { draft.copy(repeat = Repeat.WEEKLY) }
        assertThrows(InvalidInput::class.java) { draft.copy(endDate = monday.minusDays(1)) }
    }
}
