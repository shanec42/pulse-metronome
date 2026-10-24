package com.austinlinux.pulsemetronome.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationGuardTest {
    @Test
    fun `every version step from the baseline to the current version has exactly one migration`() {
        for (v in DB_BASELINE_VERSION until AppDatabase.VERSION) {
            val steps = ALL_MIGRATIONS.filter { it.startVersion == v && it.endVersion == v + 1 }
            assertEquals("Database version $v -> ${v + 1} needs exactly one Migration in ALL_MIGRATIONS", 1, steps.size)
        }
    }

    @Test
    fun `baseline is not above the current version`() {
        assertEquals(true, DB_BASELINE_VERSION <= AppDatabase.VERSION)
    }

    @Test
    fun `baseline is frozen`() {
        assertEquals("DB_BASELINE_VERSION is frozen at 3; add a Migration instead of raising it", 3, DB_BASELINE_VERSION)
    }
}
