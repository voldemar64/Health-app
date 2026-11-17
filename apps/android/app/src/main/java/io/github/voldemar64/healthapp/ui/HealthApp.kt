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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.voldemar64.healthapp.HealthApplication
import io.github.voldemar64.healthapp.ReminderTarget
import io.github.voldemar64.healthapp.domain.LEGACY_PROFILE_ID
import java.time.LocalDate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import io.github.voldemar64.healthapp.R

@Composable
fun HealthApp(model: HealthViewModel, reminderTarget: ReminderTarget? = null,
    onReminderTargetHandled: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var renameDefault by rememberSaveable { mutableStateOf(false) }
    var pendingProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedReminderId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(reminderTarget) {
        reminderTarget?.let { target ->
            focusedReminderId = target.reminderId
            model.selectProfile(target.profileId)
            nav.navigate("today") { launchSingleTop = true }
            onReminderTargetHandled()
        }
    }
    fun select(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(topBar = {
        if (entry?.destination?.route in listOf(null, "today", "pressure", "medications", "reminders", "settings") ||
            entry?.destination?.route?.startsWith("add/") == true) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(state.profiles.find { it.id == state.activeProfileId }?.name ?: stringResource(R.string.profile_main),
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { renameDefault = false; showProfiles = true }, enabled = !state.saving,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.profiles))
                }
                TextButton(onClick = { nav.navigate("settings") }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings))
                }
            }
        }
    }, bottomBar = {
        if (entry?.destination?.route in listOf(null, "today", "pressure", "medications")) Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.weight(1f), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp) {
        NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
            listOf(Triple("today", R.string.today, R.drawable.ic_pressure),
                Triple("pressure", R.string.history, R.drawable.ic_pressure),
                Triple("medications", R.string.medications_title, R.drawable.ic_medications)).forEach { (route, title, icon) ->
                NavigationBarItem(modifier = Modifier.heightIn(min = 56.dp),
                    selected = entry?.destination?.route == route, onClick = {
                        select(route)
                    }, icon = { Icon(painterResource(icon), contentDescription = null) },
                    label = { Text(stringResource(title)) }, alwaysShowLabel = true)
            }
        }
        }
        FilledIconButton(onClick = { nav.navigate("add/${if (entry?.destination?.route == "medications") "medications" else "pressure"}") },
            modifier = Modifier.size(76.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF252525), contentColor = Color.White)) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_entry), Modifier.size(36.dp))
        }
        }
    }) { padding ->
        NavHost(nav, startDestination = "today") {
            composable("today") { TodayScreen(state, model, padding, focusedReminderId,
                addPressure = { nav.navigate("add/pressure") },
                addMedication = { nav.navigate("add/medications") },
                addReminder = { nav.navigate("reminders") },
                openMedications = { select("medications") },
                openSettings = { nav.navigate("settings") },
                openProfiles = { renameDefault = true; showProfiles = true },
                onVoice = { nav.navigate("add/pressure") }) }
            composable("pressure") { PressureScreen(state, model, padding) }
            composable("medications") { MedicationScreen(state, model, padding) }
            composable("reminders") { ReminderForm(state, model, padding) { nav.popBackStack() } }
            composable("settings") { SettingsScreen(padding) { nav.popBackStack() } }
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
    if (showProfiles) ProfileDialog(state, model, { showProfiles = false }, renameDefault) { id ->
        showProfiles = false
        if (entry?.destination?.route?.startsWith("add/") == true) pendingProfile = id
        else model.selectProfile(id)
    }
    pendingProfile?.let { id -> AlertDialog(onDismissRequest = { pendingProfile = null },
        title = { Text(stringResource(R.string.switch_profile_title)) },
        text = { Text(stringResource(R.string.switch_profile_body)) },
        confirmButton = { TextButton(onClick = { model.selectProfile(id); pendingProfile = null }) {
            Text(stringResource(R.string.switch_profile))
        } }, dismissButton = { TextButton(onClick = { pendingProfile = null }) {
            Text(stringResource(R.string.cancel))
        } }) }
}

@Composable
private fun ProfileDialog(state: HealthState, model: HealthViewModel, onClose: () -> Unit,
    renameDefault: Boolean, onSelect: (String) -> Unit) {
    var name by rememberSaveable(renameDefault) { mutableStateOf(if (renameDefault) "Основной" else "") }
    var renaming by rememberSaveable(renameDefault) {
        mutableStateOf<String?>(if (renameDefault) LEGACY_PROFILE_ID else null)
    }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.profiles)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.profiles.forEach { profile ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = profile.id == state.activeProfileId, onClick = {
                            onSelect(profile.id)
                        })
                        TextButton(onClick = { onSelect(profile.id) }, Modifier.weight(1f)) {
                            Text(profile.name)
                        }
                        TextButton(onClick = { name = profile.name; renaming = profile.id }) { Text(stringResource(R.string.edit)) }
                        if (state.profiles.size > 1) TextButton(onClick = { deleting = profile.id }) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    if (renaming != null) model.renameProfile(renaming!!, name)
                    else model.createProfile(name)
                    name = ""; renaming = null
                }, enabled = name.trim().isNotEmpty() && !state.saving) {
                    Text(stringResource(if (renaming == null) R.string.create_profile else R.string.save))
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } })
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(R.string.delete_profile_title)) },
        text = { Text(stringResource(R.string.delete_profile_body)) },
        confirmButton = { TextButton(onClick = { model.deleteProfile(id); deleting = null; onClose() }) {
            Text(stringResource(R.string.delete))
        } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
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
        if (speech.saved && speech.destination == null)
            onNavigate(if (speech.kind == io.github.voldemar64.healthapp.domain.VoiceKind.PRESSURE) "pressure" else "medications")
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
        speech.pressureForm.asPressureFormDraft(), voice::updatePressureDraft) { value ->
        voice.pause()
        model.save(onSuccess = { onNavigate("pressure") }) { savePressure(value()) }
    } else MedicationForm(null, LocalDate.now(), state.data.medications.map { it.name }.distinct(),
        padding, saving, close, header, speech.medicationForm.asMedicationFormDraft(), voice::updateMedicationDraft) { value ->
        voice.pause()
        model.save(onSuccess = { onNavigate("medications") }) {
            val (draft, _, card) = value()
            saveMedicationSchedule(draft, requireNotNull(card))
        }
    }
}

internal fun errorText(code: String) = when (code) {
    "PRESSURE" -> R.string.error_pressure
    "PULSE" -> R.string.error_pulse
    "DOSE" -> R.string.error_dose
    "MEDICATION" -> R.string.error_medication_card
    "NAME" -> R.string.error_name
    "DATES" -> R.string.error_dates
    "TIMES" -> R.string.error_times
    "WEEKDAYS" -> R.string.error_weekdays
    "FUTURE" -> R.string.error_future
    "STALE" -> R.string.error_stale
    else -> R.string.error_storage
}
