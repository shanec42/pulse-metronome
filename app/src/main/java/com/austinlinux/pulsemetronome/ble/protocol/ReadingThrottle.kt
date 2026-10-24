package com.austinlinux.pulsemetronome.ble.protocol

/** Lets through at most one reading per [intervalMs] (900 leaves room for jitter on ~1 Hz devices; a clock that jumps back never blocks), so a fast stream can't flood the ~1 Hz session code. */
class ReadingThrottle(private val intervalMs: Long = 900L) {
    private var lastMs: Long? = null
    fun allow(nowMs: Long): Boolean {
        val last = lastMs
        if (last != null && nowMs >= last && nowMs - last < intervalMs) return false
        lastMs = nowMs; return true
    }
}
