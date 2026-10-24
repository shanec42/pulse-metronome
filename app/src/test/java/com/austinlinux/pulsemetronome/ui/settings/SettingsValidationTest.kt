package com.austinlinux.pulsemetronome.ui.settings

import com.austinlinux.pulsemetronome.engine.DescentPattern
import com.austinlinux.pulsemetronome.engine.MonitorSignalLoss
import com.austinlinux.pulsemetronome.engine.PacedSignalLoss
import com.austinlinux.pulsemetronome.engine.parsePacedSignalLoss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun `max offset below 1 is invalid`() {
        assertFalse(validateSettings(maxOffset = 0, staticFallbackStartingBpm = 70).isValid)
    }

    @Test
    fun `max offset of 1 or more is valid`() {
        assertTrue(validateSettings(maxOffset = 1, staticFallbackStartingBpm = 70).isValid)
    }

    @Test
    fun `max offset null is invalid - no longer optional`() {
        assertFalse(validateSettings(maxOffset = null, staticFallbackStartingBpm = 70).isValid)
    }

    @Test
    fun `static fallback bpm outside 30 to 200 is invalid`() {
        assertFalse(validateSettings(maxOffset = 2, staticFallbackStartingBpm = 29).isValid)
        assertFalse(validateSettings(maxOffset = 2, staticFallbackStartingBpm = 201).isValid)
    }

    @Test
    fun `static fallback bpm at the boundaries is valid`() {
        assertTrue(validateSettings(maxOffset = 2, staticFallbackStartingBpm = 30).isValid)
        assertTrue(validateSettings(maxOffset = 2, staticFallbackStartingBpm = 200).isValid)
    }

    @Test
    fun `settings ui state defaults to bell curve`() {
        assertEquals(DescentPattern.BELL_CURVE, SettingsUiState().descentPattern)
    }

    @Test
    fun `monitor signal loss defaults to hold`() {
        assertEquals(MonitorSignalLoss.HOLD, SettingsUiState().monitorSignalLoss)
    }

    @Test
    fun `paced signal loss parses with a hold default`() {
        assertEquals(PacedSignalLoss.HOLD, parsePacedSignalLoss(null))
        assertEquals(PacedSignalLoss.HOLD, parsePacedSignalLoss("WOBBLY"))
        for (b in PacedSignalLoss.values()) assertEquals(b, parsePacedSignalLoss(b.name))
        assertEquals(PacedSignalLoss.HOLD, SettingsUiState().pacedSignalLoss)
    }
}
