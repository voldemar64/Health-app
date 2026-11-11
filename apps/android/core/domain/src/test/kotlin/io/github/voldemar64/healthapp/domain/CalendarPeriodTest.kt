package io.github.voldemar64.healthapp.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CalendarPeriodTest {
    @Test fun weekCrossesYearWithoutDroppingDates() {
        val period = calendarPeriod(LocalDate.of(2026, 12, 31), false)
        assertEquals(LocalDate.of(2026, 12, 28), period.start)
        assertEquals(LocalDate.of(2027, 1, 3), period.end)
        assertEquals(7, period.cells.size)
        assertFalse(period.cells.contains(null))
    }
    @Test fun monthsIncludeEveryDayExactlyOnceWithMondayColumns() {
        listOf(LocalDate.of(2027, 2, 1), LocalDate.of(2028, 2, 29), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 12, 31)).forEach { date ->
            val period = calendarPeriod(date, true)
            assertEquals(date.lengthOfMonth(), period.cells.filterNotNull().distinct().size)
            assertEquals(0, period.cells.size % 7)
            period.cells.forEachIndexed { index, day -> if (day != null) assertEquals(index % 7, day.dayOfWeek.ordinal) }
            assertTrue(period.cells.contains(date))
        }
        assertEquals(42, calendarPeriod(LocalDate.of(2026, 8, 1), true).cells.size)
        assertEquals(29, calendarPeriod(LocalDate.of(2028, 2, 1), true).end.dayOfMonth)
    }
}
