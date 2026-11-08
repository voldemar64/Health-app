package io.github.voldemar64.healthapp.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Explicit Unicode boundaries behave equally on Android ICU and JDK 21.
private fun voiceRegex(pattern: String): Regex {
    val word = "[\\p{L}\\p{N}_]"
    return Regex(pattern.replace("\\b", "(?:(?<!$word)(?=$word)|(?<=$word)(?!$word))"))
}

enum class VoiceKind { PRESSURE, MEDICATION, MARK }
enum class VoiceField { SYSTOLIC, DIASTOLIC, PULSE, NAME, AMOUNT, UNIT, TIMES, REPEAT, DAYS, DATE, END_DATE, STATUS }
data class VoiceDraft(val kind: VoiceKind, val values: Map<VoiceField, String> = emptyMap(), val id: String = newId()) {
    fun missing(): VoiceField? {
        val required = when (kind) {
            VoiceKind.PRESSURE -> listOf(VoiceField.SYSTOLIC, VoiceField.DIASTOLIC, VoiceField.PULSE)
            VoiceKind.MEDICATION -> listOf(VoiceField.NAME, VoiceField.AMOUNT, VoiceField.UNIT, VoiceField.TIMES, VoiceField.REPEAT)
            VoiceKind.MARK -> listOf(VoiceField.NAME)
        }
        return required.firstOrNull { values[it].isNullOrBlank() }
            ?: VoiceField.DAYS.takeIf { kind == VoiceKind.MEDICATION && values[VoiceField.REPEAT] == "WEEKLY" && values[it].isNullOrBlank() }
    }
}

sealed interface VoiceCommand {
    data class Draft(val value: VoiceDraft) : VoiceCommand
    data class Navigate(val destination: String) : VoiceCommand
    data class ReadSchedule(val date: LocalDate) : VoiceCommand
    data object Unknown : VoiceCommand
}

object RussianNumbers {
    private val numbers = mapOf("ноль" to 0, "нуль" to 0, "один" to 1, "одна" to 1, "одну" to 1,
        "одного" to 1, "два" to 2, "две" to 2, "двух" to 2, "три" to 3, "трех" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
        "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
        "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900,
        "тысяча" to 1000)

    fun normalize(raw: String): String {
        val words = raw.lowercase(Locale.forLanguageTag("ru")).replace('ё', 'е')
            .replace(voiceRegex("полтаблетки|половин[ау] таблетки"), "0.5 таблетки")
            .replace(voiceRegex("полтора|полторы"), "1.5")
            .replace(voiceRegex("четверть"), "0.25").replace(voiceRegex("половин[ау]"), "0.5")
            .replace(voiceRegex("[^а-яa-z0-9.,:/ -]"), " ").trim().split(voiceRegex("\\s+"))
        val result = mutableListOf<String>()
        var value: Int? = null
        var previous = Int.MAX_VALUE
        fun flush() { value?.let { result += it.toString() }; value = null; previous = Int.MAX_VALUE }
        words.forEach { word ->
            val n = numbers[word]
            if (n == null) { flush(); result += word } else {
                if (value != null && !(previous >= 100 && n in 1..99 || previous in 20..90 && n in 1..9)) flush()
                value = (value ?: 0) + n
                previous = n
            }
        }
        flush()
        return result.joinToString(" ")
            .replace(voiceRegex("(\\d+) (?:запятая|точка) (\\d+)")) { "${it.groupValues[1]}.${it.groupValues[2]}" }
            .replace(voiceRegex("(\\d+) цел(?:ых|ая|ое) (\\d+) десят(?:ых|ая)")) { "${it.groupValues[1]}.${it.groupValues[2]}" }
            .replace(voiceRegex("(\\d+) с половиной")) { "${it.groupValues[1]}.5" }
    }
}

object VoiceParser {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    private val dayRoots = listOf("понедельник", "вторник", "сред", "четверг", "пятниц", "суббот", "воскресень")
    private val pressure = voiceRegex("\\b(?:давлен[а-я]*|измерен[а-я]*)\\b")
    private val drug = voiceRegex("\\b(?:лекарств[а-я]*|препарат[а-я]*|таблет[а-я]*)\\b")
    private val add = voiceRegex("\\b(?:добав[а-я]*|запиш[а-я]*|запис[а-я]*|внес[а-я]*|созда[а-я]*|назнач[а-я]*)\\b")
    private val navigate = voiceRegex("\\b(?:откро[а-я]*|перей[а-я]*|покаж[а-я]*)\\b")
    private val mark = voiceRegex("\\b(?:отмет[а-я]*|принял[аи]?|выпил[аи]?|пропустил[аи]?)\\b")

