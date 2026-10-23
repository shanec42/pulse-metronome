package com.austinlinux.dfmetronome.audio

/**
 * Pure calculation: given a scheduled-beat timestamp and a BPM, computes the
 * next beat's timestamp. A tempo change takes effect starting from the next
 * scheduled beat, not by re-timing a beat already in progress (spec: Audio
 * Engine "Tempo change timing").
 */
object BeatScheduler {
    fun nextBeatTimestampMs(currentBeatTimestampMs: Long, bpmAtSchedulingTime: Double): Long {
        val intervalMs = (60_000.0 / bpmAtSchedulingTime).toLong()
        return currentBeatTimestampMs + intervalMs
    }
}
