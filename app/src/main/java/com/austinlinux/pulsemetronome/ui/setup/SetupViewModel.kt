package com.austinlinux.pulsemetronome.ui.setup

import androidx.lifecycle.ViewModel
import com.austinlinux.pulsemetronome.ble.BleModule
import com.austinlinux.pulsemetronome.ble.DeviceCandidate
import com.austinlinux.pulsemetronome.ble.ScanOutcome
import com.austinlinux.pulsemetronome.engine.DescentPattern
import com.austinlinux.pulsemetronome.engine.NoPulseDescent
import com.austinlinux.pulsemetronome.engine.StartMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.lifecycle.viewModelScope

data class SetupValidationResult(val isValid: Boolean, val errors: List<String>)

fun validateSetup(targetBpm: Int?, totalDurationMin: Int?, rampDurationMin: Int?): SetupValidationResult {
    val errors = mutableListOf<String>()
    if (targetBpm == null || targetBpm !in 30..200) errors += "Target BPM must be 30-200"
    if (totalDurationMin == null || totalDurationMin <= 0) errors += "Total duration must be > 0"
    if (rampDurationMin == null || rampDurationMin <= 0) errors += "Ramp duration must be > 0"
    if (totalDurationMin != null && rampDurationMin != null && rampDurationMin > totalDurationMin) {
        errors += "Ramp duration cannot exceed total duration"
    }
    return SetupValidationResult(isValid = errors.isEmpty(), errors = errors)
}

/** The ramp's ceiling: the total duration (typing past it shows the warning; + at the ceiling raises both, see [rampBump]). */
fun rampStepperMax(totalDurationMin: Int?): Int = (totalDurationMin ?: 600).coerceIn(1, 600)

data class RampBump(val ramp: Int, val total: Int?)

/**
 * Ramp +/- result: the ramp stops at the total first, and once it's there (or above a typed-in
 * smaller total) + raises both together, mirroring how - on Total already brings the ramp down.
 */
fun rampBump(ramp: Int?, total: Int?, delta: Int): RampBump {
    val stepped = ((ramp ?: 1) + delta).coerceIn(1, 600)
    if (total == null) return RampBump(stepped, null)
    return if (delta > 0 && (ramp ?: 1) >= total) RampBump(stepped, stepped)
    else RampBump(minOf(stepped, rampStepperMax(total)), total)
}

/** Which start mode the Start button means: no meter and no waiting means a no-pulse, schedule-driven session. */
fun resolveStartMode(waitForPulse: Boolean, meterConnected: Boolean, monitor: Boolean = false): StartMode = when {
    monitor -> StartMode.WHEN_PULSE_DETECTED
    waitForPulse -> StartMode.WHEN_PULSE_DETECTED
    meterConnected -> StartMode.NOW
    else -> StartMode.NO_PULSE
}


data class SetupDisplay(
    val showStartingBpm: Boolean, val startLabel: String,
    val showTargetAndRamp: Boolean = true, val showWaitForPulse: Boolean = true
)

/** What Setup shows for the current mode: Starting BPM and "Start without meter" only for a no-pulse start. */
fun setupDisplay(waitForPulse: Boolean, meterConnected: Boolean, monitor: Boolean = false): SetupDisplay =
    if (monitor) SetupDisplay(false, "Start monitoring", showTargetAndRamp = false, showWaitForPulse = false)
    else when (resolveStartMode(waitForPulse, meterConnected)) {
        StartMode.NO_PULSE -> SetupDisplay(true, "Start without meter")
        StartMode.NOW -> SetupDisplay(false, "Start")
        StartMode.WHEN_PULSE_DETECTED -> SetupDisplay(false, "Start when pulse is steady")
    }

