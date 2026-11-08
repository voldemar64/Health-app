package io.github.voldemar64.healthapp.speech

interface VoiceInput {
    suspend fun prepare()
    suspend fun listen(onPartial: (String) -> Unit): String
    suspend fun release()
}

interface VoiceOutput {
    suspend fun prepare()
    suspend fun speak(text: String)
    fun stop()
    fun release()
}
