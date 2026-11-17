package io.github.voldemar64.healthapp.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.voldemar64.healthapp.domain.LEGACY_PROFILE_ID
import org.json.JSONArray

@Entity(tableName = "user_medications", indices = [Index("userId")])
data class UserMedicationRow(
    @PrimaryKey val id: String, val userId: String, val name: String, val form: String,
    val strength: String?, val unit: String, val active: Boolean, val createdAt: String,
)

@Entity(tableName = "schedule_slots", primaryKeys = ["revisionId", "id"], indices = [Index("revisionId")])
data class ScheduleSlotRow(
    val revisionId: String, val id: String, val time: String, val amount: String, val unit: String,
)

@Entity(tableName = "stock_movements", indices = [Index("medicationId"), Index("userId")])
data class StockMovementRow(
    @PrimaryKey val id: String, val userId: String, val medicationId: String,
    val type: String, val amount: String, val occurredAt: String,
    val intakeKey: String?, val reversesId: String?,
)

@Entity(tableName = "reminders", indices = [Index("userId"), Index("intakeKey")])
data class ReminderRow(
    @PrimaryKey val id: String, val userId: String, val intakeKey: String?,
    val type: String, val triggerAt: String, val status: String, val requestCode: Int,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    val amount: String? = null, val unit: String? = null, val medicationId: String? = null,
    val elapsedAtMillis: Long? = null, val bootCount: Int? = null,
)

@Entity(tableName = "profiles")
data class ProfileRow(@PrimaryKey val id: String, val name: String, val createdAt: String)

@Entity(tableName = "pressures", indices = [Index("userId")])
data class PressureRow(
    @PrimaryKey val id: String, val systolic: Int, val diastolic: Int, val pulse: Int?,
    val measuredAt: String, val zoneId: String, val note: String,
    @ColumnInfo(defaultValue = "'legacy-profile'") val userId: String = LEGACY_PROFILE_ID,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val inputSource: String = "UNKNOWN",
)

@Entity(tableName = "schedules", indices = [Index("userId")])
data class ScheduleRow(
    @PrimaryKey val id: String, val seriesId: String, val effectiveFrom: String,
    val effectiveUntil: String?, val name: String, val repeat: String, val weekdayMask: Int,
    val startDate: String, val endDate: String?, val slotsJson: String,
    @ColumnInfo(defaultValue = "'legacy-profile'") val userId: String = LEGACY_PROFILE_ID,
    @ColumnInfo(defaultValue = "''") val medicationId: String = "",
)

@Entity(tableName = "occurrence_edits", indices = [Index("userId")])
data class OccurrenceRow(
    @PrimaryKey val key: String, val seriesId: String, val revisionId: String, val slotId: String,
    val date: String, val time: String, val name: String, val amount: String, val unit: String,
    @ColumnInfo(defaultValue = "'legacy-profile'") val userId: String = LEGACY_PROFILE_ID,
)

@Entity(tableName = "intakes", indices = [Index("userId")])
data class IntakeRow(
    @PrimaryKey val key: String, val seriesId: String, val revisionId: String, val slotId: String,
    val date: String, val time: String, val name: String, val amount: String, val unit: String,
    val status: String, val plannedAt: String, val zoneId: String, val takenAt: String?,
    @ColumnInfo(defaultValue = "'legacy-profile'") val userId: String = LEGACY_PROFILE_ID,
    val strength: String? = null,
)

