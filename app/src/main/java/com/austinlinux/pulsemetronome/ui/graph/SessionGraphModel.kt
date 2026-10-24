package com.austinlinux.pulsemetronome.ui.graph

import com.austinlinux.pulsemetronome.data.EndReason
import com.austinlinux.pulsemetronome.data.PulseSampleEntity
import com.austinlinux.pulsemetronome.data.SessionEntity

data class GraphPoint(val elapsedMs: Long, val value: Int)

/** A labelled position on the time axis ("5" for whole minutes, "2:37" for a mid-minute end). */
data class TimeTick(val elapsedMs: Long, val label: String)

/** Everything SessionGraph needs to draw one session, precomputed from the stored 20 s samples. */
data class SessionGraphModel(
    val bpmPoints: List<GraphPoint>,
    val pulseSegments: List<List<GraphPoint>>,
    val lostMarkerMs: Long?,
    val xMaxMs: Long,
    val yMin: Int,
    val yMax: Int,
    val yTicks: List<Int>,
    val xTicks: List<TimeTick>,
    val hasEnoughData: Boolean
) {
    /** True when at least one pulse reading was recorded (false for a no-pulse session). */
    val hasPulse: Boolean get() = pulseSegments.isNotEmpty()

    companion object {
        private val EMPTY = SessionGraphModel(emptyList(), emptyList(), null, 1L, 50, 90, emptyList(), emptyList(), false)

        /** Builds the model; pulse nulls (signal lost) split the pulse line into segments. */
        fun from(
            samples: List<PulseSampleEntity>,
            startTimestamp: Long,
            pulseLostAtElapsedMs: Long?,
            endTimestamp: Long? = null,
            // Only for sessions that ran out their time: the engine ends a moment short of the
            // planned length (4:59 for a 5-minute session), so the axis is stretched to it.
            plannedDurationMs: Long? = null
        ): SessionGraphModel {
            if (samples.size < 2) return EMPTY
            fun elapsed(s: PulseSampleEntity) = (s.timestamp - startTimestamp).coerceAtLeast(0L)

            val bpmPoints = samples.map { GraphPoint(elapsed(it), it.metronomeBpm) }
            val segments = mutableListOf<List<GraphPoint>>()
            var current = mutableListOf<GraphPoint>()
            for (s in samples) {
                val p = s.pulse
                if (p == null) {
                    if (current.isNotEmpty()) segments += current
                    current = mutableListOf()
                } else current += GraphPoint(elapsed(s), p)
            }
            if (current.isNotEmpty()) segments += current

            // Span the whole session when its end is known; the last sample can be up to 20 s early,
            // which made short sessions start mid-plot.
            val sessionMs = endTimestamp?.let { it - startTimestamp } ?: 0L
            val xMax = maxOf(bpmPoints.last().elapsedMs, sessionMs, plannedDurationMs ?: 0L).coerceAtLeast(1L)
            val values = bpmPoints.map { it.value } + segments.flatten().map { it.value }
            val yMin = Math.floorDiv(values.min() - 3, 10) * 10
            val yMax = -Math.floorDiv(-(values.max() + 3), 10) * 10
            val xMaxMin = xMax / 60_000.0
            val step = when { xMaxMin <= 5 -> 1; xMaxMin <= 25 -> 5; xMaxMin <= 50 -> 10; else -> 15 }
            val stepMs = step * 60_000L
            var xTicks = generateSequence(0L) { it + stepMs }.takeWhile { it <= xMax }
                .map { TimeTick(it, (it / 60_000L).toString()) }.toList()
            // The axis always ends with a labelled value; a mid-minute end gets an m:ss label and
            // any regular tick close enough to collide with it is dropped.
            if (xMax % stepMs != 0L) {
                xTicks = xTicks.filter { xMax - it.elapsedMs >= stepMs * 2 / 5 } + TimeTick(xMax, endLabel(xMax))
            }

            return SessionGraphModel(
                bpmPoints = bpmPoints,
                pulseSegments = segments,
                lostMarkerMs = pulseLostAtElapsedMs?.coerceIn(0L, xMax),
                xMaxMs = xMax,
                yMin = yMin,
                yMax = yMax,
                yTicks = (yMin..yMax step 10).toList(),
                xTicks = xTicks,
                hasEnoughData = true
            )
        }

        private fun endLabel(ms: Long): String {
            val totalSec = ms / 1000
            return if (totalSec % 60 == 0L) (totalSec / 60).toString() else "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}

/** The planned length for a session that ran out its time (used to end the graph's axis there); null otherwise. */
fun SessionEntity.plannedDurationMsIfTimedOut(): Long? =
    if (endReason == EndReason.DURATION_ELAPSED) totalDurationMin * 60_000L else null
