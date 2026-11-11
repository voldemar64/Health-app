package io.github.voldemar64.healthapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.voldemar64.healthapp.domain.*
import io.github.voldemar64.healthapp.ui.ReportSnapshot
import io.github.voldemar64.healthapp.ui.renderHealthReport
import java.io.File
import java.time.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportTest {
    @Test fun exportsEmptySingleAndMultipageReportsWithHistoricalFacts() {
        val app = ApplicationProvider.getApplicationContext<HealthApplication>()
        val directory = File(app.getExternalFilesDir(null), "qa-reports").apply { mkdirs() }
        val end = LocalDate.of(2026, 11, 11)
        val start = end.minusDays(29)
        val zone = ZoneId.of("Europe/Moscow")
        val now = end.atTime(12, 0).atZone(zone).toInstant()
        val pressures = (0..29).map { day -> PressureMeasurement(systolic = 120 + day % 10, diastolic = 80 + day % 5,
            pulse = 60 + day % 15, measuredAt = start.plusDays(day.toLong()).atTime(10, 0).atZone(zone).toInstant(), recordedZoneId = zone.id) }
        val schedule = ScheduleRevision(from = start.atStartOfDay(), draft = ScheduleDraft(
            "Тестовое лекарство с длинным названием для проверки переноса строк в отчёте",
            Repeat.DAILY, emptySet(), start, null, listOf(
                DoseTime("am", LocalTime.of(9, 0), Dose.parse("0.5", DoseUnit.TABLET)),
                DoseTime("pm", LocalTime.of(18, 0), Dose.parse("2.5", DoseUnit.MG)))))
        val base = HealthData(pressures = pressures, schedules = listOf(schedule))
        val events = occurrences(base, start, end)
        val facts = events.filterIndexed { index, _ -> index % 3 == 0 }.mapIndexed { index, occurrence ->
            val status = if (index == 1) IntakeStatus.SKIPPED else IntakeStatus.TAKEN
            IntakeRecord(if (index == 0) occurrence.copy(name = "Историческое название", dose = Dose.parse("0.25", DoseUnit.TABLET)) else occurrence,
                status, occurrence.instant(zone), zone.id, if (status == IntakeStatus.TAKEN) occurrence.instant(zone).plusSeconds(120) else null)
        }
        val cases = mapOf("empty" to HealthData(), "single" to HealthData(pressures = pressures.take(1)), "month" to base.copy(intakes = facts))
        cases.forEach { (name, data) ->
            val bytes = renderHealthReport(ReportSnapshot(data, start, end, now, zone))
            assertTrue(String(bytes.take(4).toByteArray(), Charsets.US_ASCII) == "%PDF")
            val pdf = File(directory, "$name.pdf").apply { writeBytes(bytes) }
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertTrue(renderer.pageCount >= 1)
                if (name == "month") assertTrue(renderer.pageCount >= 3)
                for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(directory, "$name-${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }
}
