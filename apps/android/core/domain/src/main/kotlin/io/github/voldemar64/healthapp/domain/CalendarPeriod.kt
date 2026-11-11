package io.github.voldemar64.healthapp.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class CalendarPeriod(val start: LocalDate, val end: LocalDate, val cells: List<LocalDate?>)
fun calendarPeriod(date: LocalDate, month: Boolean): CalendarPeriod {
    val start = if (month) date.withDayOfMonth(1) else date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val end = if (month) start.with(TemporalAdjusters.lastDayOfMonth()) else start.plusDays(6)
    val offset = if (month) start.dayOfWeek.ordinal else 0
    val count = if (month) ((offset + start.lengthOfMonth() + 6) / 7) * 7 else 7
    return CalendarPeriod(start, end, List(count) { index -> start.plusDays((index - offset).toLong()).takeIf { it in start..end } })
}