/** Start is allowed without Bluetooth permission only for a no-pulse start, which needs a valid Starting BPM (equal to target is allowed: a steady session). */
fun startEnabled(
    formValid: Boolean, waitForPulse: Boolean, meterConnected: Boolean, bleGranted: Boolean,
    scanInProgress: Boolean, startingBpm: Int?, targetBpm: Int?,
    monitor: Boolean = false, totalDurationMin: Int? = null
): Boolean {
    // Monitor has no target or ramp, so only Bluetooth and a valid total matter (formValid also checks the hidden fields).
    if (monitor) return bleGranted && totalDurationMin != null && totalDurationMin > 0
    if (!formValid) return false
    return when (resolveStartMode(waitForPulse, meterConnected)) {
        StartMode.NO_PULSE -> !scanInProgress && startingBpm != null && startingBpm in 30..200
        StartMode.NOW -> bleGranted && !scanInProgress
        StartMode.WHEN_PULSE_DETECTED -> bleGranted
    }
}

/**
 * Setup's "may not reach your target" note. Only no-pulse sessions follow a schedule, so only
 * they can be judged up front: shown when the chosen pattern's fastest needed drop exceeds Max
 * offset per minute (the time-based rule the user chose on 2026-09-24).
 */
fun showMayNotReachNote(
    waitForPulse: Boolean, meterConnected: Boolean, fallbackBpm: Int, targetBpm: Int?,
    totalDurationMin: Int?, rampDurationMin: Int?, maxOffset: Int, pattern: DescentPattern
): Boolean {
    if (resolveStartMode(waitForPulse, meterConnected) != StartMode.NO_PULSE) return false
    if (targetBpm == null || totalDurationMin == null || rampDurationMin == null) return false
    return NoPulseDescent.mayNotReachTarget(
        fallbackBpm.toDouble(), targetBpm.toDouble(), totalDurationMin.toDouble(),
        rampDurationMin.toDouble(), maxOffset.toDouble(), pattern
    )
}

/**
 * True when "Start Now" (StartMode.NOW) would result in ZERO pacing for the whole session -
 * a real bug found in review, not a stretch edge case. Under StartMode.NOW, starting_bpm is
 * resolved from the static fallback BPM (clamped >= target_bpm in
 * SessionEngine.resolveStartingBpmFallback, Task 9), and RampCalculator's rate (Task 6) is
 * computed once from (starting_bpm - target_bpm) / ramp_duration. If the fallback is already
 * at or below target BEFORE that clamp applies, the clamp forces starting_bpm = target_bpm,
 * giving rate = 0 - the metronome would then just track the live pulse with no offset for the
 * entire session, silently defeating the whole point of "Start Now" pacing.
 *
 * Unlike isTargetPotentiallyUnreachable (informational only), this DISABLES the "Start Now"
 * button entirely in Task 18's Setup screen - there is no sensible session to start in this
 * configuration, not just a session that might not reach target.
 */
fun isStartNowPacingDisabled(staticFallbackStartingBpm: Int, targetBpm: Int): Boolean {
    return staticFallbackStartingBpm <= targetBpm
}

data class SetupUiState(
    val targetBpm: Int? = 60,
    val totalDurationMin: Int? = 30,
    val rampDurationMin: Int? = 30,
    val rampEditedByUser: Boolean = false,
    val scanOutcome: ScanOutcome? = null,
    // Shown for 8 s after two scans in a row (within 60 s) gave no choice: the wanted meter may need a restart.
    val restartHint: Boolean = false,
    // Tracks an in-flight scan so the Scan button can show visible feedback instead of
    // looking like a no-op for the full 4s scan window - a real gap found while debugging
    // "pressing Scan doesn't appear to be doing anything" (no UI state existed for
    // "scanning in progress" or a completed ScanOutcome.NoneFound at all).
    val scanning: Boolean = false,
    // New: replaces the old "Start When Pulse Detected" second button. Unchecked (default)
    // -> resolveStartMode at Start-tap: NOW with a meter connected, NO_PULSE without one. Checked -> StartMode.WHEN_PULSE_DETECTED, and Start
    // becomes enabled regardless of BLE connection state (see SetupScreen).
    val waitForPulse: Boolean = false,
    val monitor: Boolean = false
) {
    val validation: SetupValidationResult get() = validateSetup(targetBpm, totalDurationMin, rampDurationMin)
}

