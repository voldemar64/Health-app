package io.github.voldemar64.healthapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import io.github.voldemar64.healthapp.domain.*
import io.github.voldemar64.healthapp.speech.*
import io.github.voldemar64.healthapp.ui.VoiceViewModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechTest {
    private val app get() = ApplicationProvider.getApplicationContext<HealthApplication>()

    @Test fun systemSynthesisSpeaksOrExplicitlyReportsMissingOfflineVoice() = runBlocking {
        val output = OfflineSpeechOutput(app)
        try {
            output.prepare()
            output.speak("Проверка русского голоса")
        } catch (expected: SpeechFailure) {
            assertEquals("VOICE_MISSING", expected.code)
        } finally { withContext(Dispatchers.Main) { output.release() } }
    }

    @Test fun bundledNativeModelTranscribesRussianAndIgnoresSilenceWithoutNetwork() = runBlocking {
        val recognizer = OfflineRecognizer(app)
        try {
            val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("pressure-ru.wav").use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            var samples = ShortArray(0)
            while (offset + 8 <= bytes.size) {
                val name = String(bytes, offset, 4, Charsets.US_ASCII)
                val length = buffer.getInt(offset + 4)
                if (name == "fmt ") {
                    assertEquals(1, buffer.getShort(offset + 8).toInt())
                    assertEquals(1, buffer.getShort(offset + 10).toInt())
                    assertEquals(16000, buffer.getInt(offset + 12))
                    assertEquals(16, buffer.getShort(offset + 22).toInt())
                }
                if (name == "data") samples = ShortArray(length / 2) { buffer.getShort(offset + 8 + it * 2) }
                offset += 8 + length + length % 2
            }
            assertTrue(samples.isNotEmpty())
            val result = recognizer.transcribe(samples)
            val dialog = VoiceDialog()
            dialog.accept(result, HealthData(), Instant.now(), ZoneOffset.UTC)
            assertEquals("Synthetic fixture: $result", DialogPhase.CONFIRM, dialog.state.phase)
            assertEquals(120, dialog.state.pressure!!.systolic)
            assertEquals(80, dialog.state.pressure!!.diastolic)
            assertEquals(65, dialog.state.pressure!!.pulse)
            assertTrue(recognizer.transcribe(ShortArray(32000)).isBlank())
            val permissions = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
        } finally { recognizer.release() }
    }

    @Test fun dialogReadsBeforeSavingPausesAndDoesNotDuplicateConfirmation() = runBlocking {
        app.database.clearAllTables()
        val input = FakeInput()
        val output = FakeOutput { input.listening }
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            VoiceViewModel(app, app.repository, input, output).also { store.put("voice", it); it.start() }
        }
        try {
            input.commands.send("запиши давление 120 на 80 пульс 65")
            await { model.state.value.status == R.string.voice_listening && model.state.value.dialog.phase == DialogPhase.CONFIRM }
            assertTrue(output.spoken.any { it.contains("120") && it.contains("65") && it.contains("сохранить") })
            assertTrue(app.repository.data.first().pressures.isEmpty())
            withContext(Dispatchers.Main) { model.pause() }
            await { !input.listening }
            assertFalse(model.state.value.active)
            withContext(Dispatchers.Main) { model.start() }
            input.commands.send("сохранить")
            await { app.repository.data.first().pressures.size == 1 }
            input.commands.send("сохранить")
            await { model.state.value.dialog.error == "UNKNOWN" }
            assertEquals(1, app.repository.data.first().pressures.size)
            assertFalse(output.spokeWhileListening)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.database.clearAllTables()
        }
    }

    @Test fun missingOfflineVoicePreventsMicrophoneActivation() = runBlocking {
        val input = FakeInput()
        val output = object : VoiceOutput {
            override suspend fun prepare(): Unit = throw SpeechFailure("VOICE_MISSING")
            override suspend fun speak(text: String) = Unit
            override fun stop() = Unit
            override fun release() = Unit
        }
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            VoiceViewModel(app, app.repository, input, output).also { store.put("voice", it); it.start() }
        }
        try {
            await { model.state.value.error == "VOICE_MISSING" }
            assertFalse(input.prepared)
            assertFalse(input.listening)
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(15000) {
        while (!condition()) delay(25)
    }
    private inner class FakeInput : VoiceInput {
        val commands = Channel<String>(Channel.UNLIMITED)
        @Volatile var listening = false
        @Volatile var prepared = false
        override suspend fun prepare() { prepared = true }
        override suspend fun listen(onPartial: (String) -> Unit): String {
            listening = true
            return try { commands.receive() } finally { listening = false }
        }
        override suspend fun release() = Unit
    }
    private class FakeOutput(private val listening: () -> Boolean) : VoiceOutput {
        val spoken = java.util.concurrent.CopyOnWriteArrayList<String>()
        var spokeWhileListening = false
        override suspend fun prepare() = Unit
        override suspend fun speak(text: String) { spokeWhileListening = spokeWhileListening || listening(); spoken += text }
        override fun stop() = Unit
        override fun release() = Unit
    }
}
