package io.github.voldemar64.healthapp

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class HealthTestApplication : HealthApplication() {
    override val databaseName: String = "instrumentation-health.db"
}

class HealthTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, HealthTestApplication::class.java.name, context)
}
