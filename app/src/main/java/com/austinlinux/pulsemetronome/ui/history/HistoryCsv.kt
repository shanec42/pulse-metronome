package com.austinlinux.pulsemetronome.ui.history

import com.austinlinux.pulsemetronome.data.EndReason
import com.austinlinux.pulsemetronome.data.PulseSampleEntity
import com.austinlinux.pulsemetronome.data.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val CSV_HEADER = "session,start,mode,target_bpm,ramp_min,total_min,ended,elapsed_s,pulse,metronome_bpm"

/**
 * The whole History as one CSV: one row per saved reading, each repeating its session's details
 * so the file sorts and charts directly in a spreadsheet. A session with no readings gets one row
 * with the reading columns blank. Sessions recorded with History off ([pendingDeleteIds]) are left out.
 */
fun historyCsv(
    sessions: List<SessionEntity>,
    samples: List<PulseSampleEntity>,
    pendingDeleteIds: Set<Long>,
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }
    val samplesBySession = samples.groupBy { it.sessionId }
    val out = StringBuilder(CSV_HEADER).append('\n')
    for (s in sessions.filter { it.id !in pendingDeleteIds }.sortedBy { it.startTimestamp }) {
        val own = samplesBySession[s.id].orEmpty().sortedBy { it.timestamp }
        val mode = when {
            s.monitor -> "monitor"
            s.highestPulse == null && own.none { it.pulse != null } -> "no pulse"
            else -> "paced"
        }
        // Monitor sessions have no target or ramp; the stored values are hidden Setup leftovers.
        val sessionPart = listOf(
            s.id.toString(), dateFormat.format(Date(s.startTimestamp)), mode,
            if (s.monitor) "" else s.targetBpm.toString(), if (s.monitor) "" else s.rampDurationMin.toString(),
            s.totalDurationMin.toString(), endedLabel(s.endReason)
        ).joinToString(",")
        if (own.isEmpty()) {
            out.append(sessionPart).append(",,,\n")
        } else {
            for (r in own) {
                val elapsedS = Math.round((r.timestamp - s.startTimestamp) / 1000.0)
                out.append(sessionPart).append(',').append(elapsedS).append(',')
                    .append(r.pulse?.toString() ?: "").append(',').append(r.metronomeBpm).append('\n')
            }
        }
    }
    return out.toString()
}

private fun endedLabel(reason: EndReason?): String = when (reason) {
    EndReason.DURATION_ELAPSED -> "time up"
    EndReason.USER_STOPPED -> "stopped"
    EndReason.INCOMPLETE -> "incomplete"
    null -> ""
}
