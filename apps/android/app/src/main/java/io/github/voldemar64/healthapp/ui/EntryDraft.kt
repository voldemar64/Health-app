package io.github.voldemar64.healthapp.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.voldemar64.healthapp.domain.*
import java.time.*

internal fun encodeDraft(d: VoiceDraft): ArrayList<String> = ArrayList(listOf("v2", d.kind.name, d.id,
    (d.slots != null).toString(), VoiceField.entries.size.toString()) +
    VoiceField.entries.flatMap { listOf(it.name, d.values[it].orEmpty()) } +
    d.slots.orEmpty().flatMap { listOf(it.id, it.time, it.amount, it.unit) })
internal fun decodeDraft(v: List<String>): VoiceDraft {
    if (v.firstOrNull() == "v2") {
        val count = v[4].toInt()
        val fields = v.drop(5).take(count * 2).chunked(2).mapNotNull { pair ->
            VoiceField.entries.find { it.name == pair[0] }?.let { it to pair[1] }
        }.toMap()
        return VoiceDraft(VoiceKind.valueOf(v[1]), fields, v[2],
            if (v[3].toBoolean()) v.drop(5 + count * 2).chunked(4).map { VoiceDose(it[0], it[1], it[2], it[3]) }
            else null)
    }
    val legacyFields = listOf(VoiceField.SYSTOLIC, VoiceField.DIASTOLIC, VoiceField.PULSE,
        VoiceField.NAME, VoiceField.AMOUNT, VoiceField.UNIT, VoiceField.TIMES, VoiceField.REPEAT,
        VoiceField.DAYS, VoiceField.DATE, VoiceField.END_DATE, VoiceField.STATUS)
    return VoiceDraft(VoiceKind.valueOf(v[0]), legacyFields.mapIndexed { i, field ->
        field to v.getOrElse(i + 3) { "" }
    }.toMap(), v[1], if (v[2].toBoolean()) v.drop(3 + legacyFields.size).chunked(4)
        .filter { it.size == 4 }.map { VoiceDose(it[0], it[1], it[2], it[3]) } else null)
}
internal val entryDraftSaver = listSaver<VoiceDraft, String>(save = { encodeDraft(it) }, restore = { decodeDraft(it) })

internal data class PressureFormDraft(val id: String, val systolic: String, val diastolic: String,
    val pulse: String, val date: String, val time: String)
private val pressureFormSaver = listSaver<PressureFormDraft, String>(
    save = { listOf(it.id, it.systolic, it.diastolic, it.pulse, it.date, it.time) },
    restore = { PressureFormDraft(it[0], it[1], it[2], it[3], it[4], it[5]) })
@Composable
internal fun rememberPressureFormDraft(existing: PressureMeasurement?) =
    rememberSaveable(existing?.id, stateSaver = pressureFormSaver) {
        mutableStateOf(pressureDraft(existing).asPressureFormDraft())
    }
internal fun VoiceDraft.asPressureFormDraft() = PressureFormDraft(id, value(VoiceField.SYSTOLIC),
    value(VoiceField.DIASTOLIC), value(VoiceField.PULSE), value(VoiceField.DATE), value(VoiceField.TIMES))
internal fun PressureFormDraft.toVoiceDraft(base: VoiceDraft) = base.copy(id = id,
    values = base.values + mapOf(VoiceField.SYSTOLIC to systolic, VoiceField.DIASTOLIC to diastolic,
        VoiceField.PULSE to pulse, VoiceField.DATE to date, VoiceField.TIMES to time))

internal data class MedicationSlotDraft(val id: String, val time: String, val amount: String,
    val unit: DoseUnit)
internal data class MedicationFormDraft(val id: String, val name: String, val repeat: Repeat,
    val weekdays: Set<DayOfWeek>, val start: String, val end: String,
    val slots: List<MedicationSlotDraft>, val form: String = "", val strength: String = "",
    val stock: String = "") {
    fun schedule(): ScheduleDraft = ScheduleDraft(name.trim(), repeat, weekdays,
        LocalDate.parse(start, dateFormat), end.takeIf(String::isNotBlank)?.let { LocalDate.parse(it, dateFormat) },
        slots.map { DoseTime(it.id, LocalTime.parse(it.time), Dose.parse(it.amount, it.unit)) })
}
private val medicationFormSaver = listSaver<MedicationFormDraft, String>(
    save = { d -> listOf(d.id, d.name, d.repeat.name, d.weekdays.sorted().joinToString(",") { it.value.toString() },
        d.start, d.end, d.form, d.strength, d.stock) +
        d.slots.flatMap { listOf(it.id, it.time, it.amount, it.unit.name) } },
    restore = { values -> MedicationFormDraft(values[0], values[1], Repeat.valueOf(values[2]),
        values[3].split(',').mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet(),
        values[4], values[5],
        values.drop(9).chunked(4).filter { it.size == 4 }.map { MedicationSlotDraft(it[0], it[1], it[2], DoseUnit.valueOf(it[3])) },
        values[6], values[7], values[8]) })

