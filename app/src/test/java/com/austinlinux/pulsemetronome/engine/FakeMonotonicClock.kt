package com.austinlinux.pulsemetronome.engine

class FakeMonotonicClock(private var currentMs: Long = 0L) : MonotonicClock {
    override fun nowMs(): Long = currentMs
    fun advanceMs(deltaMs: Long) { currentMs += deltaMs }
    fun setMs(ms: Long) { currentMs = ms }
}
