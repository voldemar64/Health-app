package io.github.voldemar64.healthapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.voldemar64.healthapp.domain.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as HealthApplication
    @Before fun clearData() {
        app.database.clearAllTables()
        waitText("Пока нет измерений")
    }
    @After fun cleanup() { app.database.clearAllTables() }
    private fun waitText(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            // Only synthetic test fixtures are present; expose the failed UI assertion in CI reports.
            compose.onRoot().printToLog("ManualEntryTest")
            throw failure
        }
    }
    private fun click(text: String) {
        val node = compose.onNode(hasText(text) and hasClickAction())
        if (text != "Лекарства" && text != "Давление") node.performScrollTo()
        node.performClick()
    }
    private fun type(label: String, value: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }

    @Test fun unifiedEntryKeepsIndependentDraftsAndPulseIsRequired() {
        compose.onNodeWithContentDescription("Добавить запись").performClick()
        type("Верхнее давление, мм рт. ст.", "120")
        type("Нижнее давление, мм рт. ст.", "80")
        compose.onNode(hasText("Лекарства") and hasClickAction()).performScrollTo().performClick()
        type("Название лекарства", "Новое лекарство")
        compose.onNode(hasText("Давление") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("120").assertExists()
        click("Сохранить")
        waitText("Введите пульс: целое число от 1 до 999. Поле обязательно.")
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNode(hasText("Лекарства") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Новое лекарство").assertExists()
        click("Голосовой ввод")
        compose.onNodeWithText("Начать / продолжить разговор").performScrollTo().assertIsDisplayed()
        runBlocking { assertTrue(app.repository.data.first().pressures.isEmpty()) }
    }

    @Test fun chartShowsScaleAndSeparatePulseSeries() {
        val now = Instant.now()
        runBlocking {
            (0..6).forEach { day -> app.repository.savePressure(PressureMeasurement(
                systolic = 118 + day * 3, diastolic = 78 + day, pulse = 63 + day,
                measuredAt = now.minusSeconds((6 - day) * 86400L + 60), recordedZoneId = ZoneId.systemDefault().id)) }
        }
        waitText("Давление · мм рт. ст.")
        compose.onNode(hasContentDescription("Давление · мм рт. ст.", substring = true), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.cacheDir, "pressure-chart.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithText("Пульс · уд/мин").performScrollTo().assertIsDisplayed()
    }

    @Test fun pressureDraftSurvivesRecreationAndSaveUpdatesStatisticsThenEditAndDelete() {
        compose.onNodeWithContentDescription("Добавить запись").performClick()
        type("Верхнее давление, мм рт. ст.", "120")
        type("Нижнее давление, мм рт. ст.", "80")
        type("Пульс, уд/мин", "65")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("120").assertExists()
        click("Сохранить")
        waitText("120/80 мм рт. ст.")
        compose.onNodeWithText("Среднее: 120/80 мм рт. ст.\nИзмерений: 1").assertExists()
        compose.activityRule.scenario.recreate()
        waitText("120/80 мм рт. ст.")
        click("Изменить")
        type("Верхнее давление, мм рт. ст.", "130")
        click("Сохранить")
        waitText("130/80 мм рт. ст.")
        click("Удалить")
        compose.onNodeWithText("Удалить измерение?").assertIsDisplayed()
        compose.onAllNodes(hasText("Удалить") and hasClickAction()).onLast().performClick()
        waitText("Пока нет измерений")
        runBlocking { assertTrue(app.repository.data.first().pressures.isEmpty()) }
    }

    @Test fun invalidPressureDoesNotLeaveFormOrWriteData() {
        compose.onNodeWithContentDescription("Добавить запись").performClick()
        type("Верхнее давление, мм рт. ст.", "70")
        type("Нижнее давление, мм рт. ст.", "90")
        click("Сохранить")
        waitText("Не удалось выполнить действие")
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithText("Новое измерение").assertExists()
        runBlocking { assertTrue(app.repository.data.first().pressures.isEmpty()) }
    }

    @Test fun medicationFormStoresFractionAndWeeklyDaysAcrossRecreation() {
        click("Лекарства")
        compose.onNodeWithContentDescription("Добавить запись").performClick()
        type("Название лекарства", "Тестовое лекарство")
        click("По дням недели")
        click("понедельник")
        click("четверг")
        type("Количество", "0,5")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("0,5").assertExists()
        click("Сохранить")
        waitText("Расписание: Тестовое лекарство")
        runBlocking {
            val draft = app.repository.data.first().schedules.single().draft
            assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), draft.weekdays)
            assertEquals("0.5", draft.slots.single().dose.text)
        }
        click("Неделя")
        click("Месяц")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasText("Месяц") and hasClickAction()).assertIsSelected()
    }

    @Test fun takenSkippedUndoAndFutureSingleEditUseRealStorage() {
        val today = LocalDate.now()
        runBlocking {
            app.repository.saveSchedule(ScheduleDraft("Тест", Repeat.DAILY, emptySet(), today, null,
                listOf(DoseTime(time = LocalTime.of(9, 0), dose = Dose.parse("1", DoseUnit.TABLET)))))
        }
        click("Лекарства")
        waitText("Тест")
        click("Принято")
        click("Подтвердить приём")
        waitText("Изменить фактическое время")
        click("Пропущено")
        compose.waitUntil(10_000) { runBlocking { app.repository.data.first().intakes.singleOrNull()?.status == IntakeStatus.SKIPPED } }
        click("Отменить отметку")
        compose.waitUntil(10_000) { runBlocking { app.repository.data.first().intakes.isEmpty() } }
        click("Вперёд")
        click("Изменить только этот приём")
        type("Количество", "0,25")
        click("Сохранить")
        waitText("09:00 · 0,25 табл.")
        compose.activityRule.scenario.recreate()
        waitText("09:00 · 0,25 табл.")
    }

    @Test fun largeTextKeepsFormsAndMultipleDoseDraftReachable() {
        val original = shell("settings get system font_scale").trim()
        shell("settings put system font_scale 2.0")
        try {
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale >= 1.99f }
            compose.onNodeWithContentDescription("Добавить запись").performClick()
            type("Верхнее давление, мм рт. ст.", "120")
            type("Нижнее давление, мм рт. ст.", "80")
            type("Пульс, уд/мин", "65")
            click("Сохранить")
            waitText("120/80 мм рт. ст.")
            click("Лекарства")
            compose.onNodeWithContentDescription("Добавить запись").performClick()
            type("Название лекарства", "Две дозы")
            click("Добавить время")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Приём 2").performScrollTo().assertIsDisplayed()
            click("Сохранить")
            waitText("Расписание: Две дозы")
            runBlocking { assertEquals(2, app.repository.data.first().schedules.single().draft.slots.size) }
            click("Месяц")
            compose.onNodeWithText("Все расписания").performScrollTo().assertIsDisplayed()
        } finally {
            shell(if (original == "null" || original.isBlank()) "settings delete system font_scale"
                else "settings put system font_scale $original")
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
