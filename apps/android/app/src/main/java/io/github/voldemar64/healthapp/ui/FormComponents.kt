package io.github.voldemar64.healthapp.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.voldemar64.healthapp.R
import io.github.voldemar64.healthapp.domain.DoseUnit
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar

internal val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
internal val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun ScreenColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
internal fun Heading(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
}

@Composable
internal fun ActionButton(@StringRes title: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = 56.dp), enabled = enabled) { Text(stringResource(title)) }
}

@Composable
internal fun Input(value: String, onChange: (String) -> Unit, @StringRes label: Int,
    keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        singleLine = singleLine, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

@Composable
internal fun DateButton(@StringRes label: Int, date: LocalDate, onChange: (LocalDate) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        DatePickerDialog(context, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) },
            date.year, date.monthValue - 1, date.dayOfMonth).apply {
                datePicker.firstDayOfWeek = Calendar.MONDAY
            }.show()
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.label_value, stringResource(label), date.format(dateFormat)))
    }
}

@Composable
internal fun TimeButton(time: LocalTime, onChange: (LocalTime) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        TimePickerDialog(context, { _, h, m -> onChange(LocalTime.of(h, m)) }, time.hour, time.minute, true).show()
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.label_value, stringResource(R.string.time), time.format(timeFormat)))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> Choices(values: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(selected == value, { onSelect(value) }, label = { Text(label(value)) },
                modifier = Modifier.heightIn(min = 56.dp))
        }
    }
}

internal fun unitResource(unit: DoseUnit) = when (unit) {
    DoseUnit.TABLET -> R.string.unit_tablet
    DoseUnit.MG -> R.string.unit_mg
    DoseUnit.G -> R.string.unit_g
}

@Composable
internal fun EmptyState(@StringRes title: Int, @StringRes body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
}
