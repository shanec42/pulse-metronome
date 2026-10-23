package com.austinlinux.dfmetronome.engine

/** How far above the planned descent the pulse may sit and still count as in focus (being below it always counts). */
const val FOCUS_ALLOWANCE_BPM: Double = 2.0

/** The planned descent is stepped in the engine's own tick size, so it matches the no-pulse schedule. */
private const val PLAN_STEP_MIN: Double = 0.25 / 60.0

/**
 * Share of counted readings in which the pulse kept to the planned descent: the no-pulse
 * schedule ([NoPulseDescent]) from the session's starting BPM. A reading is in focus when the
 * pulse is at or below the plan plus [FOCUS_ALLOWANCE_BPM], so being ahead of the plan counts.
 */
class FocusTracker(private val plan: NoPulseParams) {
    private var inFocus = 0
    private var total = 0
    private var planBpm = plan.startBpm
    private var planAtMin = 0.0

    /** The planned BPM [elapsedMin] minutes after the session started running; times must not go backwards. */
    fun plannedBpmAt(elapsedMin: Double): Double {
        while (planAtMin < elapsedMin) {
            val dt = minOf(PLAN_STEP_MIN, elapsedMin - planAtMin)
            planAtMin += dt
            planBpm = NoPulseDescent.step(planBpm, plan, planAtMin, dt)
        }
        return planBpm
    }

    fun record(smoothedPulse: Double, elapsedMin: Double) {
        total++
        if (smoothedPulse <= plannedBpmAt(elapsedMin) + FOCUS_ALLOWANCE_BPM) inFocus++
    }

    /** Rounded percentage, or null when nothing was counted. */
    val percent: Int? get() = if (total == 0) null else Math.round(100.0 * inFocus / total).toInt()
    val counted: Int get() = total
}
