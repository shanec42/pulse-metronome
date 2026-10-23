package com.austinlinux.dfmetronome.engine

const val STALENESS_TIMEOUT_MS: Long = 4_000L

/**
 * Tracks whether the pulse signal is currently considered "lost" per the spec's
 * four trigger conditions. Pure state machine — no timers of its own; caller
 * drives staleness evaluation by calling isSignalLost() against the injected clock.
 */
class SignalLossTracker(private val clock: MonotonicClock) {
    private var lastValidPulseAtMs: Long? = null
    private var disconnected: Boolean = false
    private var lastReportedInvalid: Boolean = false
    private var sensorOff: Boolean = false

    fun onValidPulse() {
        lastValidPulseAtMs = clock.nowMs()
        lastReportedInvalid = false
    }

    fun onInvalidPulse() { lastReportedInvalid = true }

    fun onDisconnected() { disconnected = true }

    fun onReconnected() { disconnected = false }

    fun onSensorOffChanged(isOff: Boolean) { sensorOff = isOff }

    fun isSignalLost(): Boolean {
        if (disconnected) return true
        if (lastReportedInvalid) return true
        if (sensorOff) return true
        val lastValid = lastValidPulseAtMs ?: return true
        return clock.nowMs() - lastValid >= STALENESS_TIMEOUT_MS
    }
}