/**
 * Wraps form state as a StateFlow<SetupUiState>, re-running validateSetup on every change,
 * and reactively clamps rampDurationMin to totalDurationMin when totalDurationMin is lowered
 * below the current ramp value. Ramp duration also defaults to match total duration until the
 * user explicitly edits it (rampEditedByUser tracks that), per the plan's Task 19 note.
 */
class SetupViewModel(
    private val bleModule: BleModule,
    private val valuesStore: SetupValuesStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()
    private var editedBeforeRestore = false

    init {
        viewModelScope.launch {
            val saved = valuesStore.load()
            // Don't clobber anything the user typed in the moment before the load finished.
            if (!editedBeforeRestore) _uiState.value = _uiState.value.withRestored(saved)
        }
        viewModelScope.launch {
            bleModule.scanResults.collect { outcome ->
                _uiState.value = _uiState.value.copy(scanOutcome = outcome, scanning = false)
                if (awaitingUserScan) {
                    awaitingUserScan = false
                    if (noChoiceScans.record(noChoice = outcome !is ScanOutcome.MultipleFound, nowMs = SystemClock.elapsedRealtime())) showRestartHint()
                }
            }
        }
        viewModelScope.launch {
            bleModule.connectionState.collect { connected ->
                if (connected) _uiState.value = _uiState.value.copy(scanning = false)
            }
        }
    }

    fun onTargetBpmChanged(value: Int?) {
        updateForm(_uiState.value.copy(targetBpm = value))
    }

    fun onTotalDurationChanged(value: Int?) {
        val current = _uiState.value
        val newRamp = if (!current.rampEditedByUser && value != null) value else current.rampDurationMin
        val clampedRamp = if (value != null && newRamp != null) minOf(newRamp, value) else newRamp
        updateForm(current.copy(totalDurationMin = value, rampDurationMin = clampedRamp))
    }

    fun onRampDurationChanged(value: Int?) {
        updateForm(_uiState.value.copy(rampDurationMin = value, rampEditedByUser = true))
    }

    /** The Ramp +/- buttons; see [rampBump]. A ramp that still equals the total keeps following it. */
    fun onRampBumped(delta: Int) {
        val current = _uiState.value
        val r = rampBump(current.rampDurationMin, current.totalDurationMin, delta)
        updateForm(current.copy(
            rampDurationMin = r.ramp, totalDurationMin = r.total,
            rampEditedByUser = current.rampEditedByUser || r.ramp != r.total
        ))
    }

    fun onWaitForPulseChanged(value: Boolean) {
        updateForm(_uiState.value.copy(waitForPulse = value))
    }

    fun onMonitorChanged(value: Boolean) {
        updateForm(_uiState.value.copy(monitor = value))
    }

    /** Applies a form edit and remembers it for the next launch (skipped while a field is empty). */
    private fun updateForm(newState: SetupUiState) {
        editedBeforeRestore = true
        _uiState.value = newState
        newState.toSavedValues()?.let { viewModelScope.launch { valuesStore.save(it) } }
    }

    private val noChoiceScans = NoChoiceStreak()
    private var awaitingUserScan = false
    private var restartHintJob: Job? = null

    private fun showRestartHint() {
        restartHintJob?.cancel()
        _uiState.value = _uiState.value.copy(restartHint = true)
        restartHintJob = viewModelScope.launch { delay(8_000); _uiState.value = _uiState.value.copy(restartHint = false) }
    }

    fun onScanRequested() {
        awaitingUserScan = true
        _uiState.value = _uiState.value.copy(scanning = true, scanOutcome = null)
        bleModule.rescanAndChoose()
    }

    fun onDevicePicked(candidate: DeviceCandidate) {
        bleModule.connectTo(candidate.address, waitForAdvertising = candidate.needsRestart)
        _uiState.value = _uiState.value.copy(scanOutcome = null)
    }

    fun dismissDevicePicker() {
        _uiState.value = _uiState.value.copy(scanOutcome = null)
    }
}
