package io.github.voldemar64.healthapp

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import io.github.voldemar64.healthapp.domain.*
import io.github.voldemar64.healthapp.speech.*
import io.github.voldemar64.healthapp.ui.*
import io.github.voldemar64.healthapp.ui.theme.HealthAppTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class VoiceFormTest {
    @get:Rule val compose = createComposeRule()
    @Test fun formRemainsVisibleWhileListeningAndNoLeavesEditableFields() {
        val app = ApplicationProvider.getApplicationContext<HealthApplication>()
        app.database.clearAllTables()
        val answers = Channel<String>(Channel.UNLIMITED)
        val input = object : VoiceInput {
            override suspend fun prepare() = Unit
            override suspend fun listen(context: SpeechContext, onPartial: (String) -> Unit) = answers.receive()
            override suspend fun release() = Unit
        }
        val output = object : VoiceOutput {
            override suspend fun prepare() = Unit
            override suspend fun speak(text: String) = Unit
            override fun stop() = Unit
            override fun release() = Unit
        }
        val store = ViewModelStore()
        lateinit var voice: VoiceViewModel
        lateinit var health: HealthViewModel
        compose.runOnUiThread {
            voice = VoiceViewModel(app, app.repository, input, output)
            health = HealthViewModel(app.repository)
            store.put("voice", voice); store.put("health", health)
        }
        try {
            compose.setContent {
                HealthAppTheme {
                    EntryScreen("pressure", HealthState(loaded = true), health, voice, PaddingValues(), {}, {})
                }
            }
            compose.runOnIdle { voice.start() }
            compose.waitUntil(15000) { voice.state.value.status == R.string.voice_listening }
            compose.onNodeWithText("Слушаю…").assertIsDisplayed()
            compose.onNodeWithText("Верхнее давление, мм рт. ст.").assertIsDisplayed()
            compose.onNodeWithText("Скажите давление и пульс.").assertDoesNotExist()
            runBlocking { answers.send("120 на 80 пульс 65") }
            compose.waitUntil(15000) { voice.state.value.dialog.phase == DialogPhase.CONFIRM }
            compose.onNodeWithText("120").assertExists()
            compose.onNodeWithText("Данные верны? Да, нет или исправить.").assertDoesNotExist()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(app.cacheDir, "voice-form.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            runBlocking { answers.send("нет") }
            compose.waitUntil(15000) { !voice.state.value.active }
            compose.onNodeWithText("Голосовой ввод").assertIsDisplayed()
            compose.onNodeWithText("Пульс, уд/мин").performScrollTo().performTextReplacement("72")
            compose.runOnIdle { assertEquals("72", voice.state.value.pressureForm.values[VoiceField.PULSE]) }
            runBlocking { assertTrue(app.repository.data.first().pressures.isEmpty()) }
        } finally {
            compose.runOnUiThread { store.clear() }
            app.database.clearAllTables()
        }
    }
}
