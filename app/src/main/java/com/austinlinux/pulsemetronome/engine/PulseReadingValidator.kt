package com.austinlinux.pulsemetronome.engine

const val READING_DEVIATION_TOLERANCE_BPM: Double = 1.0
// Also consumed by RunningPulseFilter and (Task 2) SessionEngine's own verification-streak
// logic - defined here rather than in SessionEngine.kt so Task 1's files compile and test
// independently of Task 2, per this plan's own task-ordering constraint (a task must never
// leave main unable to build/test). Task 2 reuses this constant rather than redefining it.
const val VERIFICATION_STREAK_COUNT: Int = 3

/**
 * Filters raw pulse readings for sudden, implausible jumps during the INITIAL 3-reading
 * verification streak only (SessionEngine's AWAITING_START phase). A reading is accepted
 * only if it is within READING_DEVIATION_TOLERANCE_BPM of the PREVIOUSLY ACCEPTED reading; a
 * rejected reading resets the streak so the very next reading (regardless of its value)
 * starts a fresh comparison rather than being compared against the rejected outlier. This
 * "reset to accepting anything" behavior is correct for verification (we're trying to find
 * ANY 3 consecutive stable readings, wherever they start) but is DELIBERATELY NOT reused for
 * the session-long running-phase filter - see RunningPulseFilter, which needs a stickier
 * baseline so a single rejected outlier can't be immediately followed by an unconditionally-
 * accepted reading of any value (a real bug caught in design review when this class was
 * originally reused for both purposes).
 */
class PulseReadingValidator {
    private var lastAccepted: Int? = null

    fun accept(rawPulse: Int): Boolean {
        val last = lastAccepted
        val accepted = last == null || Math.abs(rawPulse - last) <= READING_DEVIATION_TOLERANCE_BPM
        lastAccepted = if (accepted) rawPulse else null
        return accepted
    }

    fun reset() { lastAccepted = null }
}
