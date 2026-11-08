package io.github.voldemar64.healthapp.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class VoiceDialogTest {
    private val today = LocalDate.of(2026, 11, 8)
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val zone = ZoneOffset.UTC
    private fun VoiceDialog.say(text: String, data: HealthData = HealthData()) = accept(text, data, now, zone)

    @Test fun pressureSynonymsAndSpokenNumbersRequireConfirmation() {
        listOf("запиши", "добавь", "внеси", "записать").forEach { verb ->
            val dialog = VoiceDialog()
            dialog.say("$verb давление сто двадцать пять на восемьдесят два пульс шестьдесят семь")
            assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
            assertEquals(125, dialog.state.pressure!!.systolic)
            assertEquals(82, dialog.state.pressure!!.diastolic)
            assertEquals(67, dialog.state.pressure!!.pulse)
            dialog.say("не сохраняй")
            assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
            dialog.say("сохранить")
            assertEquals(DialogPhase.COMMIT, dialog.state.phase)
            val committed = dialog.state
            dialog.say("сохранить")
            assertSame(committed, dialog.state)
        }
    }

    @Test fun missingPulseIsAskedAndCorrectionIsReadAgain() {
        val dialog = VoiceDialog()
        dialog.say("давление 120 на 80")
        assertEquals(VoiceField.PULSE, dialog.state.field)
        dialog.say("шестьдесят")
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        dialog.say("исправить")
        dialog.say("пульс")
        dialog.say("не разобрал")
        assertEquals(DialogPhase.FIELD, dialog.state.phase)
        assertEquals(VoiceField.PULSE, dialog.state.field)
        dialog.say("семьдесят пять")
        assertEquals(75, dialog.state.pressure!!.pulse)
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        dialog.say("отмена")
        assertEquals(DialogPhase.CANCELLED, dialog.state.phase)
    }

    @Test fun medicationSupportsFractionsMultipleTimesAndWeeklyInflections() {
        val dialog = VoiceDialog()
        dialog.say("добавь лекарство тестол половину таблетки в девять тридцать и в восемь вечера по понедельникам и четвергам")
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        val draft = dialog.state.schedule!!
        assertEquals("тестол", draft.name)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), draft.weekdays)
        assertEquals(listOf(LocalTime.of(9, 30), LocalTime.of(20, 0)), draft.slots.map { it.time })
        assertTrue(draft.slots.all { it.dose.text == "0.5" && it.dose.unit == DoseUnit.TABLET })
    }

    @Test fun weeklyIsNotMistakenForOnceAndNewNameCanBeEnteredSeparately() {
        val dialog = VoiceDialog()
        dialog.say("добавь лекарство")
        assertEquals(VoiceField.NAME, dialog.state.field)
        dialog.say("Новое название")
        dialog.say("пять миллиграммов")
        dialog.say("в девять утра")
        dialog.say("один раз в неделю")
        assertEquals(VoiceField.DAYS, dialog.state.field)
        dialog.say("по средам")
        assertEquals(Repeat.WEEKLY, dialog.state.schedule!!.repeat)
        assertEquals("Новое название", dialog.state.schedule!!.name)
    }

    @Test fun ambiguousIntakeRequiresSelectionAndDoesNotFuzzyMatchNames() {
        val schedule = ScheduleDraft("Тестол", Repeat.DAILY, emptySet(), today, null,
            listOf(9, 18).map { DoseTime(time = LocalTime.of(it, 0), dose = Dose.parse("1", DoseUnit.TABLET)) })
        val data = HealthData(schedules = listOf(ScheduleRevision(from = today.atStartOfDay(), draft = schedule)))
        val dialog = VoiceDialog()
        dialog.say("принял тестол", data)
        assertEquals(DialogPhase.CHOICE, dialog.state.phase)
        dialog.say("второй не уверен", data)
        assertEquals(DialogPhase.CHOICE, dialog.state.phase)
        dialog.say("номер два", data)
        assertEquals(LocalTime.of(18, 0), dialog.state.selected!!.time)
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        val other = VoiceDialog()
        other.say("принял тестал", data)
        assertEquals("NOT_FOUND", other.state.error)
        assertNull(other.state.selected)
    }

    @Test fun manualCorrectionRequiresFreshReviewAndConfirmation() {
        val dialog = VoiceDialog()
        dialog.say("давление 120 на 80 пульс 65")
        dialog.edit(VoiceField.PULSE, "70")
        assertEquals(DialogPhase.CORRECTION, dialog.state.phase)
        dialog.say("сохранить")
        assertNotEquals(DialogPhase.COMMIT, dialog.state.phase)
        dialog.review(HealthData(), now, zone)
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        assertEquals(70, dialog.state.pressure!!.pulse)
    }

    @Test fun invalidMeasurementsNeverReachConfirmation() {
        listOf("давление 70 на 90 пульс 60", "давление 120 на 80 пульс 0", "давление 120 на 80 пульс 65 завтра").forEach {
            val dialog = VoiceDialog()
            dialog.say(it)
            assertEquals(DialogPhase.CORRECTION, dialog.state.phase)
            dialog.say("сохранить")
            assertNotEquals(DialogPhase.COMMIT, dialog.state.phase)
        }
    }

    @Test fun navigationReadingAndUnsupportedEditingDoNotMakeDrafts() {
        assertEquals(VoiceCommand.Navigate("pressure"), VoiceParser.parse("покажи статистику давления", today))
        assertEquals(VoiceCommand.Navigate("medications"), VoiceParser.parse("открой лекарства", today))
        assertEquals(VoiceCommand.ReadSchedule(today.plusDays(1)), VoiceParser.parse("прочитай расписание на завтра", today))
        assertEquals(VoiceCommand.ReadSchedule(today), VoiceParser.parse("что мне принимать сегодня", today))
        listOf("удали давление", "редактируй лекарство").forEach { assertEquals(VoiceCommand.Unknown, VoiceParser.parse(it, today)) }
    }
}
