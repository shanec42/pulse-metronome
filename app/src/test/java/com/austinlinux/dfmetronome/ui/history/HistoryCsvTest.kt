package com.austinlinux.dfmetronome.ui.history

import com.austinlinux.dfmetronome.data.EndReason
import com.austinlinux.dfmetronome.data.PulseSampleEntity
import com.austinlinux.dfmetronome.data.SessionEntity
import com.austinlinux.dfmetronome.data.StartingBpmSource
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryCsvTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun session(id: Long, start: Long, monitor: Boolean = false, highest: Int? = 80, end: EndReason? = EndReason.DURATION_ELAPSED) = SessionEntity(
        id = id, startTimestamp = start, endTimestamp = start + 120_000L, targetBpm = 60, rampDurationMin = 2,
        totalDurationMin = 2, maxOffset = 2, startingBpm = 80, startingBpmSource = StartingBpmSource.LIVE_PULSE,
        endingBpm = 62, endReason = end, highestPulse = highest, lowestPulse = 70, monitor = monitor
    )

    private fun sample(sessionId: Long, at: Long, pulse: Int?, bpm: Int) =
        PulseSampleEntity(sessionId = sessionId, timestamp = at, pulse = pulse, metronomeBpm = bpm)

    @Test fun `one row per reading with the session details repeated, oldest session first`() {
        val s1 = session(1, 1_000_000_000_000L)
        val s2 = session(2, 1_000_000_600_000L)
        val csv = historyCsv(
            listOf(s2, s1),
            listOf(sample(1, 1_000_000_020_000L, 79, 79), sample(1, 1_000_000_040_000L, null, 78), sample(2, 1_000_000_620_000L, 75, 74)),
            pendingDeleteIds = emptySet(), timeZone = utc
        )
        assertEquals(
            listOf(
                "session,start,mode,target_bpm,ramp_min,total_min,ended,elapsed_s,pulse,metronome_bpm",
                "1,2001-09-09 01:46:40,paced,60,2,2,time up,20,79,79",
                "1,2001-09-09 01:46:40,paced,60,2,2,time up,40,,78",
                "2,2001-09-09 01:56:40,paced,60,2,2,time up,20,75,74"
            ),
            csv.trimEnd().lines()
        )
    }

    @Test fun `modes, end reasons and a session without readings`() {
        val monitor = session(1, 1_000_000_000_000L, monitor = true)
        val noPulse = session(2, 1_000_000_600_000L, highest = null, end = EndReason.USER_STOPPED)
        val incomplete = session(3, 1_000_001_200_000L, end = EndReason.INCOMPLETE)
        val lines = historyCsv(
            listOf(monitor, noPulse, incomplete),
            listOf(sample(2, 1_000_000_620_000L, null, 70)),
            pendingDeleteIds = emptySet(), timeZone = utc
        ).trimEnd().lines()
        assertEquals("1,2001-09-09 01:46:40,monitor,,,2,time up,,,", lines[1])
        assertEquals("2,2001-09-09 01:56:40,no pulse,60,2,2,stopped,20,,70", lines[2])
        assertEquals("3,2001-09-09 02:06:40,paced,60,2,2,incomplete,,,", lines[3])
    }

    @Test fun `sessions recorded with history off are left out`() {
        val lines = historyCsv(listOf(session(1, 1_000_000_000_000L), session(2, 1_000_000_600_000L)), emptyList(), pendingDeleteIds = setOf(2L), timeZone = utc)
            .trimEnd().lines()
        assertEquals(2, lines.size)
        assertEquals("1", lines[1].substringBefore(','))
    }
}