@Composable
internal fun rememberMedicationFormDraft(existing: ScheduleRevision?, date: LocalDate,
    existingCard: UserMedication?) = rememberSaveable(existing?.id, existingCard?.id,
    stateSaver = medicationFormSaver) {
    val initial = medicationDraft(existing, date).asMedicationFormDraft()
    mutableStateOf(if (existingCard == null) initial else initial.copy(name = existingCard.name,
        slots = initial.slots.map { it.copy(unit = existingCard.unit) }))
}

internal fun VoiceDraft.asMedicationFormDraft() = MedicationFormDraft(id, value(VoiceField.NAME),
    Repeat.entries.find { it.name == value(VoiceField.REPEAT) } ?: Repeat.DAILY,
    value(VoiceField.DAYS).split(',').mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet(),
    value(VoiceField.DATE), value(VoiceField.END_DATE), doses().map {
        MedicationSlotDraft(it.id, it.time, it.amount, DoseUnit.valueOf(it.unit))
    }, value(VoiceField.FORM), value(VoiceField.STRENGTH),
    value(VoiceField.STOCK).takeUnless { it == "UNKNOWN" }.orEmpty())

internal fun MedicationFormDraft.toVoiceDraft(base: VoiceDraft): VoiceDraft = base.copy(id = id,
    values = base.values + mapOf(VoiceField.NAME to name, VoiceField.REPEAT to repeat.name,
        VoiceField.DAYS to weekdays.sorted().joinToString(",") { it.value.toString() },
        VoiceField.DATE to start, VoiceField.END_DATE to end, VoiceField.FORM to form,
        VoiceField.STRENGTH to strength, VoiceField.STOCK to stock,
        VoiceField.TIMES to slots.joinToString("; ") { it.time },
        VoiceField.AMOUNT to slots.firstOrNull()?.amount.orEmpty(),
        VoiceField.UNIT to slots.firstOrNull()?.unit?.name.orEmpty()),
    slots = slots.map { VoiceDose(it.id, it.time, it.amount, it.unit.name) })

internal fun pressureDraft(existing: PressureMeasurement? = null): VoiceDraft {
    val local = (existing?.measuredAt ?: Instant.now()).atZone(ZoneId.systemDefault())
    return VoiceDraft(VoiceKind.PRESSURE, mapOf(
        VoiceField.SYSTOLIC to (existing?.systolic?.toString() ?: ""),
        VoiceField.DIASTOLIC to (existing?.diastolic?.toString() ?: ""),
        VoiceField.PULSE to (existing?.pulse?.toString() ?: ""),
        VoiceField.DATE to local.format(dateFormat), VoiceField.TIMES to local.format(timeFormat)), existing?.id ?: newId())
}

internal fun medicationDraft(existing: ScheduleRevision? = null, date: LocalDate = LocalDate.now()): VoiceDraft {
    val d = existing?.draft
    val slots = d?.slots?.map { VoiceDose(it.id, it.time.toString(), it.dose.text, it.dose.unit.name) } ?: listOf(VoiceDose())
    return VoiceDraft(VoiceKind.MEDICATION, mapOf(
        VoiceField.NAME to d?.name.orEmpty(), VoiceField.REPEAT to (d?.repeat?.name ?: "DAILY"),
        VoiceField.DAYS to d?.weekdays.orEmpty().sorted().joinToString(",") { it.value.toString() },
        VoiceField.DATE to maxOf(date, LocalDate.now()).format(dateFormat),
        VoiceField.END_DATE to (d?.endDate?.format(dateFormat) ?: "")),
        slots = slots).withSlots(slots)
}

internal fun VoiceDraft.value(field: VoiceField) = values[field].orEmpty()
internal fun VoiceDraft.withValue(field: VoiceField, value: String) = copy(values = values + (field to value))
internal fun VoiceDraft.doses(): List<VoiceDose> = slots ?: value(VoiceField.TIMES).split(';').filter { it.isNotBlank() }.mapIndexed { i, time ->
    VoiceDose("$id-$i", time.trim(), value(VoiceField.AMOUNT), value(VoiceField.UNIT).ifBlank { "TABLET" })
}.ifEmpty { listOf(VoiceDose("$id-0", "09:00", value(VoiceField.AMOUNT), value(VoiceField.UNIT).ifBlank { "TABLET" })) }
internal fun VoiceDraft.withSlots(items: List<VoiceDose>): VoiceDraft = copy(slots = items, values = values + mapOf(
    VoiceField.TIMES to items.joinToString("; ") { it.time }, VoiceField.AMOUNT to items.firstOrNull()?.amount.orEmpty(),
    VoiceField.UNIT to items.firstOrNull()?.unit.orEmpty()))

@Composable
internal fun rememberLocalDraft(initial: () -> VoiceDraft) = rememberSaveable(stateSaver = entryDraftSaver) { mutableStateOf(initial()) }
