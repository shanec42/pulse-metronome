package com.austinlinux.dfmetronome.engine

/** Injectable monotonic time source. On-device impl backed by SystemClock.elapsedRealtime(). */
interface MonotonicClock {
    fun nowMs(): Long
}
