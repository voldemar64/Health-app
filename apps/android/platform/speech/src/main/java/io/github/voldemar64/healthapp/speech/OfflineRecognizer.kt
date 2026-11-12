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
import org.json.JSONArray
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

    override suspend fun listen(context: SpeechContext, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        lock.withLock {
            prepareLocked()
            if (this@OfflineRecognizer.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SpeechFailure("PERMISSION")
            val buffer = maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
            try {
                if (audio.state != AudioRecord.STATE_INITIALIZED) throw SpeechFailure("MICROPHONE")
                createRecognizer(context).use { recognizer ->
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
    suspend fun transcribe(samples: ShortArray, context: SpeechContext = SpeechContext.FREE,
        stopAtEndpoint: Boolean = false): String = withContext(Dispatchers.IO) {
        lock.withLock {
            prepareLocked()
            createRecognizer(context).use { recognizer ->
                val segments = mutableListOf<String>()
                // Match AudioRecord's 100 ms frames and consume every completed utterance.
                // A fixture can contain several endpointed phrases, unlike one listen() turn.
                var offset = 0
                while (offset < samples.size) {
                    val frame = samples.copyOfRange(offset, minOf(offset + 1600, samples.size))
                    if (recognizer.acceptWaveForm(frame, frame.size)) {
                        val text = JSONObject(recognizer.result).optString("text")
                        if (stopAtEndpoint && text.isNotBlank()) return@withLock text
                        segments += text
                    }
                    offset += frame.size
                }
                segments += JSONObject(recognizer.finalResult).optString("text")
                segments.filter(String::isNotBlank).joinToString(" ")
            }
        }
    }

    private fun createRecognizer(context: SpeechContext): Recognizer {
        val phrases = context.phrases()
        val recognizer = if (phrases == null) Recognizer(model, 16000f)
            else Recognizer(model, 16000f, JSONArray(phrases).toString())
        try {
            if (context == SpeechContext.PRESSURE || context == SpeechContext.FREE) {
                // Keep natural pauses inside measurements and medication schedules.
                // Vosk's trailing-silence rules become 2.5/3.0/3.5 s; short replies
                // retain their default endpoint rules. listen() still caps a turn at 25 s.
                recognizer.setEndpointerDelays(25f, 2.5f, 25f)
            }
            return recognizer
        } catch (failure: Exception) {
            recognizer.close()
            throw failure
        }
    }

    override suspend fun release() = withContext(Dispatchers.IO) { lock.withLock { model?.close(); model = null } }
    private companion object { val installationLock = Any() }
}

/** Pressure entry and closed answers use their own vocabulary; medication names remain free. */
internal fun SpeechContext.phrases(): List<String>? {
    val numbers = ("ноль нуль один одна одну два две три четыре пять шесть семь восемь девять десять " +
        "одиннадцать двенадцать тринадцать четырнадцать пятнадцать шестнадцать семнадцать восемнадцать девятнадцать " +
        "двадцать тридцать сорок пятьдесят шестьдесят семьдесят восемьдесят девяносто " +
        "сто двести триста четыреста пятьсот шестьсот семьсот восемьсот девятьсот тысяча").split(' ')
    val units = listOf("таблетка", "таблетки", "таблетку", "таблеток", "миллиграмм", "миллиграмма", "миллиграммов",
        "грамм", "грамма", "граммов")
    val vocabulary = when (this) {
        SpeechContext.FREE -> return null
        SpeechContext.PRESSURE -> numbers + listOf("давление", "измерение", "запиши", "записать", "добавь", "добавить",
            "на", "пульс", "сердцебиение", "верхнее", "нижнее", "и", "у", "меня", "пожалуйста",
            "сегодня", "вчера", "позавчера", "в", "утра", "дня", "вечера", "ночи", "час", "часа", "часов", "минут",
            "открой давление", "открой лекарства", "открой календарь", "покажи статистику",
            "прочитай расписание на сегодня", "прочитай расписание на завтра")
        SpeechContext.CONFIRM -> listOf("да", "нет", "исправить")
        SpeechContext.PRESSURE_NUMBER -> numbers
        SpeechContext.DOSE -> numbers + units + listOf("половина", "половину", "полтаблетки", "четверть",
            "полтора", "полторы", "целых", "целая", "десятых", "десятая", "запятая", "точка", "с половиной")
        SpeechContext.TIME -> numbers + listOf("в", "и", "утра", "утром", "дня", "днем", "вечера", "вечером",
            "ночи", "ночью", "час", "часа", "часов", "минут", "минута", "минуты")
        SpeechContext.WEEKDAY -> listOf("понедельник", "понедельникам", "вторник", "вторникам", "среда", "среду", "средам",
            "четверг", "четвергам", "пятница", "пятницу", "пятницам", "суббота", "субботу", "субботам",
            "воскресенье", "воскресеньям", "и", "по", "в")
        SpeechContext.CHOICE -> numbers + listOf("номер", "исправить")
        SpeechContext.CORRECTION -> listOf("верхнее давление", "нижнее давление", "пульс", "название", "количество",
            "доза", "единицы", "время", "повтор", "дни недели", "дата", "окончание")
        SpeechContext.UNIT -> units
        SpeechContext.REPEAT -> listOf("однократно", "один раз", "разово", "каждый день", "ежедневно", "по дням недели",
            "каждую неделю", "один раз в неделю", "еженедельно")
    }
    // Unknown speech must remain unknown rather than being forced into a valid confirmation.
    return (vocabulary + listOf("отмена", "стоп", "[unk]")).distinct()
}
