package io.github.voldemar64.healthapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.*

@Composable
internal fun MedicationCardForm(state: HealthState, model: HealthViewModel, padding: PaddingValues,
    onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var form by rememberSaveable { mutableStateOf("") }
    var strength by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(DoseUnit.TABLET) }
    var initial by rememberSaveable { mutableStateOf("") }
    ScreenColumn(padding) {
        Heading(R.string.new_medication)
        Input(name, { name = it }, R.string.medication_name)
        Input(form, { form = it }, R.string.medication_form)
        Input(strength, { strength = it }, R.string.medication_strength)
        Text(stringResource(R.string.medication_strength_unknown))
        Choices(DoseUnit.entries, unit, { stringResource(unitResource(it)) }) { unit = it }
        Input(initial, { initial = it }, R.string.medication_initial_stock, KeyboardType.Decimal)
        ActionButton(R.string.save, Modifier.fillMaxWidth(), enabled = !state.saving &&
            name.isNotBlank() && form.isNotBlank()) {
            model.save(onSuccess = onClose) {
                createMedication(MedicationCardInput(name.trim(), form.trim(), strength.trim().ifBlank { null },
                    unit, initial.takeIf(String::isNotBlank)?.let(::parseStockAmount)))
            }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cancel))
        }
    }
}
