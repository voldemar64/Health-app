package io.github.voldemar64.healthapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.voldemar64.healthapp.data.HealthDatabase
import io.github.voldemar64.healthapp.data.RoomHealthRepository
import io.github.voldemar64.healthapp.domain.LEGACY_PROFILE_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ProfileMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "profile-migration-test.db"

    @Test fun directV3ToLatestKeepsEventsAndUnknownStrength() = runBlocking {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
            val schema = JSONObject(testAssets.open("migrations/v3.json").bufferedReader().use { it.readText() })
                .getJSONObject("database")
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (item in 0 until indices.length())
                    old.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO profiles VALUES ('p', 'Профиль', '2026-11-01T00:00:00Z')")
            old.execSQL("INSERT INTO user_medications VALUES ('m', 'p', 'Тест', 'Не указана', NULL, 'OTHER', 1, '2026-11-01T00:00:00Z')")
            old.execSQL("INSERT INTO intakes VALUES ('s/2026-11-09/slot', 's', 'r', 'slot', '2026-11-09', '09:00', 'Тест', '0.5', 'G', 'TAKEN', '2026-11-09T09:00:00Z', 'UTC', '2026-11-09T09:01:00Z', 'p')")
            old.execSQL("INSERT INTO reminders VALUES ('rem', 'p', NULL, 'GENERAL', '2026-11-17T09:00:00Z', 'ACTIVE', 1)")
            old.version = 3
        }
        val database = HealthDatabase.open(context, name)
        try {
            val repository = RoomHealthRepository(database)
            repository.selectProfile("p")
            val state = repository.data.first()
            assertEquals("0.5", state.intakes.single().occurrence.dose.text)
            assertNull(state.intakes.single().strength)
            assertNull(state.medications.single().initialStock)
            assertEquals("", state.reminders.single().title)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    private fun createV1Tables(old: SQLiteDatabase) {
        old.execSQL("CREATE TABLE `pressures` (`id` TEXT NOT NULL, `systolic` INTEGER NOT NULL, `diastolic` INTEGER NOT NULL, `pulse` INTEGER, `measuredAt` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`id`))")
        old.execSQL("CREATE TABLE `schedules` (`id` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `effectiveFrom` TEXT NOT NULL, `effectiveUntil` TEXT, `name` TEXT NOT NULL, `repeat` TEXT NOT NULL, `weekdayMask` INTEGER NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT, `slotsJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
        old.execSQL("CREATE TABLE `occurrence_edits` (`key` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `revisionId` TEXT NOT NULL, `slotId` TEXT NOT NULL, `date` TEXT NOT NULL, `time` TEXT NOT NULL, `name` TEXT NOT NULL, `amount` TEXT NOT NULL, `unit` TEXT NOT NULL, PRIMARY KEY(`key`))")
        old.execSQL("CREATE TABLE `intakes` (`key` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `revisionId` TEXT NOT NULL, `slotId` TEXT NOT NULL, `date` TEXT NOT NULL, `time` TEXT NOT NULL, `name` TEXT NOT NULL, `amount` TEXT NOT NULL, `unit` TEXT NOT NULL, `status` TEXT NOT NULL, `plannedAt` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `takenAt` TEXT, PRIMARY KEY(`key`))")
    }

    @Test fun emptyV1MigratesToLatestWithMainProfile() = runBlocking {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            createV1Tables(old)
            old.version = 1
        }
        val database = HealthDatabase.open(context, name)
        try {
            val repository = RoomHealthRepository(database)
            assertEquals(LEGACY_PROFILE_ID, repository.profiles.first().single().id)
            val data = repository.data.first()
            assertTrue(data.pressures.isEmpty())
            assertTrue(data.schedules.isEmpty())
            assertTrue(data.medications.isEmpty())
            assertTrue(data.stockMovements.isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun v1RowsKeepValuesAndMoveToMainProfile() = runBlocking {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            createV1Tables(old)
            old.execSQL("INSERT INTO pressures VALUES ('p1', 120, 80, NULL, '2026-11-09T09:00:00Z', 'UTC', 'Старая заметка')")
            old.execSQL("INSERT INTO schedules VALUES ('r1', 's1', '2026-11-09T00:00', NULL, 'Лекарство', 'DAILY', 0, '2026-11-09', NULL, '[{\"id\":\"slot\",\"time\":\"09:00\",\"amount\":\"0.5\",\"unit\":\"TABLET\"}]')")
            old.execSQL("INSERT INTO occurrence_edits VALUES ('s1/2026-11-10/slot', 's1', 'r1', 'slot', '2026-11-10', '10:00', 'Лекарство', '0.5', 'TABLET')")
            old.execSQL("INSERT INTO intakes VALUES ('s1/2026-11-09/slot', 's1', 'r1', 'slot', '2026-11-09', '09:00', 'Лекарство', '0.5', 'TABLET', 'TAKEN', '2026-11-09T09:00:00Z', 'UTC', '2026-11-09T09:01:00Z')")
            old.version = 1
        }
        val database = HealthDatabase.open(context, name)
        try {
            val repository = RoomHealthRepository(database)
            val data = repository.data.first()
            assertEquals(LEGACY_PROFILE_ID, repository.profiles.first().single().id)
            assertEquals("Старая заметка", data.pressures.single().note)
            assertNull(data.pressures.single().pulse)
            assertEquals("0.5", data.schedules.single().draft.slots.single().dose.text)
            assertEquals("s1", data.medications.single().id)
            assertNull(data.medications.single().initialStock)
            assertEquals(1, data.exceptions.size)
            assertEquals(1, data.intakes.size)
            assertTrue(data.intakes.single().takenAt != null)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun v2SameNamedSeriesStaySeparateAcrossProfilesAndRevisions() = runBlocking {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            createV1Tables(old)
            old.execSQL("CREATE TABLE `profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))")
            old.execSQL("INSERT INTO profiles VALUES ('p1', 'Игорь', '2026-11-01T00:00:00Z')")
            old.execSQL("INSERT INTO profiles VALUES ('p2', 'Мария', '2026-11-02T00:00:00Z')")
            listOf("pressures", "schedules", "occurrence_edits", "intakes").forEach { table ->
                old.execSQL("ALTER TABLE `$table` ADD COLUMN `userId` TEXT NOT NULL DEFAULT 'legacy-profile'")
                old.execSQL("CREATE INDEX `index_${table}_userId` ON `$table` (`userId`)")
            }
            old.execSQL("ALTER TABLE pressures ADD COLUMN inputSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
            val morning = "[{\"id\":\"slot\",\"time\":\"09:00\",\"amount\":\"0.5\",\"unit\":\"TABLET\"}]"
            val evening = "[{\"id\":\"slot\",\"time\":\"18:00\",\"amount\":\"1\",\"unit\":\"TABLET\"}]"
            val grams = "[{\"id\":\"slot\",\"time\":\"09:00\",\"amount\":\"0.000001\",\"unit\":\"G\"}]"
            old.execSQL("INSERT INTO schedules VALUES ('r1', 's1', '2026-11-09T00:00', '2026-11-10T00:00', 'Лекарство', 'DAILY', 0, '2026-11-09', NULL, ?, 'p1')", arrayOf(morning))
            old.execSQL("INSERT INTO schedules VALUES ('r2', 's1', '2026-11-10T00:00', NULL, 'Лекарство новое', 'DAILY', 0, '2026-11-09', NULL, ?, 'p1')", arrayOf(evening))
            old.execSQL("INSERT INTO schedules VALUES ('r3', 's2', '2026-11-09T00:00', NULL, 'Лекарство', 'DAILY', 0, '2026-11-09', NULL, ?, 'p2')", arrayOf(grams))
            old.execSQL("INSERT INTO intakes (`key`, `seriesId`, `revisionId`, `slotId`, `date`, `time`, `name`, `amount`, `unit`, `status`, `plannedAt`, `zoneId`, `takenAt`, `userId`) VALUES ('s1/2026-11-09/slot', 's1', 'r1', 'slot', '2026-11-09', '09:00', 'Лекарство', '0.5', 'TABLET', 'TAKEN', '2026-11-09T09:00:00Z', 'UTC', '2026-11-09T09:01:00Z', 'p1')")
            old.version = 2
        }
        val database = HealthDatabase.open(context, name)
        try {
            val repository = RoomHealthRepository(database)
            repository.selectProfile("p1")
            val first = repository.data.first()
            assertEquals(listOf("r1", "r2"), first.schedules.map { it.id }.sorted())
            assertEquals(listOf("0.5", "1"), first.schedules.sortedBy { it.id }.map { it.draft.slots.single().dose.text })
            assertEquals("Лекарство новое", first.medications.single().name)
            assertNull(first.medications.single().initialStock)
            assertEquals("0.5", first.intakes.single().occurrence.dose.text)
            repository.selectProfile("p2")
            val second = repository.data.first()
            assertEquals("s2", second.medications.single().id)
            assertEquals("Лекарство", second.medications.single().name)
            assertEquals("0.000001", second.schedules.single().draft.slots.single().dose.text)
            assertEquals(io.github.voldemar64.healthapp.domain.DoseUnit.G,
                second.schedules.single().draft.slots.single().dose.unit)
            assertTrue(second.intakes.isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
