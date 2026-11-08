package io.github.voldemar64.healthapp.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineSpeechOutput(private val context: Context) : VoiceOutput {
    private var engine: TextToSpeech? = null

    override suspend fun prepare() = withContext(Dispatchers.Main.immediate) {
        if (engine != null) return@withContext
        withTimeout(15000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                var created: TextToSpeech? = null
                created = TextToSpeech(context) { status ->
                  Handler(Looper.getMainLooper()).post {
                    val tts = created
                    if (!continuation.isActive) { tts?.shutdown(); return@post }
                    val voice = if (status == TextToSpeech.SUCCESS) tts?.voices?.filter {
                        it.locale.language == "ru" && !it.isNetworkConnectionRequired &&
                            it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                    }?.maxByOrNull { it.quality } else null
                    if (voice == null || tts == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                        tts?.shutdown()
                        continuation.resumeWithException(SpeechFailure("VOICE_MISSING"))
                    } else {
                        engine = tts
                        tts.setSpeechRate(0.95f)
                        continuation.resume(Unit)
                    }
                  }
                }
                continuation.invokeOnCancellation {
                    if (engine === created) engine = null
                    created?.shutdown()
                }
            }
        }
    }

    override suspend fun speak(text: String) = withContext(Dispatchers.Main.immediate) {
        prepare()
        val tts = engine ?: throw SpeechFailure("VOICE_MISSING")
        withTimeout(120000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val id = UUID.randomUUID().toString()
                val completed = AtomicBoolean(false)
                fun finish(failure: Exception? = null) {
                    if (completed.compareAndSet(false, true)) {
                        if (failure == null) continuation.resume(Unit) else continuation.resumeWithException(failure)
                    }
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) finish()
                    }
                    @Deprecated("Required by Android")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) finish(SpeechFailure("TTS"))
                    }
                })
                continuation.invokeOnCancellation { completed.set(true); tts.stop() }
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
                    finish(SpeechFailure("TTS"))
                }
            }
        }
    }

    override fun stop() { engine?.stop() }
    override fun release() { engine?.stop(); engine?.shutdown(); engine = null }
}
