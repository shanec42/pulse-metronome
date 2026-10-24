package com.austinlinux.pulsemetronome.engine

const val EMA_ALPHA: Double = 0.3

/** Exponential moving average smoother for raw pulse readings. Stateful, single-threaded use. */
class PulseSmoother(private val alpha: Double = EMA_ALPHA) {
    private var smoothed: Double? = null

    fun update(rawPulse: Double): Double {
        val prev = smoothed
        val next = if (prev == null) rawPulse else prev + alpha * (rawPulse - prev)
        smoothed = next
        return next
    }

    fun currentValue(): Double? = smoothed

    fun reset() { smoothed = null }

    /** Used when starting BPM is established from something other than a raw update() call
     *  (e.g. the mean of a 3-reading verification streak, bell-curve pacing redesign) - sets
     *  the smoothed value directly so subsequent update() calls blend from it. */
    fun seed(value: Double) { smoothed = value }
}
