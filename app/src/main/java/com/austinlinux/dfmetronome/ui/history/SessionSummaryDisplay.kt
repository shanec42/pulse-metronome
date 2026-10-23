package com.austinlinux.dfmetronome.ui.history

import com.austinlinux.dfmetronome.data.EndReason
import com.austinlinux.dfmetronome.data.PulseSampleEntity
import com.austinlinux.dfmetronome.data.SessionEntity
import com.austinlinux.dfmetronome.ui.session.formatElapsed

data class SessionSummaryDisplay(
    val dateLabel: String,
    val durationLabel: String,
    val targetBpm: Int,
    val bpmRangeLabel: String,
    // Null in Case A (no pulse ever detected - a session with no accepted pulse reading, mainly a no-pulse StartMode.NO_PULSE session).
    // Non-null in Case B/C (highest -> lowest pulse actually recorded during the session).
    val pulseRangeLabel: String?,
    // Non-null only in Case C (signal lost and never recovered before session end).
    val pulseLostWarning: String?,
    val targetReached: Boolean,
    val isIncomplete: Boolean,
    val isMonitor: Boolean,
    val targetLabel: String,
    // Non-null only for paced sessions that counted at least one reading, e.g. "73 %".
    val focusLabel: String?
)

// The end of a range is the low point of the session's final minute - an early dip or a
// bounce back up in the last seconds isn't what the user cares about.
private const val RELEVANT_END_WINDOW_MS = 60_000L

/** Maps a session row to card/Summary text; with [samples], ranges use the first recorded
 *  value and the last-minute low instead of the stored placeholder/extremes. */
fun toDisplay(
    entity: SessionEntity,
    samples: List<PulseSampleEntity>? = null,
    dateFormatter: (Long) -> String
): SessionSummaryDisplay {
    val durationMs = (entity.endTimestamp ?: entity.startTimestamp) - entity.startTimestamp
    val storedPulseRange = if (entity.highestPulse != null && entity.lowestPulse != null) {
        "${entity.highestPulse} → ${entity.lowestPulse}"
    } else null
    val storedBpmRange = "${entity.startingBpm} → ${entity.endingBpm ?: "—"}"

    val bpmRangeLabel: String
    val pulseRangeLabel: String?
    val targetReached: Boolean
    if (samples.isNullOrEmpty()) {
        bpmRangeLabel = storedBpmRange
        pulseRangeLabel = storedPulseRange
        targetReached = entity.targetReached
    } else {
        val bpmAnchor = entity.endTimestamp ?: samples.last().timestamp
        val bpmEndCandidates = samples.filter { it.timestamp >= bpmAnchor - RELEVANT_END_WINDOW_MS }
            .map { it.metronomeBpm } + listOfNotNull(entity.endingBpm)
        val bpmEnd = bpmEndCandidates.minOrNull()
        bpmRangeLabel = "${samples.first().metronomeBpm} → ${bpmEnd ?: "—"}"
        // Same last-minute rule: the stored flag latches on any instant at target, so a single
        // early dip used to show "Target reached" for a session that never got there.
        targetReached = bpmEnd != null && bpmEnd <= entity.targetBpm

        val withPulse = samples.filter { it.pulse != null }
        pulseRangeLabel = if (withPulse.isEmpty()) null else {
            // Anchored on the last good reading so a signal lost before the end still has a window.
            val pulseAnchor = withPulse.last().timestamp
            val pulseEnd = withPulse.filter { it.timestamp >= pulseAnchor - RELEVANT_END_WINDOW_MS }.minOf { it.pulse!! }
            "${withPulse.first().pulse} → $pulseEnd"
        }
    }
    val pulseLostWarning = entity.pulseLostAtElapsedMs?.let { "Pulse lost at ${formatElapsed(it)}" }
    return SessionSummaryDisplay(
        dateLabel = dateFormatter(entity.startTimestamp),
        durationLabel = formatElapsed(durationMs),
        targetBpm = entity.targetBpm,
        bpmRangeLabel = bpmRangeLabel,
        pulseRangeLabel = pulseRangeLabel,
        pulseLostWarning = pulseLostWarning,
        targetReached = !entity.monitor && targetReached,
        isIncomplete = entity.endReason == EndReason.INCOMPLETE,
        isMonitor = entity.monitor,
        targetLabel = if (entity.monitor) "Monitor" else "${entity.targetBpm} BPM",
        focusLabel = entity.focusPercent?.let { "$it %" }
    )
}
