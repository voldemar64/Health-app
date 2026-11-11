package io.github.voldemar64.healthapp.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.voldemar64.healthapp.domain.*
import java.time.*

internal fun encodeDraft(d: VoiceDraft): ArrayList<String> = ArrayList(listOf(d.kind.name, d.id, (d.slots != null).toString()) +
    VoiceField.entries.map { d.values[it].orEmpty() } + d.slots.orEmpty().flatMap { listOf(it.id, it.time, it.amount, it.unit) })
internal fun decodeDraft(v: List<String>) = VoiceDraft(VoiceKind.valueOf(v[0]),
    VoiceField.entries.mapIndexed { i, f -> f to v[i + 3] }.toMap(), v[1],
    if (v[2].toBoolean()) v.drop(3 + VoiceField.entries.size).chunked(4).map { VoiceDose(it[0], it[1], it[2], it[3]) } else null)
internal val entryDraftSaver = listSaver<VoiceDraft, String>(save = { encodeDraft(it) }, restore = { decodeDraft(it) })

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
