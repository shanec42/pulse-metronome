package com.austinlinux.dfmetronome.engine

/** How the metronome comes down when no pulse meter is connected (Settings "Descent pattern"). */
enum class DescentPattern { BELL_CURVE, LINEAR }

/** Stored name → pattern; anything missing or unrecognised falls back to the default. */
fun parseDescentPattern(stored: String?): DescentPattern =
    DescentPattern.values().firstOrNull { it.name == stored } ?: DescentPattern.BELL_CURVE

/** Slowest bell-curve drop while descending, BPM per minute - the no-pulse counterpart of MIN_OFFSET_STEP. */
const val NO_PULSE_MIN_RATE: Double = 0.25

data class NoPulseParams(
    val startBpm: Double,
    val targetBpm: Double,
    val totalDurationMin: Double,
    val rampDurationMin: Double,
    val maxOffsetPerMin: Double,  // with no pulse, Max offset is the largest drop per minute
    val pattern: DescentPattern
)

/**
 * The no-pulse schedule (spec 2026-10-09-no-pulse-pacing-design.md section 2). Elapsed times
 * are minutes since the session started running. Hold through the sync window, then descend:
 * Linear at gap / D, Bell curve along the derivative of smoothstep 3f^2 - 2f^3 (which covers
 * exactly gap over D, peaking at 1.5 x gap / D), both capped at maxOffsetPerMin.
 */
object NoPulseDescent {
    /** Descent rate in BPM per minute at [elapsedMin]. */
    fun ratePerMin(p: NoPulseParams, elapsedMin: Double): Double {
        val gap = p.startBpm - p.targetBpm
        if (gap <= 0.0) return 0.0
        val sync = syncWindowMin(p.totalDurationMin)
        if (elapsedMin <= sync) return 0.0
        val d = p.rampDurationMin - sync
        if (d <= 0.0) return p.maxOffsetPerMin
        return when (p.pattern) {
            DescentPattern.LINEAR -> minOf(gap / d, p.maxOffsetPerMin)
            DescentPattern.BELL_CURVE ->
                if (elapsedMin >= p.rampDurationMin) NO_PULSE_MIN_RATE
                else {
                    val f = (elapsedMin - sync) / d
                    (6.0 * gap * f * (1.0 - f) / d).coerceAtLeast(NO_PULSE_MIN_RATE).coerceAtMost(p.maxOffsetPerMin)
                }
        }
    }

    /** Advances [currentBpm] by one tick of [dtMin] minutes ending at [elapsedMin]; never below target. */
    fun step(currentBpm: Double, p: NoPulseParams, elapsedMin: Double, dtMin: Double): Double =
        maxOf(p.targetBpm, currentBpm - ratePerMin(p, elapsedMin) * dtMin)

    /** Setup's "may not reach your target" rule: the pattern's fastest needed drop exceeds Max offset per minute. */
    fun mayNotReachTarget(
        fallbackBpm: Double, targetBpm: Double, totalDurationMin: Double,
        rampDurationMin: Double, maxOffsetPerMin: Double, pattern: DescentPattern
    ): Boolean {
        val gap = fallbackBpm - targetBpm
        if (gap <= 0.0) return false
        val d = rampDurationMin - syncWindowMin(totalDurationMin)
        if (d <= 0.0) return true
        val peak = when (pattern) {
            DescentPattern.LINEAR -> gap / d
            DescentPattern.BELL_CURVE -> 1.5 * gap / d
        }
        return peak > maxOffsetPerMin
    }
}
