package com.austinlinux.pulsemetronome.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartPulseTest {
    @Test fun `a fresh reading is used`() {
        assertEquals(78, usableInitialPulse(78, atMs = 10_000L, nowMs = 12_000L))
    }
    @Test fun `a reading at or past the staleness timeout is not used`() {
        assertNull(usableInitialPulse(78, atMs = 10_000L, nowMs = 10_000L + STALENESS_TIMEOUT_MS))
        assertNull(usableInitialPulse(78, atMs = 10_000L, nowMs = 60_000L))
    }
    @Test fun `no reading means no initial pulse`() {
        assertNull(usableInitialPulse(null, atMs = null, nowMs = 5_000L))
    }
}
