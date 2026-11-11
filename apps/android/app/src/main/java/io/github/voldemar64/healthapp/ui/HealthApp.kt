package io.github.voldemar64.healthapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.voldemar64.healthapp.HealthApplication
import java.time.LocalDate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import io.github.voldemar64.healthapp.R

@Composable
fun HealthApp(model: HealthViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    fun select(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(bottomBar = {
        if (entry?.destination?.route in listOf(null, "pressure", "medications")) Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.weight(1f), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp) {
        NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
            listOf(Triple("pressure", R.string.pressure_title, R.drawable.ic_pressure),
                Triple("medications", R.string.medications_title, R.drawable.ic_medications)).forEach { (route, title, icon) ->
                NavigationBarItem(modifier = Modifier.heightIn(min = 56.dp),
                    selected = entry?.destination?.route == route, onClick = {
                        select(route)
                    }, icon = { Icon(painterResource(icon), contentDescription = null) },
                    label = { Text(stringResource(title)) }, alwaysShowLabel = true)
            }
        }
        }
        FilledIconButton(onClick = { nav.navigate("add/${entry?.destination?.route ?: "pressure"}") },
            modifier = Modifier.size(76.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF252525), contentColor = Color.White)) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_entry), Modifier.size(36.dp))
        }
        }
    }) { padding ->
        NavHost(nav, startDestination = "pressure") {
            composable("pressure") { PressureScreen(state, model, padding) }
            composable("medications") { MedicationScreen(state, model, padding) }
            composable("add/{tab}") { backStack ->
                val context = LocalContext.current
                val voice: VoiceViewModel = viewModel(backStack, factory = VoiceViewModel.factory(context,
                    (context.applicationContext as HealthApplication).repository))
                EntryScreen(backStack.arguments?.getString("tab") ?: "pressure", state, model, voice, padding,
                    onClose = { nav.popBackStack() }, onNavigate = { route ->
                        nav.popBackStack()
                        select(route)
                    })
            }
        }
    }
    state.error?.let { code ->
        AlertDialog(onDismissRequest = model::clearError,
            title = { Text(stringResource(R.string.save_failed)) },
            text = { Text(stringResource(errorText(code))) },
            confirmButton = { ActionButton(R.string.close, onClick = model::clearError) })
    }
}

@Composable
internal fun EntryScreen(initialTab: String, state: HealthState, model: HealthViewModel, voice: VoiceViewModel,
    padding: PaddingValues, onClose: () -> Unit, onNavigate: (String) -> Unit) {
    val speech by voice.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (speech.dialog.draft == null) voice.initialize(if (initialTab == "pressure") io.github.voldemar64.healthapp.domain.VoiceKind.PRESSURE
            else io.github.voldemar64.healthapp.domain.VoiceKind.MEDICATION)
    }
    LaunchedEffect(speech.destination, speech.saved) {
        speech.destination?.let(onNavigate)
        if (speech.saved) onNavigate(if (speech.kind == io.github.voldemar64.healthapp.domain.VoiceKind.PRESSURE) "pressure" else "medications")
    }
    val tab = if (speech.kind == io.github.voldemar64.healthapp.domain.VoiceKind.PRESSURE) "pressure" else "medications"
    val saving = state.saving || speech.dialog.phase == io.github.voldemar64.healthapp.domain.DialogPhase.COMMIT && speech.error == null
    val close = { voice.pause(); onClose() }
    val header: @Composable ColumnScope.() -> Unit = {
        Choices(listOf("pressure", "medications"), tab,
            { stringResource(if (it == "pressure") R.string.pressure_title else R.string.medications_title) }) {
            if (!saving) voice.selectKind(if (it == "pressure") io.github.voldemar64.healthapp.domain.VoiceKind.PRESSURE
                else io.github.voldemar64.healthapp.domain.VoiceKind.MEDICATION)
        }
        VoiceControls(voice, speech, !saving)
    }
    if (tab == "pressure") PressureForm(null, saving, padding, close, header,
        speech.pressureForm, voice::updateDraft) { value ->
        voice.pause()
        model.save(onSuccess = { onNavigate("pressure") }) { savePressure(value()) }
    } else MedicationForm(null, LocalDate.now(), state.data.schedules.map { it.draft.name }.distinct(),
        padding, saving, close, header, speech.medicationForm, voice::updateDraft) { value ->
        voice.pause()
        model.save(onSuccess = { onNavigate("medications") }) { saveSchedule(value().first) }
    }
}

internal fun errorText(code: String) = when (code) {
    "PRESSURE" -> R.string.error_pressure
    "PULSE" -> R.string.error_pulse
    "DOSE" -> R.string.error_dose
    "NAME" -> R.string.error_name
    "DATES" -> R.string.error_dates
    "TIMES" -> R.string.error_times
    "WEEKDAYS" -> R.string.error_weekdays
    "FUTURE" -> R.string.error_future
    "STALE" -> R.string.error_stale
    else -> R.string.error_storage
}
