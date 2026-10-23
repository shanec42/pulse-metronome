package com.austinlinux.dfmetronome.engine

/** The meter's latest reading if it is recent enough to start a session from; otherwise null. */
fun usableInitialPulse(bpm: Int?, atMs: Long?, nowMs: Long): Int? {
    if (bpm == null || atMs == null) return null
    return if (nowMs - atMs < STALENESS_TIMEOUT_MS) bpm else null
}
