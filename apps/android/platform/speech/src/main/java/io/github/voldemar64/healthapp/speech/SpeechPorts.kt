package io.github.voldemar64.healthapp.speech

enum class SpeechContext { FREE, PRESSURE, CONFIRM, PRESSURE_NUMBER, DOSE, TIME, WEEKDAY, CHOICE, CORRECTION, UNIT, REPEAT }

interface VoiceInput {
    suspend fun prepare()
    suspend fun listen(context: SpeechContext = SpeechContext.FREE, onPartial: (String) -> Unit): String
    suspend fun release()
}

interface VoiceOutput {
    suspend fun prepare()
    suspend fun speak(text: String)
    fun stop()
    fun release()
}
