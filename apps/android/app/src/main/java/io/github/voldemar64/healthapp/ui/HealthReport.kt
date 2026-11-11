package io.github.voldemar64.healthapp.ui

import android.content.ContentResolver
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.graphics.withTranslation
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.time.*
import kotlin.math.roundToInt

internal data class ReportSnapshot(val data: HealthData, val start: LocalDate, val end: LocalDate,
    val generatedAt: Instant, val zone: ZoneId)
internal data class ReportExportState(val busy: Boolean = false, val result: Int? = null)
internal class ReportViewModel : ViewModel() {
    private var pending: ReportSnapshot? = null
    private val mutable = MutableStateFlow(ReportExportState())
    val state = mutable.asStateFlow()
    fun prepare(data: HealthData, days: Int): String {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val end = now.atZone(zone).toLocalDate()
        pending = ReportSnapshot(data, end.minusDays(days - 1L), end, now, zone)
        mutable.value = ReportExportState(busy = true)
        return "health-report-${pending!!.start}-$end.pdf"
    }
    fun save(resolver: ContentResolver, uri: Uri?) {
        val snapshot = pending
        pending = null
        if (uri == null) { mutable.value = ReportExportState(); return }
        viewModelScope.launch {
            try {
                checkNotNull(snapshot) { "Export snapshot was lost; please choose the period again" }
                withContext(Dispatchers.IO) {
                    val bytes = renderHealthReport(snapshot)
                    resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("No output stream")
                }
                mutable.value = ReportExportState(result = R.string.export_done)
            } catch (failure: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
                if (failure is CancellationException) throw failure
                mutable.value = ReportExportState(result = R.string.export_failed)
            }
        }
    }
}

@Composable
internal fun ReportExport(data: HealthData, days: Int) {
    val model: ReportViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { model.save(resolver, it) }
    ActionButton(if (state.busy) R.string.export_working else R.string.export_pdf, Modifier.fillMaxWidth(), !state.busy) {
        launcher.launch(model.prepare(data, days))
    }
    state.result?.let { Text(stringResource(it)) }
}

internal fun renderHealthReport(snapshot: ReportSnapshot): ByteArray {
    val document = PdfDocument()
    try {
        val writer = ReportWriter(document)
        writer.text("Дневник здоровья", 20f, bold = true)
        writer.text("${snapshot.start.format(dateFormat)} - ${snapshot.end.format(dateFormat)}", 12f)
        writer.text("Создан ${snapshot.generatedAt.atZone(snapshot.zone).format(dateFormat)} ${snapshot.generatedAt.atZone(snapshot.zone).format(timeFormat)} · ${snapshot.zone.id}", 9f)
        val pressures = pressureInRange(snapshot.data.pressures, snapshot.start, snapshot.end, snapshot.zone)
        summarize(pressures)?.let { summary ->
            writer.text("Измерений: ${summary.count}. Среднее давление: ${summary.systolic.roundToInt()}/${summary.diastolic.roundToInt()} мм рт. ст.", 11f)
            val pulses = pressures.mapNotNull { it.pulse }
            if (pulses.isNotEmpty()) writer.text("Средний пульс: ${pulses.average().roundToInt()} уд/мин", 11f)
        }
        writer.chart(chartData(pressures, false), "Давление · мм рт. ст.", snapshot.zone)
        writer.chart(chartData(pressures, true), "Пульс · уд/мин", snapshot.zone)
        writer.text("Приёмы лекарств", 16f, bold = true)
        val records = snapshot.data.intakes.associateBy { it.occurrence.key }
        val items = occurrences(snapshot.data, snapshot.start, snapshot.end)
        fun status(item: Occurrence): String = when (records[item.key]?.status) {
            IntakeStatus.TAKEN -> "✓ Принято"
            IntakeStatus.SKIPPED -> "Пропущено"
            null -> if (item.instant(snapshot.zone) <= snapshot.generatedAt) "Не отмечено" else "Запланировано"
        }
        if (items.isEmpty()) writer.text("За этот период приёмов нет.")
        else {
            val counts = items.groupingBy(::status).eachCount()
            writer.text(listOf("✓ Принято", "Не отмечено", "Запланировано", "Пропущено").joinToString("; ") { "$it: ${counts[it] ?: 0}" }, 10f)
            writer.tableHeader()
            items.forEach { item ->
                val record = records[item.key]
                val historical = record?.occurrence ?: item
                val actual = record?.takenAt?.atZone(record.recordedZoneId.let(ZoneId::of))
                writer.tableRow(listOf(historical.date.format(dateFormat), historical.name,
                    historical.dose.text.replace('.', ',') + " " + when (historical.dose.unit) {
                        DoseUnit.TABLET -> "табл."; DoseUnit.MG -> "мг"; DoseUnit.G -> "г"
                    }, historical.time.format(timeFormat), status(item),
                    actual?.let { "${it.format(dateFormat)} ${it.format(timeFormat)}\n${record.recordedZoneId}" } ?: "-"))
            }
        }
        writer.finish()
        return ByteArrayOutputStream().use { document.writeTo(it); it.toByteArray() }
    } finally { document.close() }
}

