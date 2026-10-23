package com.austinlinux.dfmetronome.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatSchedulerTest {
    @Test
    fun `60 bpm produces 1000ms interval`() {
        assertEquals(1000L, BeatScheduler.nextBeatTimestampMs(0L, 60.0))
    }

    @Test
    fun `120 bpm produces 500ms interval`() {
        assertEquals(500L, BeatScheduler.nextBeatTimestampMs(0L, 120.0))
    }

    @Test
    fun `bpm change between calls only affects the interval computed after the change`() {
        val beat1 = BeatScheduler.nextBeatTimestampMs(0L, 60.0)
        val beat2 = BeatScheduler.nextBeatTimestampMs(beat1, 90.0)
        assertEquals(1000L, beat1)
        // 60_000.0 / 90.0 = 666.667, truncated by toLong() to 666ms -> 1000 + 666 = 1666.
        assertEquals(1666L, beat2)
    }
}
