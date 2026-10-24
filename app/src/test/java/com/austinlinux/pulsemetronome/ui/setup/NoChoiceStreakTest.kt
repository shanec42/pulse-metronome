package com.austinlinux.pulsemetronome.ui.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoChoiceStreakTest {
    @Test fun `the second scan in a row that gave no choice fires`() {
        val s = NoChoiceStreak()
        assertFalse(s.record(noChoice = true, nowMs = 0))
        assertTrue(s.record(noChoice = true, nowMs = 8_000))
    }

    @Test fun `a picker in between resets the count`() {
        val s = NoChoiceStreak()
        s.record(noChoice = true, nowMs = 0)
        assertFalse(s.record(noChoice = false, nowMs = 5_000))
        assertFalse(s.record(noChoice = true, nowMs = 10_000))
        assertTrue(s.record(noChoice = true, nowMs = 18_000))
    }

    @Test fun `scans more than 60 seconds apart don't fire`() {
        val s = NoChoiceStreak()
        s.record(noChoice = true, nowMs = 0)
        assertFalse(s.record(noChoice = true, nowMs = 60_001))
        assertTrue(s.record(noChoice = true, nowMs = 70_000))   // 60.0 s and 70.0 s are within 60 s
    }

    @Test fun `after firing it needs two fresh results`() {
        val s = NoChoiceStreak()
        s.record(noChoice = true, nowMs = 0); assertTrue(s.record(noChoice = true, nowMs = 5_000))
        assertFalse(s.record(noChoice = true, nowMs = 10_000)); assertTrue(s.record(noChoice = true, nowMs = 15_000))
    }
}
