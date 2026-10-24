package com.austinlinux.pulsemetronome.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoPulseDescentTest {

    private fun params(
        start: Double = 80.0, target: Double = 60.0, total: Double = 30.0, ramp: Double = 30.0,
        maxOffset: Double = 2.0, pattern: DescentPattern = DescentPattern.BELL_CURVE
    ) = NoPulseParams(start, target, total, ramp, maxOffset, pattern)

    /** Integrates the schedule in 250 ms steps from t=0 to [untilMin], as the engine's ticker would. */
    private fun run(p: NoPulseParams, untilMin: Double): Double {
        val dt = 0.25 / 60.0
        var bpm = p.startBpm
        var t = 0.0
        while (t < untilMin - 1e-9) {
            t += dt
            bpm = NoPulseDescent.step(bpm, p, t, dt)
        }
        return bpm
    }

    @Test
    fun `holds during the sync window`() {
        val p = params()  // sync = 3 min
        assertEquals(0.0, NoPulseDescent.ratePerMin(p, 0.0), 0.0)
        assertEquals(0.0, NoPulseDescent.ratePerMin(p, 3.0), 0.0)
        assertEquals(80.0, run(p, 2.99), 1e-9)  // run() accumulates t, so 3.0 would step just past sync
    }

    @Test
    fun `linear drops the same amount every minute and reaches target at ramp end`() {
        val p = params(pattern = DescentPattern.LINEAR)  // gap 20, D = 27
        assertEquals(20.0 / 27.0, NoPulseDescent.ratePerMin(p, 4.0), 1e-9)
        assertEquals(20.0 / 27.0, NoPulseDescent.ratePerMin(p, 25.0), 1e-9)
        assertEquals(80.0 - 20.0 / 27.0 * 13.5, run(p, 16.5), 0.02)
        assertEquals(60.0, run(p, 30.0), 0.02)
    }

    @Test
    fun `bell curve peaks at the midpoint at one and a half times the average`() {
        val p = params()  // gap 20, sync 3, D 27, midpoint 16.5
        assertEquals(1.5 * 20.0 / 27.0, NoPulseDescent.ratePerMin(p, 16.5), 1e-9)
        assertTrue(NoPulseDescent.ratePerMin(p, 8.0) < NoPulseDescent.ratePerMin(p, 16.5))
        assertTrue(NoPulseDescent.ratePerMin(p, 25.0) < NoPulseDescent.ratePerMin(p, 16.5))
    }

    @Test
    fun `bell curve never drops slower than the minimum while descending`() {
        val p = params()
        assertEquals(NO_PULSE_MIN_RATE, NoPulseDescent.ratePerMin(p, 3.01), 1e-9)
        assertEquals(NO_PULSE_MIN_RATE, NoPulseDescent.ratePerMin(p, 29.99), 1e-9)
    }

    @Test
    fun `bell curve uncapped reaches target by ramp end`() {
        assertEquals(60.0, run(params(), 30.0), 0.0)
    }

    @Test
    fun `both patterns are capped at max offset per minute`() {
        val bell = params(total = 10.0, ramp = 10.0, maxOffset = 1.0)  // peak 1.5*20/9 = 3.33
        val linear = bell.copy(pattern = DescentPattern.LINEAR)          // 20/9 = 2.22
        assertEquals(1.0, NoPulseDescent.ratePerMin(bell, 5.5), 1e-9)
        assertEquals(1.0, NoPulseDescent.ratePerMin(linear, 5.5), 1e-9)
        assertTrue(run(bell, 10.0) > 60.0)
        assertTrue(run(linear, 10.0) > 60.0)
    }

    @Test
    fun `after the ramp a capped descent carries on - linear at max offset, bell at the minimum`() {
        val bell = params(total = 20.0, ramp = 10.0, maxOffset = 1.0)  // sync = 2, D = 8
        val linear = bell.copy(pattern = DescentPattern.LINEAR)
        assertEquals(NO_PULSE_MIN_RATE, NoPulseDescent.ratePerMin(bell, 12.0), 1e-9)
        assertEquals(1.0, NoPulseDescent.ratePerMin(linear, 12.0), 1e-9)
        assertTrue(run(linear, 12.0) < run(linear, 10.0))
    }

    @Test
    fun `never goes below target`() {
        val p = params(total = 60.0, ramp = 10.0, maxOffset = 5.0)
        assertEquals(60.0, run(p, 60.0), 0.0)
        assertEquals(60.0, NoPulseDescent.step(60.2, p, 9.0, 1.0), 0.0)
    }

    @Test
    fun `fallback at or below target never moves`() {
        val p = params(start = 60.0)
        assertEquals(0.0, NoPulseDescent.ratePerMin(p, 15.0), 0.0)
        assertEquals(60.0, run(p, 30.0), 0.0)
    }

    @Test
    fun `ramp no longer than the sync window descends at max offset after the sync window`() {
        val p = params(total = 10.0, ramp = 1.0, maxOffset = 2.0)  // sync = 1, D = 0
        assertEquals(0.0, NoPulseDescent.ratePerMin(p, 0.5), 0.0)
        assertEquals(2.0, NoPulseDescent.ratePerMin(p, 1.5), 0.0)
    }

    @Test
    fun `a long gap between ticks drops in proportion and stays clamped`() {
        val p = params(pattern = DescentPattern.LINEAR)
        val r = NoPulseDescent.ratePerMin(p, 10.0)
        assertEquals(75.0 - r * 0.5, NoPulseDescent.step(75.0, p, 10.0, 0.5), 1e-9)
        assertEquals(60.0, NoPulseDescent.step(60.1, p, 10.0, 0.5), 0.0)
    }

    @Test
    fun `worked example - 80 to 60 over 30 minutes with max offset 2`() {
        val linear = params(pattern = DescentPattern.LINEAR)
        assertEquals(0.7407, NoPulseDescent.ratePerMin(linear, 10.0), 1e-4)
        assertEquals(1.1111, NoPulseDescent.ratePerMin(params(), 16.5), 1e-4)
        assertFalse(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 30.0, 30.0, 2.0, DescentPattern.LINEAR))
        assertFalse(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 30.0, 30.0, 2.0, DescentPattern.BELL_CURVE))
    }

    @Test
    fun `warning - the user's two examples`() {
        for (pattern in DescentPattern.values()) {
            assertTrue(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 10.0, 10.0, 1.0, pattern))
            assertFalse(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 60.0, 60.0, 1.0, pattern))
        }
    }

    @Test
    fun `warning - either side of the limit for each pattern`() {
        // total = ramp = 30: sync 3, D 27, gap 20. Linear limit 0.741; bell limit 1.111.
        assertTrue(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 30.0, 30.0, 0.7, DescentPattern.LINEAR))
        assertFalse(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 30.0, 30.0, 0.75, DescentPattern.LINEAR))
        assertTrue(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 30.0, 30.0, 1.1, DescentPattern.BELL_CURVE))
        assertFalse(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 30.0, 30.0, 1.12, DescentPattern.BELL_CURVE))
    }

    @Test
    fun `descent pattern parses stored names and defaults to bell curve`() {
        assertEquals(DescentPattern.BELL_CURVE, parseDescentPattern(null))
        assertEquals(DescentPattern.BELL_CURVE, parseDescentPattern("WOBBLY"))
        for (p in DescentPattern.values()) assertEquals(p, parseDescentPattern(p.name))
    }

    @Test
    fun `warning - edge cases`() {
        assertFalse(NoPulseDescent.mayNotReachTarget(60.0, 60.0, 30.0, 30.0, 1.0, DescentPattern.LINEAR))
        assertTrue(NoPulseDescent.mayNotReachTarget(80.0, 60.0, 10.0, 1.0, 20.0, DescentPattern.BELL_CURVE))
    }
}