    fun parse(raw: String, today: LocalDate): VoiceCommand {
        val text = RussianNumbers.normalize(raw)
        if (voiceRegex("\\b(?:удал[а-я]*|сотр[а-я]*|редакт[а-я]*)\\b").containsMatchIn(text)) return VoiceCommand.Unknown
        if ((text.contains("что") && voiceRegex("приним|пить|принять").containsMatchIn(text)) ||
            voiceRegex("проч[а-я]*|зачит[а-я]*|озвуч[а-я]*").containsMatchIn(text) && (drug.containsMatchIn(text) || text.contains("расписан"))) {
            return VoiceCommand.ReadSchedule(parseDate(text, today) ?: today)
        }
        if (navigate.containsMatchIn(text) && !add.containsMatchIn(text)) {
            if (pressure.containsMatchIn(text) || text.contains("статистик")) return VoiceCommand.Navigate("pressure")
            if (drug.containsMatchIn(text) || text.contains("расписан") || text.contains("календар")) return VoiceCommand.Navigate("medications")
        }
        val kind = when {
            mark.containsMatchIn(text) && !pressure.containsMatchIn(text) -> VoiceKind.MARK
            pressure.containsMatchIn(text) -> VoiceKind.PRESSURE
            add.containsMatchIn(text) && (drug.containsMatchIn(text) || text.contains("прием")) -> VoiceKind.MEDICATION
            else -> return VoiceCommand.Unknown
        }
        return VoiceCommand.Draft(fill(VoiceDraft(kind), raw, today))
    }

    fun fill(draft: VoiceDraft, raw: String, today: LocalDate, expected: VoiceField? = null): VoiceDraft {
        val text = RussianNumbers.normalize(raw)
        val values = draft.values.toMutableMap()
        expected?.let { values.remove(it) }
        val number = voiceRegex("\\d+(?:[.,]\\d+)?").find(text)?.value?.replace(',', '.')
        if (expected != null) when (expected) {
            VoiceField.NAME -> if (raw.trim().isNotBlank()) {
                values[expected] = raw.trim()
                return draft.copy(values = values)
            }
            VoiceField.SYSTOLIC, VoiceField.DIASTOLIC, VoiceField.PULSE, VoiceField.AMOUNT -> number?.let { values[expected] = it }
            VoiceField.DATE, VoiceField.END_DATE -> parseDate(text, today)?.let { values[expected] = it.format(dateFormat) }
            VoiceField.TIMES -> parseTimes("в $text").takeIf { it.isNotEmpty() }?.let { values[expected] = it.joinToString("; ") }
            else -> Unit
        }
        if (draft.kind == VoiceKind.PRESSURE && expected !in listOf(VoiceField.DATE, VoiceField.TIMES, VoiceField.NAME)) {
            voiceRegex("(\\d{1,3})\\s*(?:на|/)\\s*(\\d{1,3})").find(text)?.let {
                values[VoiceField.SYSTOLIC] = it.groupValues[1]
                values[VoiceField.DIASTOLIC] = it.groupValues[2]
            }
            voiceRegex("(?:пульс[а-я]*|сердцебиен[а-я]*)\\s*(\\d{1,3})").find(text)?.let { values[VoiceField.PULSE] = it.groupValues[1] }
            parseTimes(text).takeIf { it.isNotEmpty() }?.let { values[VoiceField.TIMES] = it.joinToString("; ") }
        }
        if (draft.kind != VoiceKind.PRESSURE) {
            val dose = voiceRegex("(\\d+(?:[.,]\\d+)?)\\s*(таблет[а-я]*|табл\\.?|миллиграм[а-я]*|мг|грам[а-я]*|г\\b)").find(text)
            dose?.let {
                values[VoiceField.AMOUNT] = it.groupValues[1].replace(',', '.')
                values[VoiceField.UNIT] = unit(it.groupValues[2])!!
            }
            if (expected == VoiceField.UNIT) unit(text)?.let { values[VoiceField.UNIT] = it }
            if (expected == null && values[VoiceField.NAME].isNullOrBlank()) {
                val prefix = text.replace(voiceRegex("^(?:пожалуйста )?(?:добав[а-я]*|запиш[а-я]*|запис[а-я]*|внес[а-я]*|созда[а-я]*|назнач[а-я]*|отмет[а-я]*|принял[аи]?|выпил[аи]?|пропустил[аи]?)\\s*"), "")
                    .replace(voiceRegex("^(?:(?:лекарств[а-я]*|препарат[а-я]*|таблет[а-я]*|прием[а-я]*|утренн[а-я]*|вечерн[а-я]*|дневн[а-я]*)\\s+)+"), "")
                val name = prefix.split(voiceRegex("\\s+(?=\\d|кажд|ежеднев|однократ|разово|сегодня|завтра|вчера|по |в |как |принят|пропущ)"), limit = 2).first().trim(' ', '.', ',')
                if (name.isNotBlank() && !drug.matches(name)) values[VoiceField.NAME] = name
            }
            parseTimes(text).takeIf { it.isNotEmpty() }?.let { values[VoiceField.TIMES] = it.joinToString("; ") }
            val days = dayRoots.mapIndexedNotNull { index, root -> (index + 1).takeIf { text.contains(root) } }
            if (days.isNotEmpty()) {
                values[VoiceField.DAYS] = days.joinToString(",")
                values[VoiceField.REPEAT] = "WEEKLY"
            } else when {
                voiceRegex("кажд[а-я]* день|ежеднев[а-я]*|ежесуточ[а-я]*").containsMatchIn(text) -> values[VoiceField.REPEAT] = "DAILY"
                voiceRegex("недел[а-я]*|еженедель[а-я]*").containsMatchIn(text) -> values[VoiceField.REPEAT] = "WEEKLY"
                voiceRegex("однократ[а-я]*|разов[а-я]*|один раз|1 раз|без повтор[а-я]*").containsMatchIn(text) -> values[VoiceField.REPEAT] = "ONCE"
            }
            if (draft.kind == VoiceKind.MARK) {
                when {
                    text.contains("пропус") || text.contains("пропущ") -> values[VoiceField.STATUS] = "SKIPPED"
                    text.contains("принят") || text.contains("принял") || text.contains("выпил") -> values[VoiceField.STATUS] = "TAKEN"
                    expected != VoiceField.STATUS -> values.putIfAbsent(VoiceField.STATUS, "TAKEN")
                }
            }
        }
        if (expected != VoiceField.END_DATE) parseDate(text.substringBefore(" до "), today)?.let { values[VoiceField.DATE] = it.format(dateFormat) }
        if (text.contains(" до ")) parseDate(text.substringAfter(" до "), today)?.let { values[VoiceField.END_DATE] = it.format(dateFormat) }
        return draft.copy(values = values)
    }

