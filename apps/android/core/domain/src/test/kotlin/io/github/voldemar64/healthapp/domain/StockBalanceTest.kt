package io.github.voldemar64.healthapp.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.DayOfWeek
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class StockBalanceTest {
    private val at = Instant.parse("2026-11-16T10:00:00Z")
    private fun movement(id: String, type: StockMovementType, amount: String,
        reverses: String? = null) = StockMovement(id, "p", "m", type,
        BigDecimal(amount), at, reversesId = reverses)

    @Test fun exactDebitAndReversal() {
        val initial = movement("1", StockMovementType.INITIAL, "30")
        val debit = movement("2", StockMovementType.DEBIT, "0.5")
        assertEquals(BigDecimal("29.5"), stockBalance(listOf(initial, debit)).displayAmount)
        assertEquals(BigDecimal("30.0"), stockBalance(listOf(initial, debit,
            movement("3", StockMovementType.REVERSAL, "0.5", "2"))).displayAmount)
    }

    @Test fun reconciliationSupersedesEarlierDebitAndReversal() {
        val movements = listOf(movement("1", StockMovementType.INITIAL, "30"),
            movement("2", StockMovementType.DEBIT, "0.5"),
            movement("3", StockMovementType.RECONCILE, "27"),
            movement("4", StockMovementType.REVERSAL, "0.5", "2"))
        assertEquals(BigDecimal("27"), stockBalance(movements).displayAmount)
    }

    @Test fun unknownAndShortageStayHidden() {
        assertNull(stockBalance(emptyList()).displayAmount)
        val movements = listOf(movement("1", StockMovementType.INITIAL, "0"),
            movement("2", StockMovementType.DEBIT, "1"))
        assertNull(stockBalance(movements).displayAmount)
        assertFalse(stockBalance(movements).reliable)
    }

    @Test fun forecastUsesWeeklySlotsAndDifferentQuantities() {
        val start = LocalDate.of(2026, 11, 16)
        val draft = ScheduleDraft("Тест", Repeat.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            start, null, listOf(
                DoseTime("morning", LocalTime.of(9, 0), Dose.parse("0.5", DoseUnit.TABLET)),
                DoseTime("evening", LocalTime.of(18, 0), Dose.parse("1", DoseUnit.TABLET))))
        val state = HealthData(schedules = listOf(ScheduleRevision("revision", "series", start.atStartOfDay(),
            draft = draft, medicationId = "m")), medications = listOf(UserMedication("m", LEGACY_PROFILE_ID,
            "Тест", "Таблетка", null, DoseUnit.TABLET, true, BigDecimal("2"))),
            stockMovements = listOf(movement("initial", StockMovementType.INITIAL, "2")))
        val now = start.atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(start.plusDays(2), stockRunoutDate(state, "m", now, ZoneOffset.UTC))
    }
}