@Dao
interface HealthDao {
    @Query("SELECT * FROM user_medications WHERE userId = :id") suspend fun medications(id: String): List<UserMedicationRow>
    @Query("SELECT * FROM user_medications WHERE id = :id") suspend fun medication(id: String): UserMedicationRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun medication(row: UserMedicationRow)
    @Query("DELETE FROM user_medications WHERE userId = :id") suspend fun deleteProfileMedications(id: String)
    @Query("SELECT * FROM stock_movements WHERE userId = :id ORDER BY rowid") suspend fun stockMovements(id: String): List<StockMovementRow>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun stockMovement(row: StockMovementRow)
    @Query("DELETE FROM stock_movements WHERE userId = :id") suspend fun deleteProfileStockMovements(id: String)
    @Query("DELETE FROM reminders WHERE userId = :id") suspend fun deleteProfileReminders(id: String)
    @Query("SELECT * FROM reminders WHERE userId = :id") suspend fun profileReminders(id: String): List<ReminderRow>
    @Query("SELECT * FROM reminders ORDER BY rowid") suspend fun reminders(): List<ReminderRow>
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun reminder(id: String): ReminderRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun reminder(row: ReminderRow)
    @Query("UPDATE reminders SET status = :status WHERE id = :id") suspend fun reminderStatus(id: String, status: String)
    @Query("SELECT schedule_slots.* FROM schedule_slots JOIN schedules ON schedules.id = schedule_slots.revisionId WHERE schedules.userId = :id")
    suspend fun slots(id: String): List<ScheduleSlotRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun slot(row: ScheduleSlotRow)
    @Query("DELETE FROM schedule_slots WHERE revisionId = :revisionId") suspend fun deleteSlots(revisionId: String)
    @Query("DELETE FROM schedule_slots WHERE revisionId IN (SELECT id FROM schedules WHERE userId = :id)") suspend fun deleteProfileSlots(id: String)
    @Query("SELECT * FROM profiles ORDER BY createdAt, id") suspend fun profiles(): List<ProfileRow>
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun profile(id: String): ProfileRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun profile(row: ProfileRow)
    @Query("UPDATE profiles SET name = :name WHERE id = :id") suspend fun renameProfile(id: String, name: String)
    @Query("DELETE FROM profiles WHERE id = :id") suspend fun deleteProfile(id: String)
    @Query("DELETE FROM pressures WHERE userId = :id") suspend fun deleteProfilePressures(id: String)
    @Query("DELETE FROM schedules WHERE userId = :id") suspend fun deleteProfileSchedules(id: String)
    @Query("DELETE FROM occurrence_edits WHERE userId = :id") suspend fun deleteProfileExceptions(id: String)
    @Query("DELETE FROM intakes WHERE userId = :id") suspend fun deleteProfileIntakes(id: String)
    @Query("SELECT * FROM pressures WHERE userId = :id") suspend fun pressures(id: String = LEGACY_PROFILE_ID): List<PressureRow>
    @Query("SELECT * FROM pressures WHERE userId = :id AND measuredAt >= :start AND measuredAt < :end")
    suspend fun pressuresBetween(id: String, start: String, end: String): List<PressureRow>
    @Query("SELECT userId FROM pressures WHERE id = :id") suspend fun pressureOwner(id: String): String?
    @Query("SELECT * FROM schedules WHERE userId = :id") suspend fun schedules(id: String = LEGACY_PROFILE_ID): List<ScheduleRow>
    @Query("SELECT * FROM schedules WHERE userId = :id AND startDate <= :endDate AND (endDate IS NULL OR endDate >= :startDate) AND effectiveFrom < :endLocal AND (effectiveUntil IS NULL OR effectiveUntil > :startLocal)")
    suspend fun schedulesBetween(id: String, startDate: String, endDate: String,
        startLocal: String, endLocal: String): List<ScheduleRow>
    @Query("SELECT * FROM occurrence_edits WHERE userId = :id") suspend fun exceptions(id: String = LEGACY_PROFILE_ID): List<OccurrenceRow>
    @Query("SELECT * FROM occurrence_edits WHERE userId = :id AND date BETWEEN :start AND :end")
    suspend fun exceptionsBetween(id: String, start: String, end: String): List<OccurrenceRow>
    @Query("SELECT * FROM intakes WHERE userId = :id") suspend fun intakes(id: String = LEGACY_PROFILE_ID): List<IntakeRow>
    @Query("SELECT * FROM intakes WHERE userId = :id AND date BETWEEN :start AND :end")
    suspend fun intakesBetween(id: String, start: String, end: String): List<IntakeRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun pressure(row: PressureRow)
    @Query("DELETE FROM pressures WHERE id = :id AND userId = :userId") suspend fun deletePressure(id: String, userId: String = LEGACY_PROFILE_ID)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun schedule(row: ScheduleRow)
    @Query("DELETE FROM schedules WHERE id = :id") suspend fun deleteSchedule(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun exception(row: OccurrenceRow)
    @Query("DELETE FROM occurrence_edits WHERE `key` = :key") suspend fun deleteException(key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun intake(row: IntakeRow)
    @Query("DELETE FROM intakes WHERE `key` = :key") suspend fun deleteIntake(key: String)
}

@Database(entities = [ProfileRow::class, PressureRow::class, ScheduleRow::class, OccurrenceRow::class,
    IntakeRow::class, UserMedicationRow::class, ScheduleSlotRow::class, StockMovementRow::class,
    ReminderRow::class], version = 5, exportSchema = true)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun dao(): HealthDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO `profiles` (`id`, `name`, `createdAt`) VALUES ('$LEGACY_PROFILE_ID', 'Основной', '1970-01-01T00:00:00Z')")
                listOf("pressures", "schedules", "occurrence_edits", "intakes").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `userId` TEXT NOT NULL DEFAULT '$LEGACY_PROFILE_ID'")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_userId` ON `$table` (`userId`)")
                }
                db.execSQL("ALTER TABLE `pressures` ADD COLUMN `inputSource` TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `schedules` ADD COLUMN `medicationId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `schedules` SET `medicationId` = `seriesId`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `user_medications` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `name` TEXT NOT NULL, `form` TEXT NOT NULL, `strength` TEXT, `unit` TEXT NOT NULL, `active` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_medications_userId` ON `user_medications` (`userId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `schedule_slots` (`revisionId` TEXT NOT NULL, `id` TEXT NOT NULL, `time` TEXT NOT NULL, `amount` TEXT NOT NULL, `unit` TEXT NOT NULL, PRIMARY KEY(`revisionId`, `id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_slots_revisionId` ON `schedule_slots` (`revisionId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `stock_movements` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `medicationId` TEXT NOT NULL, `type` TEXT NOT NULL, `amount` TEXT NOT NULL, `occurredAt` TEXT NOT NULL, `intakeKey` TEXT, `reversesId` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_medicationId` ON `stock_movements` (`medicationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_userId` ON `stock_movements` (`userId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `intakeKey` TEXT, `type` TEXT NOT NULL, `triggerAt` TEXT NOT NULL, `status` TEXT NOT NULL, `requestCode` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_userId` ON `reminders` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_intakeKey` ON `reminders` (`intakeKey`)")
                db.query("SELECT id, seriesId, userId, name, slotsJson FROM schedules ORDER BY effectiveFrom, id").use { rows ->
                    while (rows.moveToNext()) {
                        val revisionId = rows.getString(0)
                        val seriesId = rows.getString(1)
                        val userId = rows.getString(2)
                        val name = rows.getString(3)
                        db.execSQL("INSERT OR REPLACE INTO user_medications VALUES (?, ?, ?, ?, NULL, ?, 1, ?)",
                            arrayOf(seriesId, userId, name, "Не указана", "OTHER", "1970-01-01T00:00:00Z"))
                        val slots = JSONArray(rows.getString(4))
                        for (index in 0 until slots.length()) {
                            val slot = slots.getJSONObject(index)
                            db.execSQL("INSERT INTO schedule_slots VALUES (?, ?, ?, ?, ?)", arrayOf(
                                revisionId, slot.getString("id"), slot.getString("time"),
                                slot.getString("amount"), slot.getString("unit")))
                        }
                    }
                }
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `intakes` ADD COLUMN `strength` TEXT")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `amount` TEXT")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `unit` TEXT")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `medicationId` TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `elapsedAtMillis` INTEGER")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `bootCount` INTEGER")
            }
        }
        fun open(context: Context, name: String = "health.db"): HealthDatabase =
            Room.databaseBuilder(context.applicationContext, HealthDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
