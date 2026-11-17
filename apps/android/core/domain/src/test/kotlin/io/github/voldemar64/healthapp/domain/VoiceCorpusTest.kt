package io.github.voldemar64.healthapp.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCorpusTest {
    private val today = LocalDate.of(2026, 11, 16)

    @Test fun thirtyWrittenPhrasesKeepTheirIntents() {
        val phrases = listOf(
            "Запиши давление 120 на 80" to "pressure",
            "Давление 130 на 90 пульс 72" to "pressure",
            "Добавь давление 118 на 79" to "pressure",
            "Давление 125/83" to "pressure",
            "Внеси давление 140 на 95" to "pressure",
            "Записать давление сто двадцать на восемьдесят" to "pressure",
            "Давление 110 на 70 сегодня" to "pressure",
            "Давление 119 на 77 пульс 65" to "pressure",
            "Давление 80 на 120" to "pressure",
            "Измерение 121 на 81" to "pressure",
            "Добавь лекарство Аспирин 1 таблетку каждый день в 09:00" to "medication",
            "Создай препарат Омега 3 2 капсулы ежедневно в 08:00" to "medication",
            "Назначь лекарство Тест 1 таблетку в понедельник в 09:00" to "medication",
            "Запиши лекарство Тест 1 таблетку завтра в 10:00" to "medication",
            "Внеси препарат Тест 1 таблетку в 12:00" to "medication",
            "Принял Аспирин" to "mark",
            "Выпила Омега" to "mark",
            "Пропустил Аспирин" to "mark",
            "Отметь Аспирин" to "mark",
            "Открой давление" to "navigate",
            "Покажи лекарства" to "navigate",
            "Перейди к календарю" to "navigate",
            "Что принимать сегодня" to "read",
            "Прочитай расписание на завтра" to "read",
            "Напомни через 15 минут измерить давление" to "timer",
            "Напомни принять Аспирин через 30 минут" to "timer",
            "Пополни остаток Аспирин на 10 таблеток" to "stock",
            "Сверь запас Аспирин 30 таблеток" to "stock",
            "Отложи Аспирин на 15 минут" to "postpone",
            "Перенеси прием Аспирин на 30 минут" to "postpone",
        )
        assertEquals(30, phrases.size)
        phrases.forEach { (phrase, expected) ->
            val actual = when (val command = VoiceParser.parse(phrase, today)) {
                is VoiceCommand.Draft -> command.value.kind.name.lowercase()
                is VoiceCommand.Navigate -> "navigate"
                is VoiceCommand.ReadSchedule -> "read"
                is VoiceCommand.TimerReminder -> "timer"
                is VoiceCommand.AtReminder -> "absolute"
                is VoiceCommand.StockChange -> "stock"
                is VoiceCommand.Postpone -> "postpone"
                is VoiceCommand.MarkAll -> "markAll"
                is VoiceCommand.Unknown -> "unknown"
            }
            assertEquals(phrase, expected, actual)
        }
    }
}
