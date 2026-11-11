package io.github.voldemar64.healthapp.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ChartDataTest {
    private fun item(at: String, pulse: Int? = 65) = PressureMeasurement(systolic = 120, diastolic = 80, pulse = pulse,
        measuredAt = Instant.parse(at), recordedZoneId = "UTC")
    @Test fun emptyAndLegacyMissingPulse() {
        assertNull(chartData(emptyList(), false))
        val legacy = item("2026-11-01T12:00:00Z", null)
        assertNotNull(chartData(listOf(legacy), false))
        assertNull(chartData(listOf(legacy), true))
    }
    @Test fun singlePointAndEqualTimestampsHaveFiniteCenteredCoordinates() {
        val item = item("2026-11-01T12:00:00Z")
        val chart = chartData(listOf(item, item.copy(systolic = 130)), false)!!
        assertEquals(0.5f, chart.x(item), 0f)
        assertTrue(chart.top > chart.bottom)
        assertTrue(chart.y(120) in 0f..1f)
    }
    @Test fun unsortedValuesAreChronologicalAndBothSeriesRemainDistinct() {
        val first = item("2026-10-31T23:00:00Z")
        val last = item("2026-11-01T01:00:00Z")
        val chart = chartData(listOf(last, first), false)!!
        assertEquals(first, chart.items.first())
        assertEquals(0f, chart.x(first), 0f)
        assertEquals(1f, chart.x(last), 0f)
        assertEquals(listOf(listOf(120, 120), listOf(80, 80)), chart.series)
    }
}
