package com.austinlinux.dfmetronome.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningPulseFilterTest {
    @Test
    fun `reading within 1 bpm of the seeded baseline is accepted and becomes the new baseline`() {
        val filter = RunningPulseFilter()
        filter.seed(90)
        assertTrue(filter.accept(91))
    }

    @Test
    fun `reading more than 1 bpm from the baseline is rejected`() {
        val filter = RunningPulseFilter()
        filter.seed(90)
        assertFalse(filter.accept(255))
    }

    @Test
    fun `after a rejection the baseline does NOT reset - the next reading is still compared to the last KNOWN GOOD value`() {
        // This is the exact bug PulseReadingValidator would have if reused here: a rejected
        // outlier must NOT make the next reading auto-accept regardless of value.
        val filter = RunningPulseFilter()
        filter.seed(90)
        assertFalse(filter.accept(255))  // rejected
        assertFalse(filter.accept(93))   // still compared to 90 (the last KNOWN GOOD baseline), |93-90|=3 > 1 - rejected
        assertFalse(filter.accept(0))    // also rejected, compared to 90
    }

    @Test
    fun `a run of 3 mutually-consistent off-baseline readings re-baselines - handles a genuine step change`() {
        // A real pulse jump (not noise) must eventually be accepted, or the metronome would
        // freeze forever after any sufficiently large genuine change.
        val filter = RunningPulseFilter()
        filter.seed(90)
        assertFalse(filter.accept(150))  // off-baseline, starts a candidate run of 1
        assertFalse(filter.accept(150))  // agrees with the run so far (candidate run of 2)
        assertTrue(filter.accept(150))   // 3rd mutually-consistent reading - re-baselines to 150
        // Now genuinely re-baselined: a reading near the OLD baseline is rejected...
        assertFalse(filter.accept(90))
        // ...but one near the NEW baseline is accepted.
        assertTrue(filter.accept(151))
    }

    @Test
    fun `a candidate run that itself becomes inconsistent restarts the candidate count`() {
        val filter = RunningPulseFilter()
        filter.seed(90)
        assertFalse(filter.accept(150))  // candidate run: [150]
        assertFalse(filter.accept(200))  // |200-150|=50 > 1, inconsistent with the run so far - restarts: [200]
        assertFalse(filter.accept(200))  // consistent with [200]: candidate run [200, 200]
        assertTrue(filter.accept(200))   // 3rd consistent reading - re-baselines to 200
    }

    @Test
    fun `explicit reset clears both the baseline and any in-progress candidate run`() {
        val filter = RunningPulseFilter()
        filter.seed(90)
        filter.accept(150)  // candidate run in progress
        filter.reset()
        filter.seed(200)
        assertTrue(filter.accept(201))  // fresh baseline, no leftover candidate state
    }
}
