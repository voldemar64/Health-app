package io.github.voldemar64.healthapp.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.PressureMeasurement
import io.github.voldemar64.healthapp.domain.chartData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

@Composable
internal fun MeasurementChart(measurements: List<PressureMeasurement>, pulse: Boolean) {
    val geometry = chartData(measurements, pulse) ?: return
    val items = geometry.items
    val bottom = geometry.bottom
    val top = geometry.top
    val first = geometry.first
    val last = geometry.last
    val title = stringResource(if (pulse) R.string.pulse_chart else R.string.pressure_chart)
    val description = stringResource(if (pulse) R.string.pulse_chart_description else R.string.chart_description)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!pulse) Text(stringResource(R.string.chart_legend), style = MaterialTheme.typography.bodyMedium)
            Canvas(Modifier.fillMaxWidth().height(260.dp).semantics { contentDescription = "$title. $description" }) {
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(85, 86, 95)
                    textSize = 12.sp.toPx()
                }
                val right = size.width - text.measureText(top.toInt().toString()) - 12.dp.toPx()
                val left = 10.dp.toPx()
                val yTop = 12.dp.toPx()
                val yBottom = size.height - 40.sp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                repeat(5) { index ->
                    val y = yTop + (yBottom - yTop) * index / 4
                    val value = top - (top - bottom) * index / 4
                    drawLine(Color(0xFFD9D9E2), Offset(left, y), Offset(right, y), 1.dp.toPx(), pathEffect = dash)
                    drawContext.canvas.nativeCanvas.drawText(value.toInt().toString(), right + 8.dp.toPx(), y + text.textSize / 3, text)
                    val x = left + (right - left) * index / 4
                    drawLine(Color(0xFFE6E6ED), Offset(x, yTop), Offset(x, yBottom), 1.dp.toPx(), pathEffect = dash)
                }
                fun point(item: PressureMeasurement, value: Int) = Offset(
                    left + geometry.x(item) * (right - left),
                    yBottom - geometry.y(value) * (yBottom - yTop))
                val series = if (pulse) listOf(Color(0xFF8462AC) to items.map { it.pulse!! }) else
                    listOf(Color(0xFF6683CB) to items.map { it.systolic }, Color(0xFFA47B00) to items.map { it.diastolic })
                series.forEach { (color, numbers) ->
                    items.indices.drop(1).forEach { i -> drawLine(color, point(items[i - 1], numbers[i - 1]), point(items[i], numbers[i]), 3.dp.toPx()) }
                    items.indices.forEach { i ->
                        val center = point(items[i], numbers[i])
                        drawCircle(Color.White, 5.dp.toPx(), center)
                        drawCircle(color, 5.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                    }
                }
                val zone = ZoneId.systemDefault()
                val format = DateTimeFormatter.ofPattern(if (items.first().measuredAt.atZone(zone).toLocalDate() == items.last().measuredAt.atZone(zone).toLocalDate()) "HH:mm" else "dd.MM")
                val ticks = if (first == last) listOf(0.5f) else listOf(0f, 0.5f, 1f)
                ticks.forEach { fraction ->
                    val label = Instant.ofEpochMilli(first + ((last - first) * fraction).toLong()).atZone(zone).format(format)
                    val x = (left + (right - left) * fraction - text.measureText(label) / 2).coerceIn(0f, size.width - text.measureText(label))
                    drawContext.canvas.nativeCanvas.drawText(label, x, yBottom + text.textSize * 1.8f, text)
                }
            }
        }
    }
}
