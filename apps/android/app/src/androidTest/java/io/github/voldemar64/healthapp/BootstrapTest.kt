package io.github.voldemar64.healthapp

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootstrapTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearData() {
        (compose.activity.application as HealthApplication).database.clearAllTables()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Пока нет измерений")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun selectMedications() {
        compose.onNode(hasText("Лекарства") and hasClickAction()).performClick()
        compose.onNodeWithText("Пока нет расписания").assertIsDisplayed()
    }

    @Test
    fun startsOnPressureAndRepeatedTabSelectionDoesNotDuplicateBackStack() {
        assertEquals("ru", compose.activity.resources.configuration.locales[0].language)
        assertEquals("ru", java.util.Locale.getDefault().language)
        compose.onNodeWithText("Пока нет измерений").assertIsDisplayed()
        repeat(3) { selectMedications() }
        compose.onNode(hasText("Лекарства") and hasClickAction()).assertIsSelected()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Пока нет измерений").assertIsDisplayed()
        compose.onNode(hasText("Давление") and hasClickAction()).assertIsSelected()
    }

    @Test
    fun selectedTabSurvivesActivityRecreation() {
        selectMedications()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Пока нет расписания").assertIsDisplayed()
        compose.onNode(hasText("Лекарства") and hasClickAction()).assertIsSelected()
    }

    @Test
    fun rotationKeepsSelectedTabAndScrollableContent() {
        selectMedications()
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.onNodeWithText("Пока нет расписания").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Лекарства") and hasClickAction()).assertIsDisplayed()
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    @Test
    fun darkSystemModeStillUsesLightWindowAndComposeBackground() {
        val originalNightMode = Regex("Night mode: (\\w+)")
            .find(shell("cmd uimode night"))?.groupValues?.get(1)
            ?: error("Cannot read the original system night mode")
        shell("cmd uimode night yes")
        try {
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Пока нет измерений").assertIsDisplayed()
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            assertEquals(Color(0xFFEDECF2), pixels[0, 0])
            compose.activityRule.scenario.onActivity { activity ->
                val attrs = activity.obtainStyledAttributes(
                    intArrayOf(android.R.attr.windowLightStatusBar, android.R.attr.windowLightNavigationBar),
                )
                try {
                    assertEquals(true, attrs.getBoolean(0, false))
                    assertEquals(true, attrs.getBoolean(1, false))
                } finally {
                    attrs.recycle()
                }
            }
        } finally {
            shell("cmd uimode night $originalNightMode")
        }
    }

    @Test
    fun largeTextKeepsBothTabsAndEmptyStateReachable() {
        val originalScale = shell("settings get system font_scale").trim()
        shell("settings put system font_scale 2.0")
        try {
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale >= 1.99f }
            compose.onNode(hasText("Лекарства") and hasClickAction()).assertIsDisplayed().performClick()
            compose.onNodeWithText("Пока нет расписания").performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("Давление") and hasClickAction()).assertIsDisplayed().performClick()
            compose.onNodeWithText("Пока нет измерений").performScrollTo().assertIsDisplayed()
        } finally {
            if (originalScale == "null" || originalScale.isBlank()) {
                shell("settings delete system font_scale")
            } else {
                shell("settings put system font_scale $originalScale")
            }
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
