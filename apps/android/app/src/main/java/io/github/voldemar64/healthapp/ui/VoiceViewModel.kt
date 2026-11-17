package io.github.voldemar64.healthapp.ui

import android.content.Context
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
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
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.time.Clock
import java.time.ZoneId

data class VoiceUiState(val dialog: DialogState = DialogState(), val active: Boolean = false,
    val status: Int = R.string.voice_ready, val prompt: String = "", val transcript: String = "",
    val error: String? = null, val destination: String? = null, val finished: Boolean = false,
    val saved: Boolean = false, val kind: VoiceKind = VoiceKind.PRESSURE,
    val pressureForm: VoiceDraft = pressureDraft(), val medicationForm: VoiceDraft = medicationDraft())

class VoiceViewModel(application: Application, private val repository: HealthRepository,
    private val recognizer: VoiceInput = OfflineRecognizer(application),
    private val output: VoiceOutput = OfflineSpeechOutput(application),
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : AndroidViewModel(application) {
    private var dialog = VoiceDialog()
    private var job: Job? = null
    private var sessionProfileId = repository.activeProfileId.value
    private var awaitingProfile = true
    private val savedProfileMatches = savedState.get<String>("entryProfile") == sessionProfileId
    private val mutable = MutableStateFlow(VoiceUiState(
        kind = savedState.get<String>("entryKind")?.let(VoiceKind::valueOf) ?: VoiceKind.PRESSURE,
        pressureForm = if (savedProfileMatches) savedState.get<ArrayList<String>>("pressureForm")?.let(::decodeDraft) ?: pressureDraft() else pressureDraft(),
        medicationForm = if (savedProfileMatches) savedState.get<ArrayList<String>>("medicationForm")?.let(::decodeDraft) ?: medicationDraft() else medicationDraft()))
    val state = mutable.asStateFlow()
    private fun text(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    private var manualChanged = false

    init {
        viewModelScope.launch {
            repository.activeProfileId.collect { id ->
                if (id != sessionProfileId) {
                    pause()
                    sessionProfileId = id
                    awaitingProfile = true
                    val pressure = pressureDraft()
                    val medication = medicationDraft()
                    mutable.value = mutable.value.copy(pressureForm = pressure, medicationForm = medication,
                        saved = false, finished = false)
                    dialog.seed(if (mutable.value.kind == VoiceKind.PRESSURE) pressure else medication)
                    publishDraft()
                }
            }
        }
    }

    fun initialize(kind: VoiceKind) = selectKind(if (savedState.contains("entryKind")) mutable.value.kind else kind)

    private fun persistForms() {
        savedState["entryKind"] = mutable.value.kind.name
        savedState["entryProfile"] = sessionProfileId
        savedState["pressureForm"] = encodeDraft(mutable.value.pressureForm)
        savedState["medicationForm"] = encodeDraft(mutable.value.medicationForm)
    }

    fun selectKind(kind: VoiceKind) {
        if (dialog.state.phase == DialogPhase.COMMIT && job != null) return
        if (mutable.value.kind == kind && dialog.state.draft != null) return
        pause()
        mutable.value = mutable.value.copy(kind = kind)
        dialog.seed(if (kind == VoiceKind.PRESSURE) mutable.value.pressureForm else mutable.value.medicationForm)
        mutable.value = mutable.value.copy(dialog = dialog.state)
        manualChanged = false
        persistForms()
    }

    fun updateDraft(draft: VoiceDraft) {
        if (dialog.state.phase == DialogPhase.COMMIT && job != null) return
        pause()
        dialog.seed(draft)
        publishDraft(draft)
        manualChanged = true
    }

    internal fun updatePressureDraft(draft: PressureFormDraft) = updateDraft(draft.toVoiceDraft(mutable.value.pressureForm))
    internal fun updateMedicationDraft(draft: MedicationFormDraft) = updateDraft(draft.toVoiceDraft(mutable.value.medicationForm))

    private fun publishDraft(draft: VoiceDraft? = dialog.state.draft) {
        mutable.value = when (draft?.kind) {
            VoiceKind.PRESSURE -> mutable.value.copy(pressureForm = draft, kind = draft.kind, dialog = dialog.state)
            VoiceKind.MEDICATION -> mutable.value.copy(medicationForm = draft, kind = draft.kind, dialog = dialog.state)
            else -> mutable.value.copy(dialog = dialog.state)
        }
        persistForms()
    }

    fun start() {
        if (job != null) return
        if (mutable.value.saved) return
        if (mutable.value.finished) dialog.seed(if (mutable.value.kind == VoiceKind.PRESSURE) mutable.value.pressureForm else mutable.value.medicationForm)
        mutable.value = mutable.value.copy(active = true, error = null, finished = false, destination = null)
        job = viewModelScope.launch {
            try {
                mutable.value = mutable.value.copy(status = R.string.voice_preparing)
                output.prepare()
                recognizer.prepare()
                if (manualChanged || dialog.state.phase == DialogPhase.PAUSED) {
                    dialog.review(repository.observeProfile(sessionProfileId).first(), clock.instant(), zone())
                    manualChanged = false
                    publishDraft()
                }
                var silence = 0
                while (isActive) {
                    if (awaitingProfile) {
                        if (!selectVoiceProfile()) { pause(); return@launch }
                        awaitingProfile = false
                    }
                    mutable.value = mutable.value.copy(dialog = dialog.state)
                    when (dialog.state.phase) {
                        DialogPhase.PAUSED -> { pause(); return@launch }
                        DialogPhase.CANCELLED -> { say(text(R.string.voice_cancelled)); finish(); return@launch }
                        DialogPhase.NAVIGATE -> {
                            val route = dialog.state.destination!!
                            say(text(R.string.voice_opened, text(if (route == "pressure") R.string.pressure_title else R.string.medications_title)))
                            mutable.value = mutable.value.copy(destination = route)
                            finish(); return@launch
                        }
                        DialogPhase.READ -> {
                            val date = dialog.state.readDate!!
                            val data = repository.observeProfile(sessionProfileId).first()
                            val items = occurrences(data, date, date)
                            say(text(R.string.voice_schedule_date, date.format(dateFormat)))
                            if (items.isEmpty()) say(text(R.string.no_intakes_day))
                            items.forEach { item ->
                                val status = data.intakes.find { it.occurrence.key == item.key }?.status
                                say(occurrenceText(item) + ". " + text(when (status) {
                                    IntakeStatus.TAKEN -> R.string.taken; IntakeStatus.SKIPPED -> R.string.skipped
                                    IntakeStatus.PENDING, IntakeStatus.POSTPONED, IntakeStatus.UNCONFIRMED, null -> R.string.planned
                                }))
                            }
                            dialog.seed(if (mutable.value.kind == VoiceKind.PRESSURE) mutable.value.pressureForm else mutable.value.medicationForm)
                        }
                        DialogPhase.COMMIT -> {
                            val current = dialog.state
                            mutable.value = mutable.value.copy(status = R.string.voice_saving,
                                kind = if (current.draft?.kind == VoiceKind.MARK) VoiceKind.MEDICATION else mutable.value.kind)
                            var committed = false
                            try {
                                // A consumed confirmation owns this write, even if the Activity stops.
                                withContext(NonCancellable) {
                                    val timer = current.timerReminder
                                    val atReminder = current.atReminder
                                    val stock = current.stockChange
                                    if (timer != null) repository.createTimerReminder(timer.title, timer.minutes * 60,
                                        current.selectedMedicationId, current.timerAmount)
                                    else if (atReminder != null) repository.createReminder(atReminder.title,
                                        atReminder.at.atZone(zone()).toInstant(), current.selectedMedicationId,
                                        current.timerAmount)
                                    else if (stock != null) {
                                        val id = requireNotNull(current.selectedMedicationId)
                                        if (stock.reconcile) repository.reconcileStock(id, stock.amount)
                                        else repository.addStock(id, stock.amount)
                                    } else if (current.postponeCommand != null)
                                        repository.postpone(requireNotNull(current.selected),
                                            clock.instant().plusSeconds(requireNotNull(current.postponeCommand).minutes * 60))
                                    else when (current.draft!!.kind) {
                                        VoiceKind.PRESSURE -> repository.savePressure(current.pressure!!)
                                        VoiceKind.MEDICATION -> repository.saveMedicationSchedule(current.schedule!!,
                                            current.card!!)
                                        VoiceKind.MARK -> if (current.selectedMany.isNotEmpty())
                                            repository.markAllNow(current.selectedMany)
                                        else if (current.draft!!.values[VoiceField.STATUS] == "SKIPPED")
                                            repository.mark(current.selected!!, IntakeStatus.SKIPPED, null)
                                        else repository.markTakenNow(current.selected!!)
                                    }
                                    committed = true
                                    mutable.value = mutable.value.copy(finished = true,
                                        destination = when {
                                            timer != null -> "today"
                                            atReminder != null -> "today"
                                            stock != null -> "medications"
                                            current.postponeCommand != null -> "medications"
                                            else -> null
                                        })
                                }
                                ensureActive()
                                say(text(R.string.voice_saved))
                            } finally {
                                if (committed) mutable.value = mutable.value.copy(saved = true, active = false)
                            }
                            return@launch
                        }
                        else -> Unit
                    }
                    val prompt = prompt(dialog.state)
                    say(prompt)
                    delay(400)
                    mutable.value = mutable.value.copy(status = R.string.voice_listening, transcript = "")
                    val heard = recognizer.listen(listeningContext(dialog.state, mutable.value.kind)) { partial -> mutable.value = mutable.value.copy(transcript = partial) }
                    mutable.value = mutable.value.copy(transcript = heard)
                    if (RussianNumbers.normalize(heard).replace(" ", "").startsWith("эйэнди")) {
                        awaitingProfile = true
                        dialog.seed(if (mutable.value.kind == VoiceKind.PRESSURE) pressureDraft() else medicationDraft())
                        publishDraft()
                        continue
                    }
                    if (heard.isBlank()) {
                        silence++
                        if (silence >= 3) { say(text(R.string.voice_silence)); pause(); return@launch }
                        say(text(R.string.voice_retry)); continue
                    }
                    dialog.accept(heard, repository.observeProfile(sessionProfileId).first(), clock.instant(), zone())
                    publishDraft()
                    silence = if (dialog.state.error != null) silence + 1 else 0
                    if (silence >= 3) { say(text(R.string.voice_silence)); pause(); return@launch }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                val code = (failure as? SpeechFailure)?.code ?: (failure as? InvalidInput)?.code?.name ?: "VOICE"
                mutable.value = mutable.value.copy(active = false, error = code, status = R.string.voice_paused)
                // Never retry a write automatically: its outcome may be uncertain after an IO failure.
                if (dialog.state.phase == DialogPhase.COMMIT) mutable.value = mutable.value.copy(finished = true)
            } finally {
                mutable.value = mutable.value.copy(active = false)
                job = null
            }
        }
    }

    private suspend fun selectVoiceProfile(): Boolean {
        val profiles = repository.profiles.first()
        repeat(3) {
            say(text(R.string.voice_who_is_here) + " " + profiles.mapIndexed { index, profile ->
                "${index + 1}: ${profile.name}"
            }.joinToString(". "))
            mutable.value = mutable.value.copy(status = R.string.voice_listening)
            val heard = recognizer.listen(SpeechContext.FREE) { partial ->
                mutable.value = mutable.value.copy(transcript = partial)
            }
            val normalized = RussianNumbers.normalize(heard).trim()
            val index = normalized.removePrefix("номер ").toIntOrNull()?.minus(1)
            val matches = profiles.filter { RussianNumbers.normalize(it.name) == normalized }
            val chosen = index?.let(profiles::getOrNull) ?: matches.singleOrNull()
            if (chosen != null) {
                if (chosen.id != sessionProfileId) {
                    sessionProfileId = chosen.id
                    repository.selectProfile(chosen.id)
                    val pressure = pressureDraft()
                    val medication = medicationDraft()
                    mutable.value = mutable.value.copy(pressureForm = pressure, medicationForm = medication)
                    dialog.seed(if (mutable.value.kind == VoiceKind.PRESSURE) pressure else medication)
                    publishDraft()
                }
                return true
            }
            say(text(R.string.voice_profile_unclear))
        }
        return false
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
            dialog.review(repository.observeProfile(sessionProfileId).first(), clock.instant(), zone())
            mutable.value = mutable.value.copy(dialog = dialog.state)
            start()
        }
    }
    fun permissionDenied() { mutable.value = mutable.value.copy(error = "PERMISSION") }

    private fun occurrenceText(item: Occurrence) = text(R.string.voice_occurrence,
        item.name, item.time.format(timeFormat), item.dose.text, spokenUnit(item.dose.unit))
    private fun spokenUnit(unit: DoseUnit) = text(when (unit) {
        DoseUnit.TABLET -> R.string.voice_tablets; DoseUnit.CAPSULE -> R.string.voice_capsules;
        DoseUnit.MG -> R.string.voice_milligrams; DoseUnit.G -> R.string.voice_grams;
        DoseUnit.OTHER -> R.string.voice_units
    })
    private fun prompt(value: DialogState): String {
        val prefix = value.error?.let { text(when (it) {
            "NOT_FOUND" -> R.string.voice_not_found
            "UNKNOWN" -> R.string.voice_unknown
            "FORMAT" -> R.string.voice_format
            "CONFIRM", "CHOICE", "FIELD" -> R.string.voice_retry
            else -> R.string.voice_format
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
            DialogPhase.MEDICATION_CHOICE -> text(R.string.voice_choose) + " " + value.medicationChoices
                .mapIndexed { index, card -> "${index + 1}: ${card.name}, ${card.form}, ${card.strength.orEmpty()}" }
                .joinToString(". ")
            DialogPhase.CONFIRM -> if (value.timerReminder != null) {
                val timer = requireNotNull(value.timerReminder)
                text(R.string.voice_timer_summary, timer.title, timer.minutes) + ". " + text(R.string.voice_confirm)
            } else if (value.atReminder != null) {
                val reminder = requireNotNull(value.atReminder)
                "Напомнить ${reminder.title} ${reminder.at.toLocalDate().format(dateFormat)} " +
                    "в ${reminder.at.toLocalTime().format(timeFormat)}. " + text(R.string.voice_confirm)
            } else if (value.stockChange != null) {
                val stock = requireNotNull(value.stockChange)
                text(R.string.voice_stock_summary, stock.name, stock.amount.stripTrailingZeros().toPlainString(),
                    if (stock.reconcile) text(R.string.stock_reconcile) else text(R.string.stock_topup)) + ". " +
                    text(R.string.voice_confirm)
            } else if (value.postponeCommand != null) {
                val command = requireNotNull(value.postponeCommand)
                occurrenceText(requireNotNull(value.selected)) + ". Отложить на ${command.minutes} минут. " +
                    text(R.string.voice_confirm)
            } else if (value.pressure != null) {
                val p = requireNotNull(value.pressure)
                val local = p.measuredAt.atZone(ZoneId.of(p.recordedZoneId))
                text(R.string.voice_pressure_summary, p.systolic, p.diastolic,
                    p.pulse?.toString() ?: text(R.string.voice_pulse_unknown),
                    local.format(dateFormat), local.format(timeFormat)) + ". " +
                    (if (p.systolic <= p.diastolic) text(R.string.voice_pressure_order) + " " else "") +
                    text(R.string.voice_confirm)
            } else if (value.schedule != null && value.card != null) {
                val schedule = requireNotNull(value.schedule)
                val card = requireNotNull(value.card)
                "${schedule.name}. Форма: ${card.form}. Дозировка: ${card.strength ?: "неизвестна"}. " +
                    schedule.slots.joinToString(". ") { slot ->
                        "В ${slot.time.format(timeFormat)} ${slot.dose.text} ${spokenUnit(slot.dose.unit)}"
                    } + ". Повтор: ${schedule.repeat.name}. Начало: ${schedule.startDate.format(dateFormat)}. " +
                    "Остаток: ${card.initialStock?.toPlainString() ?: "неизвестен"}. " + text(R.string.voice_confirm)
            } else if (value.selectedMany.isNotEmpty()) {
                value.selectedMany.joinToString(". ") { occurrenceText(it) } + ". " + text(R.string.voice_confirm)
            } else if (value.selected != null) {
                val action = if (value.draft?.values?.get(VoiceField.STATUS) == "SKIPPED")
                    text(R.string.skipped) else text(R.string.taken)
                occurrenceText(requireNotNull(value.selected)) + ". " + action + ". " + text(R.string.voice_confirm)
            } else text(R.string.voice_confirm)
            else -> text(if (mutable.value.kind == VoiceKind.PRESSURE) R.string.voice_intro_pressure else R.string.voice_intro_medication)
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
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                VoiceViewModel(context.applicationContext as Application, repository, savedState = extras.createSavedStateHandle()) as T
        }
    }
}

internal fun listeningContext(state: DialogState, kind: VoiceKind): SpeechContext = when (state.phase) {
    DialogPhase.CONFIRM -> SpeechContext.FREE
    DialogPhase.CHOICE, DialogPhase.MEDICATION_CHOICE -> SpeechContext.CHOICE
    DialogPhase.CORRECTION -> SpeechContext.CORRECTION
    DialogPhase.FIELD -> when (state.field) {
        VoiceField.SYSTOLIC, VoiceField.DIASTOLIC, VoiceField.PULSE -> SpeechContext.PRESSURE_NUMBER
        VoiceField.AMOUNT, VoiceField.STOCK -> SpeechContext.DOSE
        VoiceField.TIMES -> SpeechContext.TIME
        VoiceField.DAYS -> SpeechContext.WEEKDAY
        VoiceField.UNIT -> SpeechContext.UNIT
        VoiceField.REPEAT -> SpeechContext.REPEAT
        else -> SpeechContext.FREE
    }
    DialogPhase.COMMAND -> if (kind == VoiceKind.PRESSURE) SpeechContext.PRESSURE else SpeechContext.FREE
    else -> SpeechContext.FREE
}

internal fun fieldPrompt(field: VoiceField) = when (field) {
    VoiceField.SYSTOLIC -> R.string.voice_ask_systolic; VoiceField.DIASTOLIC -> R.string.voice_ask_diastolic
    VoiceField.PULSE -> R.string.voice_ask_pulse; VoiceField.NAME -> R.string.voice_ask_name
    VoiceField.FORM -> R.string.voice_ask_form; VoiceField.STRENGTH -> R.string.voice_ask_strength
    VoiceField.STOCK -> R.string.voice_ask_stock
    VoiceField.AMOUNT -> R.string.voice_ask_amount; VoiceField.UNIT -> R.string.voice_ask_unit
    VoiceField.TIMES -> R.string.voice_ask_times; VoiceField.REPEAT -> R.string.voice_ask_repeat
    VoiceField.DAYS -> R.string.voice_ask_days; VoiceField.DATE -> R.string.voice_ask_date
    VoiceField.END_DATE -> R.string.voice_ask_end; VoiceField.STATUS -> R.string.voice_ask_status
}
