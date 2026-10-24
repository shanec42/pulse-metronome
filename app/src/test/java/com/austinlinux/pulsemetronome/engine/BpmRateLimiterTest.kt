package com.austinlinux.pulsemetronome.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BpmRateLimiterTest {
    @Test
    fun `first update seeds value exactly regardless of magnitude`() {
        val limiter = BpmRateLimiter()
        assertEquals(90.0, limiter.update(90.0), 0.0001)
    }

    @Test
    fun `large upward jump is capped at 2 bpm per update`() {
        val limiter = BpmRateLimiter()
        limiter.update(80.0)
        val result = limiter.update(95.0)
        assertEquals(82.0, result, 0.0001)
    }

    @Test
    fun `large downward jump is capped at 2 bpm per update`() {
        val limiter = BpmRateLimiter()
        limiter.update(80.0)
        val result = limiter.update(50.0)
        assertEquals(78.0, result, 0.0001)
    }

    @Test
    fun `small change within cap passes through unmodified`() {
        val limiter = BpmRateLimiter()
        limiter.update(80.0)
        val result = limiter.update(81.0)
        assertEquals(81.0, result, 0.0001)
    }

    @Test
    fun `sustained large target requires multiple updates to converge - simulates resume-after-dropout`() {
        val limiter = BpmRateLimiter()
        limiter.update(60.0)
        val r1 = limiter.update(90.0)
        val r2 = limiter.update(90.0)
        val r3 = limiter.update(90.0)
        assertEquals(62.0, r1, 0.0001)
        assertEquals(64.0, r2, 0.0001)
        assertEquals(66.0, r3, 0.0001)
    }

    @Test
    fun `seed sets current value directly without going through the cap`() {
        val limiter = BpmRateLimiter()
        limiter.seed(75.0)
        assertEquals(75.0, limiter.currentValue()!!, 0.0001)
        val result = limiter.update(76.0)
        assertEquals(76.0, result, 0.0001)
    }

    @Test
    fun `non-integer target within cap lands exactly on target not prev plus delta`() {
        // Regression test for a floating-point equality gap: prev + delta can miss target
        // by an epsilon for non-integer values, which would break SessionEngine's
        // target_reached exact-equality check in real sessions (smoothed pulses are rarely
        // whole numbers). update() must return `target` itself when |target - prev| <= cap.
        val limiter = BpmRateLimiter()
        limiter.seed(61.3)
        val result = limiter.update(60.0)  // delta = -1.3, within the 2.0 cap
        assertEquals(60.0, result, 0.0)  // exact equality, not just "close to"
    }
}
