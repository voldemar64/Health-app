package io.github.voldemar64.healthapp.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer

class SpeechFailure(val code: String) : Exception(code)

class OfflineRecognizer(private val context: Context) : VoiceInput {
    private val lock = Mutex()
    private var model: Model? = null

    override suspend fun prepare() = withContext(Dispatchers.IO) { lock.withLock { prepareLocked() } }

    private fun prepareLocked(): Unit = synchronized(installationLock) {
        if (model != null) return
        LibVosk.vosk_set_log_level(-1)
        val directory = File(context.noBackupFilesDir, "vosk-ru-0.22-961d5ff9")
        val marker = File(directory, ".ready")
        if (!marker.exists()) {
            val temporary = File(context.noBackupFilesDir, "vosk-installing")
            temporary.deleteRecursively()
            temporary.mkdirs()
            try {
                context.assets.open("vosk-manifest.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split("  ", limit = 2)
                        check(parts.size == 2)
                        val file = File(temporary, parts[1]).canonicalFile
                        check(file.toPath().startsWith(temporary.canonicalFile.toPath()))
                        file.parentFile.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        context.assets.open("vosk/${parts[1]}").use { source -> file.outputStream().use { target ->
                            val bytes = ByteArray(65536)
                            while (true) {
                                val size = source.read(bytes)
                                if (size < 0) break
                                digest.update(bytes, 0, size)
                                target.write(bytes, 0, size)
                            }
                        } }
                        check(digest.digest().joinToString("") { "%02x".format(it) } == parts[0])
                    }
                }
                directory.deleteRecursively()
                check(temporary.renameTo(directory))
                marker.writeText("961d5ff9")
            } catch (failure: Exception) {
                temporary.deleteRecursively()
                throw SpeechFailure("MODEL")
            }
        }
        try { model = Model(directory.absolutePath) } catch (failure: Exception) {
            marker.delete()
            throw SpeechFailure("MODEL")
        }
    }

    override suspend fun listen(onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        lock.withLock {
            prepareLocked()
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SpeechFailure("PERMISSION")
            val buffer = maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
            try {
                if (audio.state != AudioRecord.STATE_INITIALIZED) throw SpeechFailure("MICROPHONE")
                Recognizer(model, 16000f).use { recognizer ->
                    audio.startRecording()
                    val samples = ShortArray(1600)
                    val started = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - started < 25000) {
                        currentCoroutineContext().ensureActive()
                        val count = audio.read(samples, 0, samples.size)
                        if (count <= 0) throw SpeechFailure("MICROPHONE")
                        if (recognizer.acceptWaveForm(samples, count)) {
                            val text = JSONObject(recognizer.result).optString("text")
                            if (text.isNotBlank()) return@withLock text
                        } else onPartial(JSONObject(recognizer.partialResult).optString("partial"))
                    }
                    JSONObject(recognizer.finalResult).optString("text")
                }
            } finally {
                if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                audio.release()
            }
        }
    }

    /** Used by instrumentation to exercise the native model with deterministic PCM fixtures. */
    suspend fun transcribe(samples: ShortArray): String = withContext(Dispatchers.IO) {
        lock.withLock {
            prepareLocked()
            Recognizer(model, 16000f).use { recognizer ->
                recognizer.acceptWaveForm(samples, samples.size)
                JSONObject(recognizer.finalResult).optString("text")
            }
        }
    }

    override suspend fun release() = withContext(Dispatchers.IO) { lock.withLock { model?.close(); model = null } }
    private companion object { val installationLock = Any() }
}
