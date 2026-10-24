package com.austinlinux.pulsemetronome.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
