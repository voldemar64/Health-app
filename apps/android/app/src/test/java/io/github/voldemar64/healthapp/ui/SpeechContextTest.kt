package io.github.voldemar64.healthapp.ui

import io.github.voldemar64.healthapp.domain.*
import io.github.voldemar64.healthapp.speech.SpeechContext
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechContextTest {
    @Test fun initialPressureIsRestrictedAndMedicationNamesRemainFree() {
        assertEquals(SpeechContext.PRESSURE, listeningContext(DialogState(), VoiceKind.PRESSURE))
        assertEquals(SpeechContext.FREE, listeningContext(DialogState(), VoiceKind.MEDICATION))
        assertEquals(SpeechContext.FREE, contextFor(VoiceField.NAME))
    }

    @Test fun measurementAndDoseQuestionsSelectDifferentVocabularies() {
        listOf(VoiceField.SYSTOLIC, VoiceField.DIASTOLIC, VoiceField.PULSE).forEach {
            assertEquals(it.name, SpeechContext.PRESSURE_NUMBER, contextFor(it))
        }
        assertEquals(SpeechContext.DOSE, contextFor(VoiceField.AMOUNT))
    }

    @Test fun confirmationAndCorrectionOverridePressureFormContext() {
        assertEquals(SpeechContext.CONFIRM,
            listeningContext(DialogState(phase = DialogPhase.CONFIRM), VoiceKind.PRESSURE))
        assertEquals(SpeechContext.CORRECTION,
            listeningContext(DialogState(phase = DialogPhase.CORRECTION), VoiceKind.PRESSURE))
        assertEquals(SpeechContext.TIME, contextFor(VoiceField.TIMES))
    }

    private fun contextFor(field: VoiceField) = listeningContext(
        DialogState(phase = DialogPhase.FIELD, field = field), VoiceKind.PRESSURE)
}
