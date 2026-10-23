package com.austinlinux.dfmetronome.engine

const val PULSE_SAMPLE_TICK_MS: Long = 20_000L
// VERIFICATION_STREAK_COUNT is defined in PulseReadingValidator.kt (Task 1) - reused here
// rather than redefined, since RunningPulseFilter (also Task 1) needed it before this file
// existed, and this file must not shadow/redeclare a top-level const already in this package.

enum class StartingBpmSource { LIVE_PULSE, STATIC_FALLBACK }
enum class EndReason { DURATION_ELAPSED, USER_STOPPED }

enum class StartMode {
    NOW,
    WHEN_PULSE_DETECTED,
    NO_PULSE  // Wait for pulse off and no meter connected at Start: schedule-driven, pulse ignored
}

/** Monitor mode only: what the metronome does while the pulse is lost (Settings). */
enum class MonitorSignalLoss { HOLD, SILENT }

fun parseMonitorSignalLoss(stored: String?): MonitorSignalLoss =
    MonitorSignalLoss.values().firstOrNull { it.name == stored } ?: MonitorSignalLoss.HOLD

/** Paced sessions only: what the metronome does while the pulse is lost (Settings). */
enum class PacedSignalLoss { HOLD, CONTINUE }

fun parsePacedSignalLoss(stored: String?): PacedSignalLoss =
    PacedSignalLoss.values().firstOrNull { it.name == stored } ?: PacedSignalLoss.HOLD

/** True while a monitor session set to "Stop the metronome" has lost the pulse. */
fun shouldSilence(monitor: Boolean, signalLost: Boolean, behaviour: MonitorSignalLoss): Boolean =
    monitor && signalLost && behaviour == MonitorSignalLoss.SILENT

data class SessionConfig(
    val targetBpm: Double,
    val totalDurationMin: Double,
    val rampDurationMin: Double,
    val maxOffset: Double,
    val staticFallbackStartingBpm: Double,
    val descentPattern: DescentPattern = DescentPattern.BELL_CURVE,
    val monitor: Boolean = false,
    val monitorSignalLoss: MonitorSignalLoss = MonitorSignalLoss.HOLD,
    val pacedSignalLoss: PacedSignalLoss = PacedSignalLoss.HOLD
)

data class SessionState(
    val phase: Phase,
    val elapsedTotalMs: Long,
    val remainingTotalMs: Long,
    val elapsedRampMs: Long,
    val smoothedPulse: Double?,
    val metronomeBpm: Double?,
    val offset: Double,
    val signalLost: Boolean,
    val targetReached: Boolean,
    val startingBpm: Double?,
    val startingBpmSource: StartingBpmSource?,
    val verificationStreakCount: Int = 0,
    // Time since RUNNING began and the configured session length - exposed so the Live
    // Session progress bar and sync-window marker don't have to re-derive them (elapsedTotalMs
    // also counts the AWAITING_START wait, so it can't give a running-phase fraction).
    val runningElapsedMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val noPulse: Boolean = false,
    val monitor: Boolean = false,
    val focusPercent: Int? = null
) {
    enum class Phase { AWAITING_START, RUNNING, ENDED }
}

data class PulseSampleTick(
    val timestampMs: Long,
    val pulse: Int?,
    val metronomeBpm: Int
)

sealed class SessionEvent {
    data class StateUpdated(val state: SessionState) : SessionEvent()
    data class PulseSampleReady(val sample: PulseSampleTick) : SessionEvent()
    data class SessionEnded(
        val reason: EndReason,
        val endingBpm: Double?,
        val targetReached: Boolean,
        val highestPulse: Int?,
        val lowestPulse: Int?,
        val pulseLostAtElapsedMs: Long?,
        val focusPercent: Int? = null
    ) : SessionEvent()
}

/**
 * Pure Kotlin session state machine. Single-threaded by contract: caller must
 * confine all calls to one coroutine/thread - this class does no internal
 * synchronization.
 */
