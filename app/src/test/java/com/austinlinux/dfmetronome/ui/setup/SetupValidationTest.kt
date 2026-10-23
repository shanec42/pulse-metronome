package com.austinlinux.dfmetronome.ui.setup

import com.austinlinux.dfmetronome.engine.DescentPattern
import com.austinlinux.dfmetronome.engine.StartMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupValidationTest {
    @Test
    fun `valid inputs pass`() {
        val result = validateSetup(targetBpm = 60, totalDurationMin = 30, rampDurationMin = 20)
        assertTrue(result.isValid)
    }

    @Test
    fun `target bpm below 30 fails`() {
        assertFalse(validateSetup(targetBpm = 29, totalDurationMin = 30, rampDurationMin = 20).isValid)
    }

    @Test
    fun `target bpm above 200 fails`() {
        assertFalse(validateSetup(targetBpm = 201, totalDurationMin = 30, rampDurationMin = 20).isValid)
    }

    @Test
    fun `ramp duration exceeding total duration fails`() {
        val result = validateSetup(targetBpm = 60, totalDurationMin = 20, rampDurationMin = 30)
        assertFalse(result.isValid)
    }

    @Test
    fun `ramp duration equal to total duration passes`() {
        val result = validateSetup(targetBpm = 60, totalDurationMin = 30, rampDurationMin = 30)
        assertTrue(result.isValid)
    }

    @Test
    fun `zero or negative durations fail`() {
        assertFalse(validateSetup(targetBpm = 60, totalDurationMin = 0, rampDurationMin = 0).isValid)
    }

    @Test
    fun `start now pacing disabled when fallback bpm is at or below target - rate would be zero`() {
        // Bug found in review: RampCalculator's rate = (starting_bpm - target_bpm) / ramp.
        // Under StartMode.NOW, starting_bpm comes from the static fallback (clamped >= target
        // in SessionEngine.resolveStartingBpmFallback, Task 9). If fallback <= target BEFORE
        // that clamp, the clamp forces starting_bpm = target_bpm, so rate = 0 for the entire
        // session - "Start Now" would silently do no pacing at all, just track live pulse.
        assertTrue(isStartNowPacingDisabled(staticFallbackStartingBpm = 70, targetBpm = 75))
        assertTrue(isStartNowPacingDisabled(staticFallbackStartingBpm = 70, targetBpm = 70))
    }

    @Test
    fun `start now pacing not disabled when fallback bpm exceeds target`() {
        assertFalse(isStartNowPacingDisabled(staticFallbackStartingBpm = 70, targetBpm = 60))
    }

    @Test
    fun `start mode resolves from wait-for-pulse and connection`() {
        assertEquals(StartMode.WHEN_PULSE_DETECTED, resolveStartMode(waitForPulse = true, meterConnected = true))
        assertEquals(StartMode.WHEN_PULSE_DETECTED, resolveStartMode(waitForPulse = true, meterConnected = false))
        assertEquals(StartMode.NOW, resolveStartMode(waitForPulse = false, meterConnected = true))
        assertEquals(StartMode.NO_PULSE, resolveStartMode(waitForPulse = false, meterConnected = false))
    }

    @Test
    fun `may-not-reach note uses the time-based rule only for no-pulse sessions`() {
        // 80 -> 60 in 10 minutes at max offset 1: shown for both patterns when no-pulse
        for (p in DescentPattern.values()) {
            assertTrue(showMayNotReachNote(false, false, 80, 60, 10, 10, 1, p))
            assertFalse(showMayNotReachNote(true, false, 80, 60, 10, 10, 1, p))   // waiting for pulse
            assertFalse(showMayNotReachNote(false, true, 80, 60, 10, 10, 1, p))   // meter connected
            assertFalse(showMayNotReachNote(false, false, 80, 60, 60, 60, 1, p))  // 60 minutes is enough
        }
    }

    @Test
    fun `may-not-reach note is hidden while setup fields are blank`() {
        assertFalse(showMayNotReachNote(false, false, 80, null, 10, 10, 1, DescentPattern.LINEAR))
        assertFalse(showMayNotReachNote(false, false, 80, 60, null, 10, 1, DescentPattern.LINEAR))
        assertFalse(showMayNotReachNote(false, false, 80, 60, 10, null, 1, DescentPattern.LINEAR))
    }

    @Test
    fun `setup display follows wait-for-pulse and connection`() {
        assertEquals(SetupDisplay(true, "Start without meter"), setupDisplay(waitForPulse = false, meterConnected = false))
        assertEquals(SetupDisplay(false, "Start"), setupDisplay(waitForPulse = false, meterConnected = true))
        assertEquals(SetupDisplay(false, "Start when pulse is steady"), setupDisplay(waitForPulse = true, meterConnected = false))
        assertEquals(SetupDisplay(false, "Start when pulse is steady"), setupDisplay(waitForPulse = true, meterConnected = true))
    }

    @Test
    fun `start without meter needs no bluetooth permission and a valid starting bpm, which may equal target`() {
        assertTrue(startEnabled(true, waitForPulse = false, meterConnected = false, bleGranted = false, scanInProgress = false, startingBpm = 70, targetBpm = 60))
        // A steady-BPM session is allowed (user ruling 2026-10-10): warning only, Start stays enabled.
        assertTrue(startEnabled(true, false, false, false, false, startingBpm = 60, targetBpm = 60))
        assertTrue(startEnabled(true, false, false, false, false, startingBpm = 55, targetBpm = 60))
        assertFalse(startEnabled(true, false, false, false, false, startingBpm = null, targetBpm = 60))
        assertFalse(startEnabled(true, false, false, false, false, startingBpm = 250, targetBpm = 60))
        assertFalse(startEnabled(true, false, false, false, scanInProgress = true, startingBpm = 70, targetBpm = 60))
    }

    @Test
    fun `meter modes need bluetooth permission and ignore the starting bpm`() {
        assertTrue(startEnabled(true, waitForPulse = false, meterConnected = true, bleGranted = true, scanInProgress = false, startingBpm = 50, targetBpm = 60))
        assertTrue(startEnabled(true, waitForPulse = true, meterConnected = false, bleGranted = true, scanInProgress = true, startingBpm = null, targetBpm = 60))
        assertFalse(startEnabled(true, waitForPulse = true, meterConnected = false, bleGranted = false, scanInProgress = false, startingBpm = 70, targetBpm = 60))
    }

    @Test
    fun `an invalid form disables start in every mode`() {
        assertFalse(startEnabled(false, false, false, true, false, 70, 60))
        assertFalse(startEnabled(false, true, true, true, false, 70, 60))
    }

    @Test
    fun `monitor always waits for the pulse`() {
        for (wait in listOf(true, false)) for (connected in listOf(true, false))
            assertEquals(StartMode.WHEN_PULSE_DETECTED, resolveStartMode(wait, connected, monitor = true))
    }

    @Test
    fun `monitor shows only total duration and its own button`() {
        assertEquals(
            SetupDisplay(showStartingBpm = false, startLabel = "Start monitoring", showTargetAndRamp = false, showWaitForPulse = false),
            setupDisplay(waitForPulse = false, meterConnected = false, monitor = true)
        )
    }

    @Test
    fun `monitor start needs bluetooth and a valid total only`() {
        assertTrue(startEnabled(formValid = false, waitForPulse = false, meterConnected = false, bleGranted = true,
            scanInProgress = false, startingBpm = null, targetBpm = null, monitor = true, totalDurationMin = 10))
        assertFalse(startEnabled(false, false, false, bleGranted = false, scanInProgress = false, startingBpm = null,
            targetBpm = null, monitor = true, totalDurationMin = 10))
        assertFalse(startEnabled(true, false, false, true, false, 70, 60, monitor = true, totalDurationMin = null))
        assertFalse(startEnabled(true, false, false, true, false, 70, 60, monitor = true, totalDurationMin = 0))
    }

    @Test
    fun `ramp buttons stop at the total duration`() {
        assertEquals(25, rampStepperMax(25))
        assertEquals(1, rampStepperMax(1))
        assertEquals(600, rampStepperMax(null))   // total blank: fall back to the field's own limit
        assertEquals(600, rampStepperMax(900))    // never above the field's own limit
    }

    @Test
    fun `ramp plus stops at the total first, then raises both`() {
        assertEquals(RampBump(20, 30), rampBump(ramp = 15, total = 30, delta = 5))   // below the total: ramp only
        assertEquals(RampBump(28, 28), rampBump(ramp = 25, total = 28, delta = 5))   // lands exactly on the total
        assertEquals(RampBump(33, 33), rampBump(ramp = 28, total = 28, delta = 5))   // at the total: both go up
        assertEquals(RampBump(600, 600), rampBump(ramp = 600, total = 600, delta = 5)) // never past the field limit
    }

    @Test
    fun `ramp plus above a typed-in smaller total brings the total up with it`() {
        assertEquals(RampBump(45, 45), rampBump(ramp = 40, total = 30, delta = 5))
    }

    @Test
    fun `ramp minus and a blank total leave the total alone`() {
        assertEquals(RampBump(25, 30), rampBump(ramp = 30, total = 30, delta = -5))
        assertEquals(RampBump(1, 30), rampBump(ramp = 3, total = 30, delta = -5))
        assertEquals(RampBump(20, null), rampBump(ramp = 15, total = null, delta = 5))
        assertEquals(RampBump(1, 30), rampBump(ramp = null, total = 30, delta = 0))
    }
}
