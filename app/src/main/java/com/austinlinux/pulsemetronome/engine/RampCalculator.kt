package com.austinlinux.pulsemetronome.engine

const val MIN_OFFSET_STEP: Double = 0.25

/** 10% of total session duration, floored at 1 minute - the window during which the
 *  metronome exactly mirrors the live pulse with zero pacing lag, letting the user
 *  "sync up" with the metronome before any nudging begins. */
fun syncWindowMin(totalDurationMin: Double): Double = maxOf(0.10 * totalDurationMin, 1.0)

data class RampInput(
    val currentPulse: Double,
    val startingBpm: Double,
    val targetBpm: Double,
    val rampDurationMin: Double,
    val syncWindowMin: Double,
    val elapsedMin: Double,  // elapsed time since runningStartMs, UNCAPPED by the caller
    val maxOffset: Double
)

data class RampOutput(
    val offset: Double,
    val targetMetronomeBpm: Double
)

/**
 * Bell-curve pacing formula, replacing the earlier linear-rate formula entirely (see
 * docs/superpowers/specs/2026-09-27-bell-curve-pacing-redesign.md section 3). Three phases:
 *  1. Sync window (elapsedMin <= syncWindowMin): offset = 0, metronome tracks pulse exactly.
 *  2. starting_gap <= 0 (pulse started at/below target): offset = 0 for the WHOLE session,
 *     guarded BEFORE any division - never computed by clamping a NaN/negative division
 *     result after the fact.
 *  3. Otherwise: offset = max(growth(t) * taper(gap), MIN_OFFSET_STEP) - a smooth
 *     growth*taper curve, floored so it never eases through values smaller than 0.25 on its
 *     way back toward zero near target. The floor does NOT apply during the sync window
 *     (phase 1) or when starting_gap <= 0 (phase 2) - only once actively pacing.
 */
object RampCalculator {
    fun calculate(input: RampInput): RampOutput {
        val startingGap = input.startingBpm - input.targetBpm

        val offset = if (input.elapsedMin <= input.syncWindowMin) {
            0.0
        } else if (startingGap <= 0.0) {
            0.0
        } else {
            val denom = input.rampDurationMin - input.syncWindowMin
            val growth = if (denom <= 0.0) {
                input.maxOffset
            } else {
                val fraction = ((input.elapsedMin - input.syncWindowMin) / denom).coerceIn(0.0, 1.0)
                input.maxOffset * fraction
            }
            val currentGap = input.currentPulse - input.targetBpm
            val taper = (currentGap / startingGap).coerceIn(0.0, 1.0)
            val rawOffset = growth * taper
            maxOf(rawOffset, MIN_OFFSET_STEP)
        }

        val targetMetronomeBpm = maxOf(input.targetBpm, input.currentPulse - offset)
        return RampOutput(offset = offset, targetMetronomeBpm = targetMetronomeBpm)
    }
}
