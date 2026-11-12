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
            // This fixture has a pause between pressure and pulse. Replay the live
            // first-endpoint behavior, not only batch transcription of all segments.
            // Insert another 0.8 s into the existing silence at 4 s, before "пульс".
            val paused = samples.copyOfRange(0, 64000) + ShortArray(12800) + samples.copyOfRange(64000, samples.size)
            for (context in listOf(SpeechContext.FREE, SpeechContext.PRESSURE)) {
                for (audio in listOf(samples, paused)) {
                    // A second utterance after 4 s of silence must not enter this turn.
                    val liveResult = recognizer.transcribe(audio + ShortArray(64000) + samples,
                        context, stopAtEndpoint = true)
                    val liveDraft = VoiceParser.fill(VoiceDraft(VoiceKind.PRESSURE), liveResult, LocalDate.now())
                    assertEquals("$context streaming: $liveResult", "120", liveDraft.values[VoiceField.SYSTOLIC])
                    assertEquals("$context streaming: $liveResult", "80", liveDraft.values[VoiceField.DIASTOLIC])
                    assertEquals("$context streaming: $liveResult", "65", liveDraft.values[VoiceField.PULSE])
                    assertEquals("Must stop before next utterance: $liveResult", 1,
                        Regex("\\bпульс\\b").findAll(liveResult).count())
                }
            }
            repeat(3) {
                val result = recognizer.transcribe(samples)
                val dialog = VoiceDialog()
                dialog.accept(result, HealthData(), Instant.now(), ZoneOffset.UTC)
                assertEquals("Synthetic fixture: $result", DialogPhase.CONFIRM, dialog.state.phase)
                assertEquals("Synthetic fixture: $result", 120, dialog.state.pressure!!.systolic)
                assertEquals("Synthetic fixture: $result", 80, dialog.state.pressure!!.diastolic)
                assertEquals("Synthetic fixture: $result", 65, dialog.state.pressure!!.pulse)
            }
            assertTrue(recognizer.transcribe(ShortArray(32000)).isBlank())
            SpeechContext.entries.filter { it != SpeechContext.FREE }.forEach { context ->
                val silence = recognizer.transcribe(ShortArray(32000), context)
                assertTrue("$context: $silence", silence.isBlank() || silence == "[unk]")
            }
            val closed = recognizer.transcribe(samples, SpeechContext.CONFIRM)
            assertFalse("Full pressure phrase must not confirm: $closed", closed == "да")
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
            assertEquals(SpeechContext.PRESSURE, input.contexts.first())
            assertTrue(output.spoken.contains("Данные верны? Да, нет или исправить."))
            assertFalse(output.spoken.any { it.contains("120") || it.contains("65") })
            assertEquals(SpeechContext.CONFIRM, input.contexts.last())
            assertTrue(app.repository.data.first().pressures.isEmpty())
            withContext(Dispatchers.Main) { model.pause() }
            await { !input.listening }
            assertFalse(model.state.value.active)
            withContext(Dispatchers.Main) { model.start() }
            input.commands.send("да")
            await { app.repository.data.first().pressures.size == 1 }
            input.commands.send("да")
            await { model.state.value.saved }
            assertEquals(1, app.repository.data.first().pressures.size)
            assertFalse(output.spokeWhileListening)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.database.clearAllTables()
        }
    }

    @Test fun noPreservesSharedFormAndThreeUnknownRepliesPause() = runBlocking {
        app.database.clearAllTables()
        val input = FakeInput()
        val output = FakeOutput { input.listening }
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            VoiceViewModel(app, app.repository, input, output).also {
                store.put("voice", it); it.selectKind(VoiceKind.PRESSURE); it.start()
            }
        }
        try {
            input.commands.send("120 на 80 пульс 65")
            await { model.state.value.dialog.phase == DialogPhase.CONFIRM && input.listening }
            input.commands.send("нет")
            await { !model.state.value.active }
            assertEquals("120", model.state.value.pressureForm.values[VoiceField.SYSTOLIC])
            assertTrue(app.repository.data.first().pressures.isEmpty())
            withContext(Dispatchers.Main) { model.start() }
            repeat(3) { input.commands.send("непонятный ответ") }
            await { output.spoken.contains("Не удалось понять. Исправьте в форме.") && !model.state.value.active }
            assertTrue(app.repository.data.first().pressures.isEmpty())
            assertEquals("65", model.state.value.pressureForm.values[VoiceField.PULSE])
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.database.clearAllTables()
        }
    }

    @Test fun backgroundDuringCommitStillReportsSuccessAndDoesNotRepeatWrite() = runBlocking {
        app.database.clearAllTables()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val repository = object : HealthRepository by app.repository {
            override suspend fun savePressure(measurement: PressureMeasurement) {
                entered.complete(Unit); release.await(); writes++
                app.repository.savePressure(measurement)
            }
        }
        val input = FakeInput()
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            VoiceViewModel(app, repository, input, FakeOutput { input.listening }).also { store.put("voice", it); it.start() }
        }
        try {
            input.commands.send("давление 120 на 80 пульс 65"); input.commands.send("да")
            withTimeout(15000) { entered.await() }
            withContext(Dispatchers.Main) {
                model.pause()
                model.updateDraft(VoiceDraft(VoiceKind.PRESSURE, mapOf(VoiceField.SYSTOLIC to "999")))
            }
            release.complete(Unit)
            await { model.state.value.saved }
            withContext(Dispatchers.Main) { model.start() }
            assertEquals(1, writes)
            assertEquals(120, app.repository.data.first().pressures.single().systolic)
        } finally {
            release.complete(Unit)
            withContext(Dispatchers.Main) { store.clear() }
            app.database.clearAllTables()
        }
    }

    @Test fun savedStateRestoresBothDraftsWithoutStartingMicrophone() = runBlocking {
        val state = androidx.lifecycle.SavedStateHandle()
        val input = FakeInput()
        withContext(Dispatchers.Main) {
            val first = VoiceViewModel(app, app.repository, input, FakeOutput { input.listening }, state)
            first.initialize(VoiceKind.PRESSURE)
            first.updateDraft(first.state.value.pressureForm.copy(values = first.state.value.pressureForm.values + (VoiceField.PULSE to "72")))
            first.selectKind(VoiceKind.MEDICATION)
            first.updateDraft(first.state.value.medicationForm.copy(values = first.state.value.medicationForm.values + (VoiceField.NAME to "Черновик")))
            val saved = androidx.lifecycle.SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })
            val restored = VoiceViewModel(app, app.repository, input, FakeOutput { input.listening }, saved)
            restored.initialize(VoiceKind.PRESSURE)
            assertEquals(VoiceKind.MEDICATION, restored.state.value.kind)
            assertEquals("72", restored.state.value.pressureForm.values[VoiceField.PULSE])
            assertEquals("Черновик", restored.state.value.medicationForm.values[VoiceField.NAME])
            assertFalse(restored.state.value.active)
            assertFalse(input.prepared)
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
        val contexts = java.util.concurrent.CopyOnWriteArrayList<SpeechContext>()
        @Volatile var listening = false
        @Volatile var prepared = false
        override suspend fun prepare() { prepared = true }
        override suspend fun listen(context: SpeechContext, onPartial: (String) -> Unit): String {
            contexts += context
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