    private fun unit(text: String): String? = when {
        voiceRegex("таблет|табл").containsMatchIn(text) -> "TABLET"
        voiceRegex("миллиграм|\\bмг\\b").containsMatchIn(text) -> "MG"
        voiceRegex("грам|\\bг\\b").containsMatchIn(text) -> "G"
        else -> null
    }

    fun parseDate(text: String, today: LocalDate): LocalDate? = when {
        text.contains("послезавтра") -> today.plusDays(2)
        text.contains("позавчера") -> today.minusDays(2)
        text.contains("завтра") -> today.plusDays(1)
        text.contains("вчера") -> today.minusDays(1)
        text.contains("сегодня") -> today
        else -> voiceRegex("\\b(\\d{1,2})[./](\\d{1,2})[./](\\d{4})\\b").find(text)?.let {
            runCatching { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
        }
    }

    private fun parseTimes(text: String): List<String> = voiceRegex("(?:\\bв|\\bи в|\\bи)\\s+(\\d{1,2})(?:(?::|\\s+час[а-я]*\\s*|\\s+)(\\d{1,2}))?\\s*(утра|вечера|дня|ночи)?")
        .findAll(text).mapNotNull { match ->
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (match.groupValues[3] in listOf("вечера", "дня") && hour in 1..11) hour += 12
            if (match.groupValues[3] == "ночи" && hour == 12) hour = 0
            if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute).toString() else null
        }.distinct().toList()

    fun medication(draft: VoiceDraft, today: LocalDate): ScheduleDraft {
        val values = draft.values
        val amount = Dose.parse(values[VoiceField.AMOUNT].orEmpty(), DoseUnit.valueOf(values[VoiceField.UNIT].orEmpty()))
        val date = values[VoiceField.DATE]?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it, dateFormat) } ?: today
        val times = values[VoiceField.TIMES].orEmpty().split(';').map { LocalTime.parse(it.trim()) }
        return ScheduleDraft(values[VoiceField.NAME].orEmpty(), Repeat.valueOf(values[VoiceField.REPEAT].orEmpty()),
            values[VoiceField.DAYS].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.let(DayOfWeek::of) }.toSet(),
            date, values[VoiceField.END_DATE]?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it, dateFormat) },
            times.mapIndexed { index, time -> DoseTime("${draft.id}-$index", time, amount) })
    }
}
