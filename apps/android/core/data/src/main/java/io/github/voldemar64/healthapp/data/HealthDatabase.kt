package io.github.voldemar64.healthapp.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "pressures")
data class PressureRow(
    @PrimaryKey val id: String, val systolic: Int, val diastolic: Int, val pulse: Int?,
    val measuredAt: String, val zoneId: String, val note: String,
)

@Entity(tableName = "schedules")
data class ScheduleRow(
    @PrimaryKey val id: String, val seriesId: String, val effectiveFrom: String,
    val effectiveUntil: String?, val name: String, val repeat: String, val weekdayMask: Int,
    val startDate: String, val endDate: String?, val slotsJson: String,
)

@Entity(tableName = "occurrence_edits")
data class OccurrenceRow(
    @PrimaryKey val key: String, val seriesId: String, val revisionId: String, val slotId: String,
    val date: String, val time: String, val name: String, val amount: String, val unit: String,
)

@Entity(tableName = "intakes")
data class IntakeRow(
    @PrimaryKey val key: String, val seriesId: String, val revisionId: String, val slotId: String,
    val date: String, val time: String, val name: String, val amount: String, val unit: String,
    val status: String, val plannedAt: String, val zoneId: String, val takenAt: String?,
)

@Dao
interface HealthDao {
    @Query("SELECT * FROM pressures") suspend fun pressures(): List<PressureRow>
    @Query("SELECT * FROM schedules") suspend fun schedules(): List<ScheduleRow>
    @Query("SELECT * FROM occurrence_edits") suspend fun exceptions(): List<OccurrenceRow>
    @Query("SELECT * FROM intakes") suspend fun intakes(): List<IntakeRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun pressure(row: PressureRow)
    @Query("DELETE FROM pressures WHERE id = :id") suspend fun deletePressure(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun schedule(row: ScheduleRow)
    @Query("DELETE FROM schedules WHERE id = :id") suspend fun deleteSchedule(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun exception(row: OccurrenceRow)
    @Query("DELETE FROM occurrence_edits WHERE `key` = :key") suspend fun deleteException(key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun intake(row: IntakeRow)
    @Query("DELETE FROM intakes WHERE `key` = :key") suspend fun deleteIntake(key: String)
}

@Database(entities = [PressureRow::class, ScheduleRow::class, OccurrenceRow::class, IntakeRow::class],
    version = 1, exportSchema = true)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun dao(): HealthDao
    companion object {
        fun open(context: Context, name: String = "health.db"): HealthDatabase =
            Room.databaseBuilder(context.applicationContext, HealthDatabase::class.java, name).build()
    }
}
