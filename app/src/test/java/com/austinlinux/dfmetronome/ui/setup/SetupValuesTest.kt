package com.austinlinux.dfmetronome.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupValuesTest {
    @Test fun `nothing saved leaves the defaults`() {
        assertEquals(SetupUiState(), SetupUiState().withRestored(null))
    }

    @Test fun `saved values replace the defaults`() {
        val saved = SavedSetupValues(targetBpm = 62, totalDurationMin = 20, rampDurationMin = 15, rampEditedByUser = true, waitForPulse = true)
        val state = SetupUiState().withRestored(saved)
        assertEquals(62, state.targetBpm)
        assertEquals(20, state.totalDurationMin)
        assertEquals(15, state.rampDurationMin)
        assertTrue(state.rampEditedByUser)
        assertTrue(state.waitForPulse)
    }

    @Test fun `restoring keeps non-form state such as an in-flight scan`() {
        val state = SetupUiState(scanning = true).withRestored(SavedSetupValues(60, 30, 30, false, false))
        assertTrue(state.scanning)
    }

    @Test fun `a complete form round-trips through the saved values`() {
        val state = SetupUiState(targetBpm = 58, totalDurationMin = 45, rampDurationMin = 40, rampEditedByUser = true, waitForPulse = true)
        assertEquals(state, SetupUiState().withRestored(state.toSavedValues()))
    }

    @Test fun `a half-typed (empty) field is not saved`() {
        assertNull(SetupUiState(targetBpm = null).toSavedValues())
        assertNull(SetupUiState(totalDurationMin = null).toSavedValues())
        assertNull(SetupUiState(rampDurationMin = null).toSavedValues())
    }

    @Test fun `ramp-follows-total is preserved when not edited`() {
        val saved = SetupUiState(totalDurationMin = 20, rampDurationMin = 20).toSavedValues()!!
        assertFalse(saved.rampEditedByUser)
    }

    @Test fun `monitor survives the saved values round trip`() {
        val state = SetupUiState(monitor = true)
        assertTrue(SetupUiState().withRestored(state.toSavedValues()).monitor)
    }
}
