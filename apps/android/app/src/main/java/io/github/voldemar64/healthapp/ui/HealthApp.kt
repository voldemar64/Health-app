package io.github.voldemar64.healthapp.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import io.github.voldemar64.healthapp.R

@Composable
fun HealthApp(model: HealthViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    Scaffold(bottomBar = {
        NavigationBar {
            listOf(Triple("pressure", R.string.pressure_title, R.drawable.ic_pressure),
                Triple("medications", R.string.medications_title, R.drawable.ic_medications)).forEach { (route, title, icon) ->
                NavigationBarItem(modifier = Modifier.heightIn(min = 56.dp),
                    selected = entry?.destination?.route == route, onClick = {
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }, icon = { Icon(painterResource(icon), contentDescription = null) },
                    label = { Text(stringResource(title)) }, alwaysShowLabel = true)
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = "pressure") {
            composable("pressure") { PressureScreen(state, model, padding) }
            composable("medications") { MedicationScreen(state, model, padding) }
        }
    }
    state.error?.let { code ->
        AlertDialog(onDismissRequest = model::clearError,
            title = { Text(stringResource(R.string.save_failed)) },
            text = { Text(stringResource(errorText(code))) },
            confirmButton = { ActionButton(R.string.close, onClick = model::clearError) })
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
