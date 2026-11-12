package io.github.voldemar64.healthapp.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechGrammarTest {
    private fun words(context: SpeechContext) = context.phrases()!!.flatMap { it.split(' ') }.toSet()

    @Test fun pressurePhraseCoversMeasurementDateTimeAndNavigationWithoutDoseWords() {
        val words = words(SpeechContext.PRESSURE)
        val examples = listOf("запиши давление сто двадцать на восемьдесят пульс шестьдесят пять",
            "вчера в девять утра сто тридцать на восемьдесят пять пульс семьдесят",
            "открой лекарства", "прочитай расписание на сегодня")
        examples.forEach { example -> assertTrue(example, words.containsAll(example.split(' '))) }
        assertFalse(words.contains("таблетки"))
        assertFalse(words.contains("миллиграммов"))
    }

    @Test fun vitalSignsUseIntegersWhileDoseKeepsFractionsAndUnits() {
        val vital = words(SpeechContext.PRESSURE_NUMBER)
        val dose = words(SpeechContext.DOSE)
        assertTrue(vital.containsAll(listOf("сто", "двадцать", "пять", "ноль")))
        listOf("таблетки", "миллиграммов", "грамма", "половину", "четверть", "запятая", "десятых").forEach {
            assertFalse(it, vital.contains(it))
            assertTrue(it, dose.contains(it))
        }
    }

    @Test fun restrictedGrammarsRetainUnknownAndCancellationAndNamesStayFree() {
        assertNull(SpeechContext.FREE.phrases())
        SpeechContext.entries.filter { it != SpeechContext.FREE }.forEach {
            assertTrue(it.name, words(it).containsAll(listOf("[unk]", "стоп", "отмена")))
        }
    }
}
