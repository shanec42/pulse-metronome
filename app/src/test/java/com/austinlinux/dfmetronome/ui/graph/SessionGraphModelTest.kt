package com.austinlinux.dfmetronome.ui.graph

import com.austinlinux.dfmetronome.data.PulseSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGraphModelTest {
    private val start = 1_000_000L
    private fun s(sec: Int, pulse: Int?, bpm: Int) =
        PulseSampleEntity(sessionId = 1, timestamp = start + sec * 1000L, pulse = pulse, metronomeBpm = bpm)

    @Test fun `elapsed is relative to session start`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(40, 79, 77)), start, null)
        assertEquals(listOf(20_000L, 40_000L), m.bpmPoints.map { it.elapsedMs })
        assertEquals(40_000L, m.xMaxMs)
    }

    @Test fun `null pulse splits segments and brass line has no gaps`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(40, 79, 77), s(60, null, 76), s(80, 75, 75)), start, null)
        assertEquals(2, m.pulseSegments.size)
        assertEquals(listOf(80, 79), m.pulseSegments[0].map { it.value })
        assertEquals(listOf(75), m.pulseSegments[1].map { it.value })
        assertEquals(4, m.bpmPoints.size)
    }

    @Test fun `leading and trailing nulls produce no empty segments`() {
        val m = SessionGraphModel.from(listOf(s(20, null, 78), s(40, 79, 77), s(60, null, 76)), start, null)
        assertEquals(1, m.pulseSegments.size)
        assertEquals(listOf(79), m.pulseSegments[0].map { it.value })
    }

    @Test fun `all-null pulse samples report no pulse`() {
        val m = SessionGraphModel.from(listOf(s(20, null, 72), s(40, null, 68)), start, null)
        assertFalse(m.hasPulse)
    }

    @Test fun `all-null pulse gives no segments and range from bpm only`() {
        val m = SessionGraphModel.from(listOf(s(20, null, 72), s(40, null, 68)), start, null)
        assertTrue(m.pulseSegments.isEmpty())
        assertEquals(60, m.yMin)  // floor((68-3)/10)*10
        assertEquals(80, m.yMax)  // ceil((72+3)/10)*10
        assertTrue(m.hasEnoughData)
    }

    @Test fun `y range pads by 3 and rounds to tens, ticks every 10`() {
        val m = SessionGraphModel.from(listOf(s(20, 84, 80), s(40, 59, 61)), start, null)
        assertEquals(50, m.yMin)  // floor((59-3)/10)*10 = 50
        assertEquals(90, m.yMax)  // ceil((84+3)/10)*10 = 90
        assertEquals(listOf(50, 60, 70, 80, 90), m.yTicks)
    }

    @Test fun `x ticks step 5 min up to 25 min`() {
        val m = SessionGraphModel.from(listOf(s(0, 80, 80), s(20 * 60, 70, 70)), start, null)
        assertEquals(listOf("0", "5", "10", "15", "20"), m.xTicks.map { it.label })
    }

    @Test fun `x ticks step 10 min up to 50 min and 15 beyond`() {
        assertEquals(listOf("0", "10", "20", "30"), SessionGraphModel.from(listOf(s(0, 80, 80), s(30 * 60, 70, 70)), start, null).xTicks.map { it.label })
        assertEquals(listOf("0", "15", "30", "45", "60"), SessionGraphModel.from(listOf(s(0, 80, 80), s(60 * 60, 70, 70)), start, null).xTicks.map { it.label })
    }

    @Test fun `lost marker is clamped into range`() {
        val samples = listOf(s(20, 80, 78), s(40, 79, 77))
        assertEquals(30_000L, SessionGraphModel.from(samples, start, 30_000L).lostMarkerMs)
        assertEquals(40_000L, SessionGraphModel.from(samples, start, 99_000L).lostMarkerMs)
        assertNull(SessionGraphModel.from(samples, start, null).lostMarkerMs)
    }

    @Test fun `fewer than two samples is not enough data`() {
        assertFalse(SessionGraphModel.from(emptyList(), start, null).hasEnoughData)
        val one = SessionGraphModel.from(listOf(s(20, 80, 78)), start, 5_000L)
        assertFalse(one.hasEnoughData)
        assertTrue(one.bpmPoints.isEmpty())
        assertNull(one.lostMarkerMs)
    }

    @Test fun `sample before start is floored to zero elapsed`() {
        val m = SessionGraphModel.from(listOf(s(-5, 80, 78), s(20, 79, 77)), start, null)
        assertEquals(0L, m.bpmPoints[0].elapsedMs)
    }

    @Test fun `x axis spans the whole session when the end time is known`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(40, 79, 77)), start, null, endTimestamp = start + 60_000L)
        assertEquals(60_000L, m.xMaxMs)
    }

    @Test fun `end time earlier than the last sample does not shrink the axis`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(40, 79, 77)), start, null, endTimestamp = start + 30_000L)
        assertEquals(40_000L, m.xMaxMs)
    }

    @Test fun `sessions of 5 minutes or less get one-minute ticks`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(280, 70, 69)), start, null, endTimestamp = start + 299_000L)
        assertEquals(listOf("0", "1", "2", "3", "4", "4:59"), m.xTicks.map { it.label })
    }

    @Test fun `just over 5 minutes goes back to five-minute ticks`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(320, 70, 69)), start, null, endTimestamp = start + 330_000L)
        // The 5-minute tick sits 30 s from the 5:30 end label and would overlap it.
        assertEquals(listOf("0", "5:30"), m.xTicks.map { it.label })
    }

    @Test fun `a timed-out session's axis runs to its planned length`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(280, 70, 69)), start, null,
            endTimestamp = start + 299_500L, plannedDurationMs = 300_000L)
        assertEquals(300_000L, m.xMaxMs)
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), m.xTicks.map { it.label })
    }

    @Test fun `a stopped session ends with an m-ss label at its end time`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(140, 70, 69)), start, null, endTimestamp = start + 157_000L)
        assertEquals(listOf("0", "1", "2", "2:37"), m.xTicks.map { it.label })
        assertEquals(157_000L, m.xTicks.last().elapsedMs)
    }

    @Test fun `a minute tick crowding the end label is dropped`() {
        val m = SessionGraphModel.from(listOf(s(20, 80, 78), s(120, 70, 69)), start, null, endTimestamp = start + 130_000L)
        assertEquals(listOf("0", "1", "2:10"), m.xTicks.map { it.label })
    }
}
