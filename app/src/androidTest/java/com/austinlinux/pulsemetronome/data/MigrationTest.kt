package com.austinlinux.pulsemetronome.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun everySupportedVersionMigratesToCurrent() {
        for (v in DB_BASELINE_VERSION..AppDatabase.VERSION) {
            val name = "migration-walk-$v.db"
            helper.createDatabase(name, v).close()
            helper.runMigrationsAndValidate(name, AppDatabase.VERSION, true, *ALL_MIGRATIONS).close()
        }
    }

    @Test
    fun migrate3To4KeepsRowsWithNoFocus() {
        val name = "migration-3-4.db"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO sessions (startTimestamp, targetBpm, rampDurationMin, totalDurationMin, maxOffset, startingBpm, startingBpmSource, targetReached) VALUES (1000, 60, 30, 60, 2, 90, 'LIVE_PULSE', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 4, true, *ALL_MIGRATIONS)
        db.query("SELECT startTimestamp, focusPercent IS NULL FROM sessions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1000L, c.getLong(0))
            assertEquals(1, c.getInt(1))
            assertFalse(c.moveToNext())
        }
        db.close()
    }
}