class SessionEngine(
    private val config: SessionConfig,
    private val clock: MonotonicClock
) {
    private val smoother = PulseSmoother()
    private val rateLimiter = BpmRateLimiter()
    private val signalTracker = SignalLossTracker(clock)
    private val verificationValidator = PulseReadingValidator()
    private var runningPhaseFilter: RunningPulseFilter? = null  // only instantiated for WHEN_PULSE_DETECTED

    private var sessionStartMs: Long? = null
    private var runningStartMs: Long? = null
    private var lastCarryOnTickMs: Long? = null
    private var phase = SessionState.Phase.AWAITING_START
    private var targetReached = false
    private var startingBpm: Double? = null
    private var startingBpmSource: StartingBpmSource? = null
    private var lastPulseSampleTickMs: Long? = null
    private var lastRawPulseForSample: Int? = null
    private var lastComputedOffset: Double = 0.0
    private var verificationReadings = mutableListOf<Int>()
    private var lastVerificationReadingAtMs: Long? = null
    private var highestPulse: Int? = null
    private var lowestPulse: Int? = null
    private var lastAcceptedPulseAtMs: Long? = null
    private var noPulse = false
    private var lastNoPulseTickMs: Long? = null
    // Built on the first counted reading, once the starting BPM (the plan's start) is known.
    private var focus: FocusTracker? = null

    private val listeners = mutableListOf<(SessionEvent) -> Unit>()
    fun addListener(l: (SessionEvent) -> Unit) { listeners.add(l) }
    private fun emit(e: SessionEvent) { listeners.forEach { it(e) } }

    fun start(mode: StartMode, initialPulse: Int? = null) {
        sessionStartMs = clock.nowMs()
        phase = SessionState.Phase.AWAITING_START
        if (mode == StartMode.NOW || mode == StartMode.NO_PULSE) {
            noPulse = mode == StartMode.NO_PULSE
            if (mode == StartMode.NOW && initialPulse != null) resolveStartingBpmFromLiveReading(initialPulse)
            else resolveStartingBpmFallback()
            if (noPulse) {
                lastNoPulseTickMs = sessionStartMs
                if (startingBpm == config.targetBpm) targetReached = true
            }
        }
        // StartMode.WHEN_PULSE_DETECTED: stays AWAITING_START, collecting a 3-reading
        // verification streak, with no time limit: it waits until the streak completes
        // (onPulseReading) or the user stops. The old 2-minute give-up ended the session as
        // "time up" and rang the alarm before a meter switched on later could connect.
    }

    fun onPulseReading(rawPulse: Int, atMs: Long) {
        if (phase == SessionState.Phase.ENDED) return
        if (noPulse) return
        signalTracker.onValidPulse()

        if (phase == SessionState.Phase.AWAITING_START) {
            if (signalTracker.isSignalLost()) {
                // A reading arriving while still disconnected/sensor-off cannot count
                // toward verification, even though onValidPulse() was just called above -
                // isSignalLost() still returns true here because a single valid reading
                // does not clear a disconnected/sensorOff condition (SignalLossTracker's
                // existing contract - only onReconnected()/onSensorOffChanged(false) do).
                resetVerificationStreak()
                emit(SessionEvent.StateUpdated(buildState(clock.nowMs())))
                return
            }
            handleVerificationReading(rawPulse)
            return
        }

        if (signalTracker.isSignalLost()) {
            val now = clock.nowMs()
            emit(SessionEvent.StateUpdated(buildState(now)))
            return
        }

        // Session-long deviation filtering applies ONLY to WHEN_PULSE_DETECTED sessions
        // during RUNNING (Global Constraints) - StartMode.NOW never instantiates
        // runningPhaseFilter, so this check is skipped entirely for that mode.
        val filter = runningPhaseFilter
        if (filter != null && !filter.accept(rawPulse)) {
            val now = clock.nowMs()
            emit(SessionEvent.StateUpdated(buildState(now)))
            return
        }

        lastRawPulseForSample = rawPulse
        recordHighLow(rawPulse)
        lastAcceptedPulseAtMs = clock.nowMs()
        val smoothed = smoother.update(rawPulse.toDouble())
        recomputeAndEmit(smoothed)
    }

    fun onInvalidPulse() {
        if (phase == SessionState.Phase.ENDED) return
        if (noPulse) return
        signalTracker.onInvalidPulse()
        lastRawPulseForSample = null
        resetVerificationStreak()
        emitCurrentStateIfArmed()
    }

    fun onDisconnected() {
        if (noPulse) return
        signalTracker.onDisconnected()
        lastRawPulseForSample = null
        resetVerificationStreak()
        emitCurrentStateIfArmed()
    }

    fun onReconnected() {
        if (noPulse) return
        signalTracker.onReconnected()
        emitCurrentStateIfArmed()
    }

    fun onSensorOffChanged(isOff: Boolean) {
        if (noPulse) return
        signalTracker.onSensorOffChanged(isOff)
        if (isOff) {
            lastRawPulseForSample = null
            resetVerificationStreak()
        }
        emitCurrentStateIfArmed()
    }

    fun onTick() {
        if (phase == SessionState.Phase.ENDED) return
        val start = sessionStartMs ?: return
        val now = clock.nowMs()

        if (phase == SessionState.Phase.AWAITING_START) {
            emit(SessionEvent.StateUpdated(buildState(now)))
            return
        }

        maybeFirePulseSampleTick(now)
        val runningStart = runningStartMs ?: start
        val elapsedSinceRunning = now - runningStart
        if (elapsedSinceRunning >= (config.totalDurationMin * 60_000.0).toLong()) {
            endSession(EndReason.DURATION_ELAPSED)
            return
        }

        if (noPulse) stepNoPulse(now)
        else if (!config.monitor && config.pacedSignalLoss == PacedSignalLoss.CONTINUE && signalTracker.isSignalLost()) stepCarryOn(now)
        else lastCarryOnTickMs = null
        emit(SessionEvent.StateUpdated(buildState(now)))
    }

    /** Moves the metronome one tick along the no-pulse schedule (NoPulseDescent). */
    private fun stepNoPulse(now: Long) {
        val runningStart = runningStartMs ?: return
        val last = lastNoPulseTickMs ?: now
        lastNoPulseTickMs = now
        val current = rateLimiter.currentValue() ?: return
        val params = NoPulseParams(
            startBpm = startingBpm ?: return,
            targetBpm = config.targetBpm,
            totalDurationMin = config.totalDurationMin,
            rampDurationMin = config.rampDurationMin,
            maxOffsetPerMin = config.maxOffset,
            pattern = config.descentPattern
        )
        val next = NoPulseDescent.step(current, params, (now - runningStart) / 60_000.0, (now - last) / 60_000.0)
        // seed() bypasses the 2-BPM-per-update limiter; the schedule's own cap is far below it.
        rateLimiter.seed(next)
        if (next == config.targetBpm) targetReached = true
    }

    /** While a paced session's pulse is lost, keep coming down on the planned (no-pulse) schedule from the current BPM. */
    private fun stepCarryOn(now: Long) {
        val runningStart = runningStartMs ?: return
        val last = lastCarryOnTickMs
        lastCarryOnTickMs = now
        if (last == null) return   // the loss just began: measure from here, so the first step doesn't jump
        val current = rateLimiter.currentValue() ?: return
        val params = NoPulseParams(
            startBpm = startingBpm ?: return, targetBpm = config.targetBpm,
            totalDurationMin = config.totalDurationMin, rampDurationMin = config.rampDurationMin,
            maxOffsetPerMin = config.maxOffset, pattern = config.descentPattern
        )
        rateLimiter.seed(NoPulseDescent.step(current, params, (now - runningStart) / 60_000.0, (now - last) / 60_000.0))
        // targetReached is deliberately not set here: it only latches on a live reading.
    }

    fun stop() {
        if (phase == SessionState.Phase.ENDED) return
        endSession(EndReason.USER_STOPPED)
    }

    private fun handleVerificationReading(rawPulse: Int) {
        val now = clock.nowMs()
        val last = lastVerificationReadingAtMs
        if (last != null && now - last >= STALENESS_TIMEOUT_MS) {
            resetVerificationStreak()
        }
        lastVerificationReadingAtMs = now

        if (!verificationValidator.accept(rawPulse)) {
            verificationReadings.clear()
            emit(SessionEvent.StateUpdated(buildState(now)))
            return
        }

        verificationReadings.add(rawPulse)

        if (verificationReadings.size < VERIFICATION_STREAK_COUNT) {
            emit(SessionEvent.StateUpdated(buildState(now)))
            return
        }

        // Streak complete - NOW (and only now) do the completed streak's readings count
        // toward highest/lowest, per spec 7.1's "everything is based off of those 3 valid
        // pulses" - an abandoned (never-completed) streak's readings are excluded.
        val completedStreak = verificationReadings.toList()
        completedStreak.forEach { recordHighLow(it) }
        lastAcceptedPulseAtMs = now
        val mean = completedStreak.average()
        resolveStartingBpm(mean, lastRawReading = completedStreak.last())
    }

    private fun resetVerificationStreak() {
        verificationReadings.clear()
        verificationValidator.reset()
        lastVerificationReadingAtMs = null
    }

    private fun recordHighLow(rawPulse: Int) {
        highestPulse = highestPulse?.let { maxOf(it, rawPulse) } ?: rawPulse
        lowestPulse = lowestPulse?.let { minOf(it, rawPulse) } ?: rawPulse
    }

    private fun resolveStartingBpm(meanOfThreeReadings: Double, lastRawReading: Int) {
        val clamped = if (config.monitor) meanOfThreeReadings else maxOf(meanOfThreeReadings, config.targetBpm)
        startingBpm = clamped
        startingBpmSource = StartingBpmSource.LIVE_PULSE
        rateLimiter.seed(clamped)
        smoother.seed(clamped)
        // Seed with the last RAW reading of the completed streak, not the clamped/rounded
        // startingBpm - a design-review finding: seeding with the clamped value is wrong
        // when the mean was below target and got clamped up, since the first real
        // post-RUNNING reading would then be compared against a baseline it never actually
        // was.
        val filter = RunningPulseFilter()
        filter.seed(lastRawReading)
        runningPhaseFilter = filter
        runningStartMs = clock.nowMs()
        phase = SessionState.Phase.RUNNING
        if (!config.monitor && clamped == config.targetBpm) targetReached = true
        recomputeAndEmit(clamped)
    }

    /** NOW with a meter already streaming: start from its latest reading (spec 2026-10-10 section 2). */
    private fun resolveStartingBpmFromLiveReading(pulse: Int) {
        if (phase != SessionState.Phase.AWAITING_START) return
        signalTracker.onValidPulse()
        recordHighLow(pulse)
        lastRawPulseForSample = pulse
        lastAcceptedPulseAtMs = clock.nowMs()
        val clamped = maxOf(pulse.toDouble(), config.targetBpm)
        startingBpm = clamped
        startingBpmSource = StartingBpmSource.LIVE_PULSE
        rateLimiter.seed(clamped)
        smoother.seed(clamped)
        runningStartMs = sessionStartMs
        phase = SessionState.Phase.RUNNING
        if (clamped == config.targetBpm) targetReached = true
    }

    private fun resolveStartingBpmFallback() {
        if (phase != SessionState.Phase.AWAITING_START) return
        val clamped = maxOf(config.staticFallbackStartingBpm, config.targetBpm)
        startingBpm = clamped
        startingBpmSource = StartingBpmSource.STATIC_FALLBACK
        rateLimiter.seed(clamped)
        // StartMode.NOW never uses runningPhaseFilter (Global Constraints) - left null.
        runningStartMs = sessionStartMs
        phase = SessionState.Phase.RUNNING
    }

    private fun recomputeAndEmit(smoothedPulse: Double) {
        val runningStart = runningStartMs ?: return
        val resolvedStartingBpm = startingBpm ?: return
        val now = clock.nowMs()
        val elapsedMin = (now - runningStart) / 60_000.0
        val sync = syncWindowMin(config.totalDurationMin)
        // Monitor mode: no target and no pacing - the metronome simply keeps pace with the pulse.
        val targetMetronome: Double
        if (config.monitor) {
            lastComputedOffset = 0.0
            targetMetronome = smoothedPulse
        } else {
            val rampOut = RampCalculator.calculate(
                RampInput(
                    currentPulse = smoothedPulse,
                    startingBpm = resolvedStartingBpm,
                    targetBpm = config.targetBpm,
                    rampDurationMin = config.rampDurationMin,
                    syncWindowMin = sync,
                    elapsedMin = elapsedMin,
                    maxOffset = config.maxOffset
                )
            )
            lastComputedOffset = rampOut.offset
            targetMetronome = rampOut.targetMetronomeBpm
        }
        val newMetronomeBpm = rateLimiter.update(targetMetronome)
        if (!config.monitor && !noPulse && elapsedMin > sync) {
            val tracker = focus ?: FocusTracker(
                NoPulseParams(
                    startBpm = resolvedStartingBpm, targetBpm = config.targetBpm,
                    totalDurationMin = config.totalDurationMin, rampDurationMin = config.rampDurationMin,
                    maxOffsetPerMin = config.maxOffset, pattern = config.descentPattern
                )
            ).also { focus = it }
            tracker.record(smoothedPulse, elapsedMin)
        }

        if (!config.monitor && !signalTracker.isSignalLost() && newMetronomeBpm == config.targetBpm) {
            targetReached = true
        }

        maybeFirePulseSampleTick(now)
        emit(SessionEvent.StateUpdated(buildState(now)))
    }

    private fun maybeFirePulseSampleTick(now: Long) {
        val last = lastPulseSampleTickMs
        if (last == null) {
            lastPulseSampleTickMs = now
            return
        }
        if (now - last < PULSE_SAMPLE_TICK_MS) return
        lastPulseSampleTickMs = now
        val pulseForSample = if (signalTracker.isSignalLost()) null else lastRawPulseForSample
        val bpm = rateLimiter.currentValue() ?: startingBpm ?: config.targetBpm
        emit(SessionEvent.PulseSampleReady(
            PulseSampleTick(timestampMs = now, pulse = pulseForSample, metronomeBpm = Math.round(bpm).toInt())
        ))
    }

    private fun endSession(reason: EndReason) {
        phase = SessionState.Phase.ENDED
        val endingBpm = rateLimiter.currentValue()
        val runningStart = runningStartMs
        val lostAt = if (highestPulse != null && signalTracker.isSignalLost() && runningStart != null) {
            lastAcceptedPulseAtMs?.let { it - runningStart }
        } else null
        emit(SessionEvent.SessionEnded(
            reason = reason,
            endingBpm = endingBpm,
            targetReached = targetReached,
            highestPulse = highestPulse,
            lowestPulse = lowestPulse,
            pulseLostAtElapsedMs = lostAt,
            focusPercent = focusPercent()
        ))
    }

    /** Focus is only meaningful for paced (non-monitor, pulse-driven) sessions. */
    private fun focusPercent(): Int? = if (config.monitor || noPulse) null else focus?.percent

    private fun emitCurrentStateIfArmed() {
        if (phase == SessionState.Phase.ENDED) return
        val now = clock.nowMs()
        if (sessionStartMs == null) return
        emit(SessionEvent.StateUpdated(buildState(now)))
    }

    private fun buildState(now: Long): SessionState {
        val start = sessionStartMs ?: now
        val elapsedTotalMs = now - start
        val runningStart = runningStartMs
        val totalMs = (config.totalDurationMin * 60_000.0).toLong()
        val elapsedForDuration = if (runningStart != null) now - runningStart else 0L
        val elapsedRampMs = if (runningStart != null) {
            minOf(now - runningStart, (config.rampDurationMin * 60_000.0).toLong())
        } else 0L
        return SessionState(
            phase = phase,
            elapsedTotalMs = elapsedTotalMs,
            remainingTotalMs = maxOf(totalMs - elapsedForDuration, 0L),
            elapsedRampMs = elapsedRampMs,
            smoothedPulse = smoother.currentValue(),
            metronomeBpm = rateLimiter.currentValue(),
            offset = lastComputedOffset,
            signalLost = !noPulse && signalTracker.isSignalLost(),
            targetReached = targetReached,
            startingBpm = startingBpm,
            startingBpmSource = startingBpmSource,
            verificationStreakCount = verificationReadings.size,
            runningElapsedMs = elapsedForDuration,
            totalDurationMs = totalMs,
            noPulse = noPulse,
            monitor = config.monitor,
            focusPercent = focusPercent()
        )
    }
}
