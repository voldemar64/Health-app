package io.github.voldemar64.healthapp

import android.graphics.Color
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.voldemar64.healthapp.ui.HealthApp
import io.github.voldemar64.healthapp.ui.HealthViewModel
import io.github.voldemar64.healthapp.ui.theme.HealthAppTheme
import java.util.Locale

data class ReminderTarget(val profileId: String, val reminderId: String)

class MainActivity : ComponentActivity() {
    private val reminderTarget = mutableStateOf<ReminderTarget?>(null)

    private fun targetFrom(intent: Intent?): ReminderTarget? {
        val profileId = intent?.getStringExtra("reminder_profile_id") ?: return null
        val reminderId = intent.getStringExtra("reminder_target_id") ?: return null
        return ReminderTarget(profileId, reminderId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminderTarget.value = targetFrom(intent)
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ru")
        Locale.setDefault(locale)
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reminderTarget.value = targetFrom(intent)
        (application as HealthApplication).checkSpeech()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            val app = application as HealthApplication
            val model: HealthViewModel = viewModel(factory = HealthViewModel.factory(app.repository,
                app.reminderCoordinator::cancelProfile))
            HealthAppTheme { HealthApp(model, reminderTarget.value) { reminderTarget.value = null } }
        }
    }
}
