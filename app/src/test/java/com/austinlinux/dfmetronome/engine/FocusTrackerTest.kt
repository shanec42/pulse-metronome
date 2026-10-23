package com.austinlinux.dfmetronome.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTrackerTest {
    // 80 -> 60 over a 30-minute ramp: sync window 3 min, then a linear 20/27 BPM per minute.
    private val plan = NoPulseParams(
        startBpm = 80.0, targetBpm = 60.0, totalDurationMin = 30.0, rampDurationMin = 30.0,
        maxOffsetPerMin = 2.0, pattern = DescentPattern.LINEAR
    )

    @Test fun `nothing counted is null`() { assertNull(FocusTracker(plan).percent) }

    @Test fun `the plan holds through the sync window, then follows the descent schedule to target`() {
        val f = FocusTracker(plan)
        assertEquals(80.0, f.plannedBpmAt(3.0), 1e-9)
        assertEquals(70.0, f.plannedBpmAt(16.5), 0.01)
        assertEquals(60.0, f.plannedBpmAt(30.0), 0.01)
        assertEquals(60.0, f.plannedBpmAt(45.0), 1e-9)   // stays at target after the ramp
    }

    @Test fun `within the allowance above the plan counts, inclusive, and further above does not`() {
        val f = FocusTracker(plan)
        f.record(82.0, 2.0)   // plan 80: +2, in
        f.record(82.5, 2.0)   // +2.5, out
        assertEquals(50, f.percent)
    }

    @Test fun `being ahead of the plan is in focus`() {
        val f = FocusTracker(plan)
        f.record(62.0, 10.0); f.record(60.0, 20.0)   // well below the plan
        assertEquals(100, f.percent)
    }

    @Test fun `a pulse that stays at the start falls out of focus as the plan comes down`() {
        val f = FocusTracker(plan)
        var t = 3.5
        while (t <= 30.0) { f.record(80.0, t); t += 0.5 }
        // In focus only until the plan is 2 BPM below 80: about 2.7 of the 26.5 counted minutes.
        assertTrue("was ${f.percent}", f.percent!! in 8..13)
    }

    @Test fun `a session that starts at or below target is scored against the target`() {
        val f = FocusTracker(plan.copy(startBpm = 58.0))
        assertEquals(60.0, f.plannedBpmAt(20.0), 1e-9)   // no gap to descend: the plan sits at the target
        f.record(61.0, 20.0); f.record(62.5, 20.0)
        assertEquals(50, f.percent)
    }

    @Test fun `rounds to the nearest percent`() {
        val f = FocusTracker(plan); f.record(80.0, 1.0); f.record(80.0, 1.0); f.record(90.0, 1.0)
        assertEquals(67, f.percent)
    }
}
