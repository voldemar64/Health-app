package io.github.voldemar64.healthapp.domain

import kotlin.math.ceil
import kotlin.math.floor

data class ChartData(val items: List<PressureMeasurement>, val pulse: Boolean, val bottom: Float, val top: Float) {
    val first: Long get() = items.first().measuredAt.toEpochMilli()
    val last: Long get() = items.last().measuredAt.toEpochMilli()
    fun x(item: PressureMeasurement): Float = if (first == last) 0.5f else
        ((item.measuredAt.toEpochMilli() - first).toDouble() / (last - first)).toFloat()
    fun y(value: Int): Float = (value - bottom) / (top - bottom)
    val series: List<List<Int>> get() = if (pulse) listOf(items.map { it.pulse!! })
        else listOf(items.map { it.systolic }, items.map { it.diastolic })
}

fun chartData(measurements: List<PressureMeasurement>, pulse: Boolean): ChartData? {
    val items = measurements.filter { !pulse || it.pulse != null }.sortedBy { it.measuredAt }
    if (items.isEmpty()) return null
    val values = items.flatMap { if (pulse) listOf(it.pulse!!) else listOf(it.systolic, it.diastolic) }
    return ChartData(items, pulse, (floor((values.min() - 5) / 20.0) * 20).coerceAtLeast(0.0).toFloat(),
        (ceil((values.max() + 5) / 20.0) * 20).toFloat())
}
