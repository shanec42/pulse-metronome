package com.austinlinux.pulsemetronome.engine

/**
 * Session-long deviation filter for RUNNING-phase readings (WHEN_PULSE_DETECTED sessions
 * only - see Global Constraints, StartMode.NOW never uses this). Unlike PulseReadingValidator
 * (used only for the initial verification streak), a rejected reading here does NOT reset
 * the comparison baseline to "accept anything next" - it keeps comparing against the last
 * KNOWN GOOD value, so a single noise spike can't be immediately followed by an
 * unconditionally-accepted reading of any value. A genuine step-change in the real pulse
 * (not noise) still eventually gets through: VERIFICATION_STREAK_COUNT (3) consecutive
 * mutually-consistent off-baseline readings re-baseline the filter, the same threshold used
 * for initial verification, so a sustained real change isn't permanently rejected.
 */
class RunningPulseFilter {
    private var baseline: Int? = null
    private val candidateRun = mutableListOf<Int>()

    fun seed(rawPulse: Int) {
        baseline = rawPulse
        candidateRun.clear()
    }

    fun accept(rawPulse: Int): Boolean {
        val current = baseline
        if (current == null || Math.abs(rawPulse - current) <= READING_DEVIATION_TOLERANCE_BPM) {
            baseline = rawPulse
            candidateRun.clear()
            return true
        }

        // Off-baseline: only a run of mutually-consistent readings (compared to EACH OTHER,
        // not to the stale baseline) may eventually re-baseline the filter.
        if (candidateRun.isNotEmpty() && Math.abs(rawPulse - candidateRun.last()) > READING_DEVIATION_TOLERANCE_BPM) {
            candidateRun.clear()
        }
        candidateRun.add(rawPulse)

        if (candidateRun.size >= VERIFICATION_STREAK_COUNT) {
            baseline = rawPulse
            candidateRun.clear()
            return true
        }
        return false
    }

    fun reset() {
        baseline = null
        candidateRun.clear()
    }
}
