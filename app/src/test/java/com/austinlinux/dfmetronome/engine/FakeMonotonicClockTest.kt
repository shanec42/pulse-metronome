package com.austinlinux.dfmetronome.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeMonotonicClockTest {
    @Test
    fun `starts at configured initial value`() {
        val clock = FakeMonotonicClock(currentMs = 500L)
        assertEquals(500L, clock.nowMs())
    }

    @Test
    fun `advanceMs increments time by delta`() {
        val clock = FakeMonotonicClock(currentMs = 0L)
        clock.advanceMs(1500L)
        assertEquals(1500L, clock.nowMs())
        clock.advanceMs(250L)
        assertEquals(1750L, clock.nowMs())
    }
}
