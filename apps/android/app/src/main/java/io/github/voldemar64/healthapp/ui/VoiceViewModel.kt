package io.github.voldemar64.healthapp.ui

import android.content.Context
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*
import io.github.voldemar64.healthapp.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

data class VoiceUiState(val dialog: DialogState = DialogState(), val active: Boolean = false,
    val status: Int = R.string.voice_ready, val prompt: String = "", val transcript: String = "",
    val error: String? = null, val destination: String? = null, val finished: Boolean = false)

class VoiceViewModel(application: Application, private val repository: HealthRepository,
    private val recognizer: VoiceInput = OfflineRecognizer(application),
    private val output: VoiceOutput = OfflineSpeechOutput(application)) : AndroidViewModel(application) {
    private var dialog = VoiceDialog()
    private var job: Job? = null
    private val mutable = MutableStateFlow(VoiceUiState())
    val state = mutable.asStateFlow()
    private fun text(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    fun start() {
        if (job?.isActive == true) return
        if (mutable.value.finished) dialog = VoiceDialog()
        mutable.value = mutable.value.copy(active = true, error = null, finished = false, destination = null)
        job = viewModelScope.launch {
            try {
                mutable.value = mutable.value.copy(status = R.string.voice_preparing)
                output.prepare()
                recognizer.prepare()
                var silence = 0
                while (isActive) {
                    mutable.value = mutable.value.copy(dialog = dialog.state)
                    when (dialog.state.phase) {
                        DialogPhase.CANCELLED -> { say(text(R.string.voice_cancelled)); finish(); return@launch }
                        DialogPhase.NAVIGATE -> {
                            val route = dialog.state.destination!!
                            say(text(R.string.voice_opened, text(if (route == "pressure") R.string.pressure_title else R.string.medications_title)))
                            mutable.value = mutable.value.copy(destination = route)
                            finish(); return@launch
                        }
                        DialogPhase.READ -> {
                            val date = dialog.state.readDate!!
                            val data = repository.data.first()
                            val items = occurrences(data, date, date)
                            say(text(R.string.voice_schedule_date, date.format(dateFormat)))
                            if (items.isEmpty()) say(text(R.string.no_intakes_day))
                            items.forEach { item ->
                                val status = data.intakes.find { it.occurrence.key == item.key }?.status
                                say(occurrenceText(item) + ". " + text(when (status) {
                                    IntakeStatus.TAKEN -> R.string.taken; IntakeStatus.SKIPPED -> R.string.skipped; null -> R.string.planned
                                }))
                            }
                            dialog = VoiceDialog()
                        }
                        DialogPhase.COMMIT -> {
                            val current = dialog.state
                            mutable.value = mutable.value.copy(status = R.string.voice_saving)
                            // A single consumed confirmation owns this write, even if the Activity stops.
                            withContext(NonCancellable) {
                                when (current.draft!!.kind) {
                                    VoiceKind.PRESSURE -> repository.savePressure(current.pressure!!)
                                    VoiceKind.MEDICATION -> repository.saveSchedule(current.schedule!!)
                                    VoiceKind.MARK -> {
                                        val status = IntakeStatus.valueOf(current.draft!!.values[VoiceField.STATUS] ?: "TAKEN")
                                        repository.mark(current.selected!!, status, if (status == IntakeStatus.TAKEN) Instant.now() else null)
                                    }
                                }
                                mutable.value = mutable.value.copy(finished = true, active = false)
                            }
                            ensureActive()
                            say(text(R.string.voice_saved))
                            dialog = VoiceDialog()
                            mutable.value = mutable.value.copy(finished = false, active = true)
                        }
                        else -> Unit
                    }
                    val prompt = prompt(dialog.state)
                    say(prompt)
                    delay(400)
                    mutable.value = mutable.value.copy(status = R.string.voice_listening, transcript = "")
                    val heard = recognizer.listen { partial -> mutable.value = mutable.value.copy(transcript = partial) }
                    mutable.value = mutable.value.copy(transcript = heard)
                    if (heard.isBlank()) {
                        silence++
                        if (silence >= 3) { say(text(R.string.voice_silence)); pause(); return@launch }
                        say(text(R.string.voice_retry)); continue
                    }
                    silence = 0
                    dialog.accept(heard, repository.data.first(), Instant.now(), ZoneId.systemDefault())
                }
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                val code = (failure as? SpeechFailure)?.code ?: (failure as? InvalidInput)?.code?.name ?: "VOICE"
                mutable.value = mutable.value.copy(active = false, error = code, status = R.string.voice_paused)
                // Never retry a write automatically: its outcome may be uncertain after an IO failure.
                if (dialog.state.phase == DialogPhase.COMMIT) mutable.value = mutable.value.copy(finished = true)
            } finally {
                mutable.value = mutable.value.copy(active = false)
            }
        }
    }

    private suspend fun say(message: String) {
        mutable.value = mutable.value.copy(status = R.string.voice_speaking, prompt = message, dialog = dialog.state)
        // Android TTS limits each utterance; a long schedule must still be read completely.
        var remaining = message
        while (remaining.isNotBlank()) {
            val end = if (remaining.length <= 1000) remaining.length else remaining.lastIndexOf(' ', 1000).coerceAtLeast(1)
            output.speak(remaining.substring(0, end))
            remaining = remaining.substring(end).trimStart()
        }
    }

    fun pause() {
        job?.cancel()
        output.stop()
        mutable.value = mutable.value.copy(active = false, status = R.string.voice_paused)
    }
    private fun finish() { mutable.value = mutable.value.copy(active = false, finished = true, status = R.string.voice_ready) }
    fun edit(field: VoiceField, value: String) {
        pause()
        dialog.edit(field, value)
        mutable.value = mutable.value.copy(dialog = dialog.state, error = null)
    }
    fun review() {
        if (job?.isActive == true) return
        viewModelScope.launch {
            dialog.review(repository.data.first(), Instant.now(), ZoneId.systemDefault())
            mutable.value = mutable.value.copy(dialog = dialog.state)
            start()
        }
    }
    fun permissionDenied() { mutable.value = mutable.value.copy(error = "PERMISSION") }

    private fun occurrenceText(item: Occurrence) = text(R.string.voice_occurrence,
        item.name, item.date.format(dateFormat), item.time.format(timeFormat), item.dose.text, spokenUnit(item.dose.unit))
    private fun spokenUnit(unit: DoseUnit) = text(when (unit) {
        DoseUnit.TABLET -> R.string.voice_tablets; DoseUnit.MG -> R.string.voice_milligrams; DoseUnit.G -> R.string.voice_grams
    })
    private fun prompt(value: DialogState): String {
        val prefix = value.error?.let { text(when (it) {
            "NOT_FOUND" -> R.string.voice_not_found
            "UNKNOWN" -> R.string.voice_unknown
            "FORMAT" -> R.string.voice_format
            "CONFIRM", "CHOICE", "FIELD" -> R.string.voice_retry
            else -> errorText(it)
        }) + " " }.orEmpty()
        return prefix + when (value.phase) {
            DialogPhase.FIELD -> text(fieldPrompt(value.field!!))
            DialogPhase.CORRECTION -> text(when (value.draft?.kind) {
                VoiceKind.PRESSURE -> R.string.voice_correct_pressure
                VoiceKind.MARK -> R.string.voice_correct_mark
                else -> R.string.voice_correct_medication
            })
            DialogPhase.CHOICE -> text(R.string.voice_choose) + " " + value.choices.mapIndexed { index, item ->
                text(R.string.voice_choice, index + 1, occurrenceText(item))
            }.joinToString(". ")
            DialogPhase.CONFIRM -> {
                val summary = when (value.draft!!.kind) {
                    VoiceKind.PRESSURE -> value.pressure!!.let {
                        val local = it.measuredAt.atZone(ZoneId.systemDefault())
                        text(R.string.voice_pressure_summary, it.systolic, it.diastolic, it.pulse!!,
                            local.format(dateFormat), local.format(timeFormat))
                    }
                    VoiceKind.MEDICATION -> value.schedule!!.let { draft ->
                        val repeat = text(repeatResource(draft.repeat)) + if (draft.repeat == Repeat.WEEKLY)
                            ": " + draft.weekdays.sorted().joinToString(", ") { it.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("ru")) } else ""
                        text(R.string.voice_medication_summary, draft.name,
                            draft.slots.joinToString("; ") { text(R.string.voice_slot, it.time.format(timeFormat), it.dose.text, spokenUnit(it.dose.unit)) },
                            repeat, draft.startDate.format(dateFormat), draft.endDate?.format(dateFormat) ?: text(R.string.no_end))
                    }
                    VoiceKind.MARK -> text(R.string.voice_mark_summary, occurrenceText(value.selected!!),
                        text(if (value.draft!!.values[VoiceField.STATUS] == "SKIPPED") R.string.skipped else R.string.taken))
                }
                summary + " " + text(R.string.voice_confirm)
            }
            else -> text(R.string.voice_intro)
        }
    }
    override fun onCleared() {
        pause()
        output.release()
        CoroutineScope(Dispatchers.IO).launch { recognizer.release() }
    }
    companion object {
        fun factory(context: Context, repository: HealthRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VoiceViewModel(context.applicationContext as Application, repository) as T
        }
    }
}

internal fun fieldPrompt(field: VoiceField) = when (field) {
    VoiceField.SYSTOLIC -> R.string.voice_ask_systolic; VoiceField.DIASTOLIC -> R.string.voice_ask_diastolic
    VoiceField.PULSE -> R.string.voice_ask_pulse; VoiceField.NAME -> R.string.voice_ask_name
    VoiceField.AMOUNT -> R.string.voice_ask_amount; VoiceField.UNIT -> R.string.voice_ask_unit
    VoiceField.TIMES -> R.string.voice_ask_times; VoiceField.REPEAT -> R.string.voice_ask_repeat
    VoiceField.DAYS -> R.string.voice_ask_days; VoiceField.DATE -> R.string.voice_ask_date
    VoiceField.END_DATE -> R.string.voice_ask_end; VoiceField.STATUS -> R.string.voice_ask_status
}