private class ReportWriter(private val document: PdfDocument) {
    private var page: PdfDocument.Page? = null
    private var count = 0
    private var y = 36f
    private val canvas get() = page!!.canvas
    private val widths = listOf(60, 160, 55, 45, 100, 103) // 523 pt, A4 with 36 pt margins.
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 35) }
    init { newPage() }
    private fun newPage() {
        finish()
        count++
        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, count).create())
        y = 36f
    }
    fun finish() {
        page?.let {
            paint.textSize = 9f; paint.typeface = Typeface.DEFAULT; paint.color = Color.DKGRAY
            it.canvas.drawText("Дневник здоровья · $count", 36f, 818f, paint)
            document.finishPage(it)
            page = null
        }
    }
    private fun layout(value: String, width: Int, size: Float, bold: Boolean = false): StaticLayout {
        val p = TextPaint(paint).apply { textSize = size; color = Color.rgb(35, 35, 35); typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        return StaticLayout.Builder.obtain(value, 0, value.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false).setLineSpacing(2f, 1f).build()
    }
    private fun draw(layout: StaticLayout, x: Float, top: Float) {
        canvas.withTranslation(x, top) { layout.draw(this) }
    }
    fun text(value: String, size: Float = 11f, bold: Boolean = false) {
        val block = layout(value, 523, size, bold)
        if (y + block.height + 10 > 794) newPage()
        draw(block, 36f, y); y += block.height + 10
    }
    fun chart(data: ChartData?, title: String, zone: ZoneId) {
        if (y + 222 > 794) newPage()
        text(title, 14f, true)
        if (data == null) { text("За этот период нет измерений."); return }
        if (!data.pulse) text("Синяя — верхнее; золотистая — нижнее", 9f)
        val left = 64f; val right = 548f; val top = y + 4; val bottom = top + 140
        paint.typeface = Typeface.DEFAULT; paint.textSize = 9f; paint.strokeWidth = 0.5f
        repeat(5) { index ->
            val row = bottom - 140f * index / 4
            paint.color = Color.LTGRAY; canvas.drawLine(left, row, right, row, paint)
            paint.color = Color.DKGRAY
            canvas.drawText((data.bottom + (data.top - data.bottom) * index / 4).roundToInt().toString(), 36f, row + 3, paint)
        }
        val colors = if (data.pulse) listOf(Color.rgb(132, 98, 172)) else listOf(Color.rgb(102, 131, 203), Color.rgb(164, 123, 0))
        data.series.forEachIndexed { index, values ->
            paint.color = colors[index]; paint.strokeWidth = 1.5f
            values.indices.forEach { i ->
                val x = left + data.x(data.items[i]) * (right - left)
                val yy = bottom - data.y(values[i]) * 140
                if (i > 0) canvas.drawLine(left + data.x(data.items[i - 1]) * (right - left), bottom - data.y(values[i - 1]) * 140, x, yy, paint)
                canvas.drawCircle(x, yy, 2.5f, paint)
            }
        }
        paint.color = Color.DKGRAY; paint.textSize = 9f
        val format = if (data.items.first().measuredAt.atZone(zone).toLocalDate() == data.items.last().measuredAt.atZone(zone).toLocalDate()) timeFormat else dateFormat
        val ticks = if (data.first == data.last) listOf(0.5f) else listOf(0f, 0.5f, 1f)
        ticks.forEach { fraction ->
            val label = Instant.ofEpochMilli(data.first + ((data.last - data.first) * fraction).toLong()).atZone(zone).format(format)
            val x = (left + fraction * (right - left) - paint.measureText(label) / 2).coerceIn(36f, 559f - paint.measureText(label))
            canvas.drawText(label, x, bottom + 16, paint)
        }
        y = bottom + 34
    }
    fun tableHeader() {
        if (y + 70 > 794) newPage()
        row(listOf("Дата", "Лекарство", "Доза", "План", "Статус", "Факт"), true)
    }
    fun tableRow(cells: List<String>) {
        val height = cells.mapIndexed { i, v -> layout(v, widths[i] - 10, 9f).height }.max() + 12
        if (y + height > 794) { newPage(); tableHeader() }
        row(cells, false)
    }
    private fun row(cells: List<String>, header: Boolean) {
        val blocks = cells.mapIndexed { i, value -> layout(value, widths[i] - 10, 9f, header) }
        val height = blocks.maxOf { it.height } + 12f
        if (header) { paint.color = Color.rgb(231, 236, 248); canvas.drawRect(36f, y, 559f, y + height, paint) }
        var x = 36f
        blocks.forEachIndexed { i, block -> draw(block, x + 5, y + 6); x += widths[i] }
        paint.color = Color.LTGRAY; paint.strokeWidth = 0.5f
        canvas.drawLine(36f, y + height, 559f, y + height, paint)
        y += height
    }
}
