package com.austinlinux.dfmetronome.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RampCalculatorTest {

    // Worked example throughout, verified by hand and by two Opus design reviews:
    // starting=80, target=60, total=ramp=30min -> syncWindowMin = max(0.1*30, 1.0) = 3.0.
    private fun pulseAt(t: Double): Double {
        val sync = 3.0
        if (t <= sync) return 80.0
        val remaining = 30.0 - sync
        val frac = minOf((t - sync) / remaining, 1.0)
        return 80.0 - frac * 20.0
    }

    @Test
    fun `syncWindowMin is 10 percent of total duration when that exceeds the 1 minute floor`() {
        assertEquals(3.0, syncWindowMin(30.0), 0.0001)
        assertEquals(6.0, syncWindowMin(60.0), 0.0001)
    }

    @Test
    fun `syncWindowMin floors at 1 minute for short sessions`() {
        assertEquals(1.0, syncWindowMin(5.0), 0.0001)
        assertEquals(1.0, syncWindowMin(10.0), 0.0001)
    }

    @Test
    fun `during the sync window offset is exactly zero and metronome tracks pulse exactly`() {
        val out = RampCalculator.calculate(RampInput(
            currentPulse = 80.0, startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 2.0, maxOffset = 2.0
        ))
        assertEquals(0.0, out.offset, 0.0001)
        assertEquals(80.0, out.targetMetronomeBpm, 0.0001)
    }

    @Test
    fun `at exactly the sync window boundary offset is still zero`() {
        val out = RampCalculator.calculate(RampInput(
            currentPulse = 80.0, startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 3.0, maxOffset = 2.0
        ))
        assertEquals(0.0, out.offset, 0.0001)
    }

    @Test
    fun `starting_gap less than or equal zero locks offset at zero for the whole session`() {
        val atTarget = RampCalculator.calculate(RampInput(
            currentPulse = 60.0, startingBpm = 60.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 15.0, maxOffset = 2.0
        ))
        assertEquals(0.0, atTarget.offset, 0.0001)
        assertEquals(60.0, atTarget.targetMetronomeBpm, 0.0001)

        val roseAboveTarget = RampCalculator.calculate(RampInput(
            currentPulse = 75.0, startingBpm = 60.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 20.0, maxOffset = 2.0
        ))
        assertEquals(0.0, roseAboveTarget.offset, 0.0001)
        assertEquals(75.0, roseAboveTarget.targetMetronomeBpm, 0.0001)
    }

    @Test
    fun `ramp duration less than or equal to sync window - growth is max_offset directly`() {
        val out = RampCalculator.calculate(RampInput(
            currentPulse = 70.0, startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 2.0,
            syncWindowMin = 3.0, elapsedMin = 5.0, maxOffset = 2.0
        ))
        // growth = max_offset = 2.0 directly (denom = 2.0 - 3.0 = -1.0 <= 0)
        // taper = (70-60)/(80-60) = 0.5 -> raw = 1.0, floored at max(1.0, 0.25) = 1.0
        assertEquals(1.0, out.offset, 0.0001)
        assertEquals(69.0, out.targetMetronomeBpm, 0.0001)
    }

    @Test
    fun `offset never below the 0_25 floor once past the sync window with a positive starting gap`() {
        val out = RampCalculator.calculate(RampInput(
            currentPulse = pulseAt(3.5), startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 3.5, maxOffset = 2.0
        ))
        assertEquals(0.25, out.offset, 0.0001)
    }

    @Test
    fun `offset rises above the floor through the middle of the ramp then returns to the floor near the end`() {
        val midRamp = RampCalculator.calculate(RampInput(
            currentPulse = pulseAt(16.5), startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 16.5, maxOffset = 2.0
        ))
        assertEquals(0.5, midRamp.offset, 0.0001)

        val nearEnd = RampCalculator.calculate(RampInput(
            currentPulse = pulseAt(29.0), startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 29.0, maxOffset = 2.0
        ))
        assertEquals(0.25, nearEnd.offset, 0.0001)
    }

    @Test
    fun `metronome clamps to exactly target_bpm once pulse minus offset would go below it`() {
        val out = RampCalculator.calculate(RampInput(
            currentPulse = 60.0, startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 30.0, maxOffset = 2.0
        ))
        assertEquals(0.25, out.offset, 0.0001)
        assertEquals(60.0, out.targetMetronomeBpm, 0.0001)
    }

    @Test
    fun `growth is capped at max_offset and does not keep growing past the ramp window`() {
        val atRampEnd = RampCalculator.calculate(RampInput(
            currentPulse = 80.0, startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 30.0, maxOffset = 2.0
        ))
        val pastRampEnd = RampCalculator.calculate(RampInput(
            currentPulse = 80.0, startingBpm = 80.0, targetBpm = 60.0, rampDurationMin = 30.0,
            syncWindowMin = 3.0, elapsedMin = 45.0, maxOffset = 2.0
        ))
        assertEquals(atRampEnd.offset, pastRampEnd.offset, 0.0001)
        assertEquals(2.0, atRampEnd.offset, 0.0001)
    }
}
