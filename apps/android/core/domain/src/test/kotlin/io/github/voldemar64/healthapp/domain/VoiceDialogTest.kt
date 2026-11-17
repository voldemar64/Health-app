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

    @Test fun optionalPulseCanBeAddedDuringCorrection() {
        val dialog = VoiceDialog()
        dialog.say("давление 120 на 80")
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        assertNull(dialog.state.pressure!!.pulse)
        dialog.say("исправь пульс на семьдесят пять")
        assertEquals(75, dialog.state.pressure!!.pulse)
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
        dialog.say("таблетка")
        dialog.say("не знаю")
        dialog.say("не знаю")
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
        dialog.say("раствор")
        dialog.say("не знаю")
        dialog.say("не знаю")
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
        dialog.say("да", data)
        assertEquals(DialogPhase.COMMIT, dialog.state.phase)
        val other = VoiceDialog()
        other.say("принял тестал", data)
        assertEquals("NOT_FOUND", other.state.error)
        assertNull(other.state.selected)
    }

    @Test fun seededPressureAcceptsValuesWithoutCommandKeyword() {
        val dialog = VoiceDialog()
        dialog.seed(VoiceDraft(VoiceKind.PRESSURE))
        dialog.say("сто двадцать на восемьдесят пульс шестьдесят пять")
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        assertEquals(120, dialog.state.pressure!!.systolic)
        assertEquals(80, dialog.state.pressure!!.diastolic)
        assertEquals(65, dialog.state.pressure!!.pulse)
    }

    @Test fun noPausesAndRetainsDraftInsteadOfSavingOrClearing() {
        val dialog = VoiceDialog()
        dialog.say("давление 120 на 80 пульс 65")
        val original = dialog.state
        dialog.say("нет")
        assertEquals(DialogPhase.PAUSED, dialog.state.phase)
        assertEquals(original.draft, dialog.state.draft)
        assertEquals(original.pressure, dialog.state.pressure)
        dialog.review(HealthData(), now, zone)
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
    }

    @Test fun confirmationRequiresWholeAnswerNotEmbeddedYes() {
        val dialog = VoiceDialog()
        dialog.say("давление 120 на 80 пульс 65")
        listOf("не надо", "да но исправить", "да позже", "[unk]", "").forEach { answer ->
            dialog.say(answer)
            assertEquals(answer, DialogPhase.CONFIRM, dialog.state.phase)
            assertEquals("CONFIRM", dialog.state.error)
        }
        dialog.say("Да.")
        assertEquals(DialogPhase.COMMIT, dialog.state.phase)
        val committed = dialog.state
        dialog.say("да")
        assertSame(committed, dialog.state)
    }

    @Test fun directIntakeAndSkipRequireConfirmationToday() {
        val schedule = ScheduleDraft("Тестол", Repeat.DAILY, emptySet(), today.minusDays(1), null,
            listOf(DoseTime(time = LocalTime.of(9, 0), dose = Dose.parse("1", DoseUnit.TABLET))))
        val data = HealthData(schedules = listOf(ScheduleRevision(from = today.minusDays(1).atStartOfDay(), draft = schedule)))
        val dialog = VoiceDialog()
        dialog.say("принял тестол", data)
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        dialog.say("да", data)
        assertEquals(DialogPhase.COMMIT, dialog.state.phase)
        assertEquals(today, dialog.state.selected!!.date)
        listOf("вчера", "завтра").forEach { date ->
            val historical = VoiceDialog()
            historical.say("принял тестол $date", data)
            assertNotEquals(DialogPhase.COMMIT, historical.state.phase)
            assertEquals("FUTURE", historical.state.error)
        }
        val skipped = VoiceDialog()
        skipped.say("пропустил тестол", data)
        assertEquals(DialogPhase.CONFIRM, skipped.state.phase)
        assertEquals("SKIPPED", skipped.state.draft?.values?.get(VoiceField.STATUS))
        skipped.say("да", data)
        assertEquals(DialogPhase.COMMIT, skipped.state.phase)
    }

    @Test fun unrelatedSpeechCorrectionKeepsManualPerSlotDoses() {
        val slots = listOf(VoiceDose("morning", "09:00", "0.5", "TABLET"),
            VoiceDose("evening", "18:00", "5", "TABLET"))
        val draft = VoiceDraft(VoiceKind.MEDICATION, mapOf(
            VoiceField.NAME to "Тестол", VoiceField.AMOUNT to "0.5", VoiceField.UNIT to "TABLET",
            VoiceField.TIMES to "09:00;18:00", VoiceField.REPEAT to "DAILY",
            VoiceField.FORM to "таблетка", VoiceField.STRENGTH to "UNKNOWN", VoiceField.STOCK to "UNKNOWN"), slots = slots)
        val dialog = VoiceDialog()
        dialog.seed(draft)
        dialog.review(HealthData(), now, zone)
        dialog.say("исправить")
        dialog.say("повтор")
        dialog.say("по понедельникам и четвергам")
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        assertEquals(slots, dialog.state.draft!!.slots)
        assertEquals(Repeat.WEEKLY, dialog.state.schedule!!.repeat)
        assertEquals(listOf("0.5", "5"), dialog.state.schedule!!.slots.map { it.dose.text })
        assertEquals(listOf(DoseUnit.TABLET, DoseUnit.TABLET), dialog.state.schedule!!.slots.map { it.dose.unit })
        assertEquals(listOf("morning", "evening"), dialog.state.schedule!!.slots.map { it.id })
    }

    @Test fun changingTimesPreservesDifferentDosesAndRejectsAmbiguousSlotCount() {
        val slots = listOf(VoiceDose("am", "09:00", "0.5", "TABLET"), VoiceDose("pm", "18:00", "1", "TABLET"))
        val draft = VoiceDraft(VoiceKind.MEDICATION, mapOf(VoiceField.NAME to "Тест",
            VoiceField.AMOUNT to "0.5", VoiceField.UNIT to "TABLET", VoiceField.TIMES to "09:00;18:00", VoiceField.REPEAT to "DAILY",
            VoiceField.FORM to "таблетка", VoiceField.STRENGTH to "UNKNOWN", VoiceField.STOCK to "UNKNOWN"), slots = slots)
        val dialog = VoiceDialog()
        dialog.seed(draft)
        dialog.review(HealthData(), now, zone)
        dialog.say("исправить"); dialog.say("время"); dialog.say("в десять и в девятнадцать")
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        assertEquals(listOf("0.5", "1"), dialog.state.schedule!!.slots.map { it.dose.text })
        assertEquals(listOf(LocalTime.of(10, 0), LocalTime.of(19, 0)), dialog.state.schedule!!.slots.map { it.time })
        dialog.say("исправить"); dialog.say("время"); dialog.say("в десять")
        assertEquals(DialogPhase.FIELD, dialog.state.phase)
        assertEquals("TIMES", dialog.state.error)
        assertEquals(listOf("0.5", "1"), dialog.state.draft!!.slots!!.map { it.amount })
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
        listOf("давление 120 на 80 пульс 0", "давление 120 на 80 пульс 65 завтра").forEach {
            val dialog = VoiceDialog()
            dialog.say(it)
            assertEquals(DialogPhase.CORRECTION, dialog.state.phase)
            dialog.say("сохранить")
            assertNotEquals(DialogPhase.COMMIT, dialog.state.phase)
        }
        val unusual = VoiceDialog()
        unusual.say("давление 70 на 90 пульс 60")
        assertEquals(DialogPhase.CONFIRM, unusual.state.phase)
    }

    @Test fun navigationReadingAndUnsupportedEditingDoNotMakeDrafts() {
        assertEquals(VoiceCommand.Navigate("pressure"), VoiceParser.parse("покажи статистику давления", today))
        assertEquals(VoiceCommand.Navigate("medications"), VoiceParser.parse("открой лекарства", today))
        assertEquals(VoiceCommand.ReadSchedule(today.plusDays(1)), VoiceParser.parse("прочитай расписание на завтра", today))
        assertEquals(VoiceCommand.ReadSchedule(today), VoiceParser.parse("что мне принимать сегодня", today))
        listOf("удали давление", "редактируй лекарство").forEach { assertEquals(VoiceCommand.Unknown, VoiceParser.parse(it, today)) }
    }

    @Test fun voiceTimerAndStockWaitForWholeConfirmation() {
        val card = UserMedication("card", LEGACY_PROFILE_ID, "Тестол", "Таблетка", "40 мг",
            DoseUnit.TABLET, true, null)
        val data = HealthData(medications = listOf(card))
        val timer = VoiceDialog()
        timer.say("напомни выпить Тестол 1 таблетку через пятнадцать минут", data)
        assertEquals(DialogPhase.CONFIRM, timer.state.phase)
        assertEquals("card", timer.state.selectedMedicationId)
        assertEquals("1", timer.state.timerAmount?.toPlainString())
        timer.say("да позже", data)
        assertEquals(DialogPhase.CONFIRM, timer.state.phase)
        timer.say("да", data)
        assertEquals(DialogPhase.COMMIT, timer.state.phase)

        val stock = VoiceDialog()
        stock.say("пополни остаток Тестол на 0,5 таблетки", data)
        assertEquals(DialogPhase.CONFIRM, stock.state.phase)
        assertEquals("0.5", stock.state.stockChange?.amount?.toPlainString())
        stock.say("отмена", data)
        assertEquals(DialogPhase.CANCELLED, stock.state.phase)
    }

    @Test fun absoluteReminderUsesLocalTimeAndNeedsConfirmation() {
        val command = VoiceParser.parse("напомни измерить давление сегодня в 18:30", today)
        assertEquals(VoiceCommand.AtReminder("измерить давление", today.atTime(18, 30)), command)
        val dialog = VoiceDialog()
        dialog.say("напомни измерить давление сегодня в 18:30")
        assertEquals(DialogPhase.CONFIRM, dialog.state.phase)
        assertEquals(today.atTime(18, 30), dialog.state.atReminder?.at)
        dialog.say("нет")
        assertEquals(DialogPhase.PAUSED, dialog.state.phase)
        val accepted = VoiceDialog()
        accepted.say("напомни измерить давление завтра в 09:00")
        accepted.say("да")
        assertEquals(DialogPhase.COMMIT, accepted.state.phase)
        val past = VoiceDialog()
        past.say("напомни измерить давление сегодня в 09:00")
        assertNotEquals(DialogPhase.COMMIT, past.state.phase)
    }

    @Test fun voicePostponeAndMarkAllListTargetsBeforeConfirmation() {
        val draft = ScheduleDraft("Тестол", Repeat.DAILY, emptySet(), today, null,
            listOf(DoseTime("am", LocalTime.of(9, 0), Dose.parse("1", DoseUnit.TABLET))))
        val data = HealthData(schedules = listOf(ScheduleRevision("r", "s", today.atStartOfDay(),
            draft = draft)))
        val postpone = VoiceDialog()
        postpone.say("отложи Тестол на 15 минут", data)
        assertEquals(DialogPhase.CONFIRM, postpone.state.phase)
        assertEquals("s/$today/am", postpone.state.selected?.key)
        val all = VoiceDialog()
        all.say("принял все", data)
        assertEquals(DialogPhase.CONFIRM, all.state.phase)
        assertEquals(1, all.state.selectedMany.size)
    }
}
