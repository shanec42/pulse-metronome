package com.austinlinux.pulsemetronome.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseOpenerPlanTest {
    @Test fun `no file or an empty version is a fresh database`() {
        assertEquals(OpenPlan.FRESH, planOpen(null, 3))
        assertEquals(OpenPlan.FRESH, planOpen(0, 3))
    }
    @Test fun `an existing file with no readable version is unreadable`() {
        assertEquals(OpenPlan.UNREADABLE, planOpen(null, 3, fileExists = true))
        assertEquals(OpenPlan.FRESH, planOpen(null, 3, fileExists = false))
    }
    @Test fun `same, older and newer versions`() {
        assertEquals(OpenPlan.SAME, planOpen(3, 3))
        assertEquals(OpenPlan.UPGRADE, planOpen(2, 3))
        assertEquals(OpenPlan.DOWNGRADE, planOpen(4, 3))
    }
    @Test fun `prune keeps the two newest of a prefix and ignores others`() {
        val names = listOf(
            "pre-upgrade-20261001-120000-v3.db", "pre-upgrade-20261003-120000-v3.db",
            "pre-upgrade-20261002-120000-v3.db", "failed-20261001-120000-v4.db", "UPGRADE_FAILED"
        )
        assertEquals(listOf("pre-upgrade-20261001-120000-v3.db"), backupsToPrune(names, "pre-upgrade-"))
        assertEquals(emptyList<String>(), backupsToPrune(names, "failed-"))
    }
    @Test fun `prune never returns the protected name even when its clock is older`() {
        val names = listOf("failed-20261003-120000-v3.db", "failed-20261002-120000-v3.db", "failed-20250101-120000-v3.db")
        val pruned = backupsToPrune(names, "failed-", protect = "failed-20250101-120000-v3.db")
        assertEquals(listOf("failed-20261002-120000-v3.db"), pruned)
    }
}
