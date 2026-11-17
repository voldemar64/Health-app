package io.github.voldemar64.healthapp.ui

import io.github.voldemar64.healthapp.domain.*
import org.junit.Assert.*
import org.junit.Test

class EntryDraftTest {
    @Test fun legacySavedStateKeepsFieldPositionsAfterNewVoiceFields() {
        val legacy = listOf("MEDICATION", "id", "true", "", "", "", "Тестол", "0.5", "TABLET",
            "09:00", "DAILY", "", "16.11.2026", "", "", "slot", "09:00", "0.5", "TABLET")
        val restored = decodeDraft(legacy)
        assertEquals("Тестол", restored.values[VoiceField.NAME])
        assertEquals("0.5", restored.values[VoiceField.AMOUNT])
        assertEquals("09:00", restored.slots?.single()?.time)
    }

    @Test fun typedMedicationDraftPreservesDifferentSlotQuantities() {
        val draft = MedicationFormDraft("id", "Тест", Repeat.WEEKLY, setOf(java.time.DayOfWeek.MONDAY),
            "16.11.2026", "", listOf(MedicationSlotDraft("am", "09:00", "0.5", DoseUnit.TABLET),
                MedicationSlotDraft("pm", "18:00", "1", DoseUnit.TABLET)))
        val voice = draft.toVoiceDraft(VoiceDraft(VoiceKind.MEDICATION))
        assertEquals(draft, decodeDraft(encodeDraft(voice)).asMedicationFormDraft())
        assertEquals(listOf("0.5", "1"), draft.schedule().slots.map { it.dose.text })
    }
}
