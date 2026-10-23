package com.austinlinux.dfmetronome.engine

const val MAX_BPM_CHANGE_PER_UPDATE: Double = 2.0

/** Caps metronome_bpm change per update, per spec Rate Limiting section. */
class BpmRateLimiter(private val maxChangePerUpdate: Double = MAX_BPM_CHANGE_PER_UPDATE) {
    private var current: Double? = null

    fun update(target: Double): Double {
        val prev = current
        val next = if (prev == null) {
            target
        } else if (Math.abs(target - prev) <= maxChangePerUpdate) {
            // Return target directly (not prev + delta) when within the cap. Computing
            // prev + delta can land a tiny floating-point epsilon away from target with
            // non-integer values (e.g. prev = 61.3, target = 60.0 -> prev + (-1.3) may not
            // be bit-for-bit 60.0), which would make SessionEngine's target_reached
            // exact-equality check against config.targetBpm silently never fire in real
            // sessions using non-integer smoothed pulses - the SessionEngineTest cases use
            // integer BPM steps and would not have caught this.
            target
        } else {
            val delta = (target - prev).coerceIn(-maxChangePerUpdate, maxChangePerUpdate)
            prev + delta
        }
        current = next
        return next
    }

    fun currentValue(): Double? = current

    fun reset() { current = null }

    /** Used when starting BPM is established without going through the update cap. */
    fun seed(value: Double) { current = value }
}
