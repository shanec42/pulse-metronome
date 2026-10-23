package com.austinlinux.dfmetronome.ui.history

import com.austinlinux.dfmetronome.data.EndReason
import com.austinlinux.dfmetronome.data.PulseSampleEntity
import com.austinlinux.dfmetronome.data.SessionEntity
import com.austinlinux.dfmetronome.data.StartingBpmSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDisplayMapperTest {
    private fun baseEntity(
        id: Long,
        endReason: EndReason?,
        endTimestamp: Long = 3_600_000L,
        highestPulse: Int? = null,
        lowestPulse: Int? = null,
        pulseLostAtElapsedMs: Long? = null,
        targetReached: Boolean = false,
        endingBpm: Int? = 65
    ) = SessionEntity(
        id = id, startTimestamp = 0L, endTimestamp = endTimestamp, targetBpm = 60,
        rampDurationMin = 30, totalDurationMin = 60, maxOffset = 2,
        startingBpm = 90, startingBpmSource = StartingBpmSource.LIVE_PULSE,
        endingBpm = endingBpm, endReason = endReason, targetReached = targetReached,
        highestPulse = highestPulse, lowestPulse = lowestPulse,
        pulseLostAtElapsedMs = pulseLostAtElapsedMs
    )

    @Test
    fun `incomplete session shows incomplete badge flag true`() {
        val entity = baseEntity(id = 1, endReason = EndReason.INCOMPLETE, endTimestamp = 5000L, endingBpm = null)
        val display = toDisplay(entity) { "Jan 1" }
        assertTrue(display.isIncomplete)
        assertEquals("90 → —", display.bpmRangeLabel)
    }

    @Test
    fun `completed session with target reached shows flag true and correct duration`() {
        val entity = baseEntity(id = 2, endReason = EndReason.DURATION_ELAPSED, endingBpm = 60, targetReached = true)
        val display = toDisplay(entity) { "Jan 1" }
        assertFalse(display.isIncomplete)
        assertTrue(display.targetReached)
        assertEquals("1:00:00", display.durationLabel)
    }

    @Test
    fun `case A - no pulse ever detected - shows starting to ending metronome bpm not a pulse range`() {
        val entity = baseEntity(id = 3, endReason = EndReason.DURATION_ELAPSED, highestPulse = null, lowestPulse = null)
        val display = toDisplay(entity) { "Jan 1" }
        assertNull(display.pulseRangeLabel)
        assertEquals("90 → 65", display.bpmRangeLabel)
        assertNull(display.pulseLostWarning)
    }

    @Test
    fun `case B - pulse detected and held for the whole session - shows highest and lowest pulse`() {
        val entity = baseEntity(id = 4, endReason = EndReason.DURATION_ELAPSED, highestPulse = 92, lowestPulse = 61)
        val display = toDisplay(entity) { "Jan 1" }
        assertEquals("92 → 61", display.pulseRangeLabel)
        assertNull(display.pulseLostWarning)
    }

    @Test
    fun `case C - pulse lost before session end - shows highest and lowest plus a loss warning`() {
        val entity = baseEntity(
            id = 5, endReason = EndReason.DURATION_ELAPSED,
            highestPulse = 92, lowestPulse = 70, pulseLostAtElapsedMs = 1_453_000L
        )
        val display = toDisplay(entity) { "Jan 1" }
        assertEquals("92 → 70", display.pulseRangeLabel)
        assertEquals("Pulse lost at 24:13", display.pulseLostWarning)
    }

    // --- Relevance rule (2026-10-08): start = first value actually recorded, end = lowest value
    // in the session's last minute. Entity: start 0, end 3_600_000 (60 min).
    private fun sample(atSec: Int, pulse: Int?, bpm: Int) =
        PulseSampleEntity(sessionId = 1, timestamp = atSec * 1000L, pulse = pulse, metronomeBpm = bpm)

    @Test
    fun `with samples, start is the first recorded value, not the stored placeholder`() {
        val entity = baseEntity(id = 7, endReason = EndReason.DURATION_ELAPSED, endingBpm = 64, highestPulse = 80, lowestPulse = 60)
        val samples = listOf(sample(20, 76, 75), sample(40, 74, 74), sample(3590, 66, 64))
        val display = toDisplay(entity, samples) { "Jan 1" }
        assertEquals("75 → 64", display.bpmRangeLabel)
        assertEquals("76 → 66", display.pulseRangeLabel)
    }

    @Test
    fun `an early low is ignored - only the last minute counts for the end value`() {
        val entity = baseEntity(id = 8, endReason = EndReason.DURATION_ELAPSED, endingBpm = 66, highestPulse = 80, lowestPulse = 55)
        val samples = listOf(sample(20, 80, 78), sample(60, 55, 58), sample(3560, 69, 67), sample(3580, 68, 66))
        val display = toDisplay(entity, samples) { "Jan 1" }
        assertEquals("78 → 66", display.bpmRangeLabel)
        assertEquals("80 → 68", display.pulseRangeLabel)
    }

    @Test
    fun `a bounce back up in the final seconds does not hide the last-minute low`() {
        val entity = baseEntity(id = 9, endReason = EndReason.DURATION_ELAPSED, endingBpm = 70)
        val samples = listOf(sample(20, 76, 75), sample(3560, 70, 68), sample(3580, 72, 70))
        val display = toDisplay(entity, samples) { "Jan 1" }
        assertEquals("75 → 68", display.bpmRangeLabel)
        assertEquals("76 → 70", display.pulseRangeLabel)
    }

    @Test
    fun `a stored final metronome value lower than every sample is the end value`() {
        val entity = baseEntity(id = 10, endReason = EndReason.DURATION_ELAPSED, endingBpm = 63)
        val samples = listOf(sample(20, 76, 75), sample(3580, 67, 65))
        assertEquals("75 → 63", toDisplay(entity, samples) { "Jan 1" }.bpmRangeLabel)
    }

    @Test
    fun `pulse lost before the end uses the last minute of pulse data`() {
        val entity = baseEntity(id = 11, endReason = EndReason.DURATION_ELAPSED, endingBpm = 64,
            highestPulse = 82, lowestPulse = 60, pulseLostAtElapsedMs = 1_800_000L)
        val samples = listOf(sample(20, 82, 80), sample(600, 60, 62), sample(1760, 71, 69),
            sample(1780, 70, 68), sample(1800, null, 68), sample(3580, null, 64))
        assertEquals("82 → 70", toDisplay(entity, samples) { "Jan 1" }.pulseRangeLabel)
    }

    @Test
    fun `session shorter than a minute uses every sample for the end value`() {
        val entity = baseEntity(id = 12, endReason = EndReason.USER_STOPPED, endTimestamp = 45_000L, endingBpm = 71)
        val samples = listOf(sample(20, 74, 72), sample(40, 73, 71))
        val display = toDisplay(entity, samples) { "Jan 1" }
        assertEquals("72 → 71", display.bpmRangeLabel)
        assertEquals("74 → 73", display.pulseRangeLabel)
    }

    @Test
    fun `incomplete session anchors the last minute on its last sample`() {
        val entity = baseEntity(id = 13, endReason = EndReason.INCOMPLETE, endTimestamp = 0L, endingBpm = null)
            .copy(endTimestamp = null)
        val samples = listOf(sample(20, 80, 78), sample(600, 61, 63), sample(1200, 70, 68), sample(1220, 69, 67))
        val display = toDisplay(entity, samples) { "Jan 1" }
        assertEquals("78 → 67", display.bpmRangeLabel)
        assertEquals("80 → 69", display.pulseRangeLabel)
    }

    @Test
    fun `empty samples fall back to the stored values`() {
        val entity = baseEntity(id = 14, endReason = EndReason.DURATION_ELAPSED, highestPulse = 92, lowestPulse = 61)
        val display = toDisplay(entity, emptyList()) { "Jan 1" }
        assertEquals("90 → 65", display.bpmRangeLabel)
        assertEquals("92 → 61", display.pulseRangeLabel)
    }

    @Test
    fun `target reached only when the metronome is at target in the last minute`() {
        // Stored flag was set by a momentary dip early on (Oct 2 case) - samples overrule it.
        val entity = baseEntity(id = 16, endReason = EndReason.DURATION_ELAPSED, endingBpm = 64, targetReached = true)
        val earlyOnly = listOf(sample(20, 80, 78), sample(600, 61, 60), sample(3560, 66, 64), sample(3580, 66, 64))
        assertFalse(toDisplay(entity, earlyOnly) { "Jan 1" }.targetReached)

        val atEnd = listOf(sample(20, 80, 78), sample(3560, 62, 60), sample(3580, 63, 61))
        assertTrue(toDisplay(entity.copy(targetReached = false), atEnd) { "Jan 1" }.targetReached)
    }

    @Test
    fun `stored final value at target counts as reached even if the last sample was above`() {
        val entity = baseEntity(id = 17, endReason = EndReason.DURATION_ELAPSED, endingBpm = 60)
        val samples = listOf(sample(20, 80, 78), sample(3580, 63, 61))
        assertTrue(toDisplay(entity, samples) { "Jan 1" }.targetReached)
    }

    @Test
    fun `without samples the stored target-reached flag is used`() {
        val entity = baseEntity(id = 18, endReason = EndReason.DURATION_ELAPSED, targetReached = true)
        assertTrue(toDisplay(entity, emptyList()) { "Jan 1" }.targetReached)
    }

    @Test
    fun `samples with no pulse at all give no pulse range`() {
        val entity = baseEntity(id = 15, endReason = EndReason.DURATION_ELAPSED)
        val samples = listOf(sample(20, null, 70), sample(40, null, 69))
        assertNull(toDisplay(entity, samples) { "Jan 1" }.pulseRangeLabel)
    }

    @Test
    fun `incomplete session with null pulse stats is still marked incomplete not case A`() {
        val entity = baseEntity(id = 6, endReason = EndReason.INCOMPLETE, endingBpm = null, highestPulse = null, lowestPulse = null)
        val display = toDisplay(entity) { "Jan 1" }
        assertTrue(display.isIncomplete)
        assertNull(display.pulseRangeLabel)
    }

    @Test
    fun `monitor session shows Monitor as its target and never target reached`() {
        val e = baseEntity(1, EndReason.DURATION_ELAPSED, targetReached = true, endingBpm = 55).copy(monitor = true)
        val samples = listOf(
            PulseSampleEntity(sessionId = 1, timestamp = 0L, pulse = 70, metronomeBpm = 70),
            PulseSampleEntity(sessionId = 1, timestamp = 3_590_000L, pulse = 55, metronomeBpm = 55)
        )
        for (d in listOf(toDisplay(e) { "d" }, toDisplay(e, samples) { "d" })) {
            assertTrue(d.isMonitor)
            assertEquals("Monitor", d.targetLabel)
            assertFalse(d.targetReached)
        }
    }

    @Test
    fun `ordinary session target label is the bpm`() {
        val d = toDisplay(baseEntity(2, EndReason.DURATION_ELAPSED)) { "d" }
        assertFalse(d.isMonitor)
        assertEquals("60 BPM", d.targetLabel)
    }

    @Test fun `focus label shows the percentage and is absent without one`() {
        assertEquals("73 %", toDisplay(baseEntity(1, EndReason.DURATION_ELAPSED).copy(focusPercent = 73)) { "d" }.focusLabel)
        assertNull(toDisplay(baseEntity(2, EndReason.DURATION_ELAPSED)) { "d" }.focusLabel)
    }
}
