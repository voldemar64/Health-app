package io.github.voldemar64.healthapp

import android.app.Application
import io.github.voldemar64.healthapp.data.HealthDatabase
import io.github.voldemar64.healthapp.data.RoomHealthRepository

open class HealthApplication : Application() {
    protected open val databaseName: String = "health.db"
    val database by lazy { HealthDatabase.open(this, databaseName) }
    val repository by lazy { RoomHealthRepository(database) }
}
