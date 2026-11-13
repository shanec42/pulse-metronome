package com.austinlinux.pulsemetronome.source.fitbit

import android.os.SystemClock
import com.austinlinux.pulsemetronome.ble.TimedPulse
import com.austinlinux.pulsemetronome.ble.protocol.PulseReading
import com.austinlinux.pulsemetronome.debug.DebugCapture
import com.austinlinux.pulsemetronome.source.PulseSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** No request for this long while connected counts as the companion going away. */
const val FITBIT_SILENCE_MS = 15_000L

/** How long the listener stays open after its last holder lets go, so Setup can hand off to a session. */
const val FITBIT_LINGER_MS = 5_000L

/** Holder name Setup uses while the Fitbit source is selected. */
const val HOLD_SETUP = "setup"

/** Holder name a running session uses. */
const val HOLD_SESSION = "session"

private const val DEVICE_NAME = "Fitbit · HR Service"

/** What the Fitbit source is doing, for Setup to show. */
sealed interface FitbitStatus {
    object Off : FitbitStatus
    object Waiting : FitbitStatus
    data class Receiving(val bpm: Int) : FitbitStatus
    object NoReading : FitbitStatus
    object PortBusy : FitbitStatus
}

/** Pulse source fed by the HR Service companion's loopback POSTs; the listener is open while anyone [hold]s it. */
class FitbitPulseSource(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val elapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    listenerFactory: ((HrRequest) -> Unit) -> FitbitListener
) : PulseSource {
    private val _readings = MutableSharedFlow<PulseReading>(extraBufferCapacity = 64)
    override val readings: Flow<PulseReading> = _readings
    private val _sensorOff = MutableStateFlow(false)
    override val sensorOffState: Flow<Boolean> = _sensorOff.asStateFlow()
    private val _connected = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connected.asStateFlow()
    private val _deviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _deviceName.asStateFlow()
    override val batteryPercent: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()
    private val _latest = MutableStateFlow<TimedPulse?>(null)
    override val latestValidPulse: StateFlow<TimedPulse?> = _latest.asStateFlow()
    private val _status = MutableStateFlow<FitbitStatus>(FitbitStatus.Off)
    val status: StateFlow<FitbitStatus> = _status.asStateFlow()

    // Callers arrive on main, Dispatchers.Default and the listener's IO thread; everything below is guarded by it.
    private val lock = Any()
    private val listener = listenerFactory(::onRequest)
    private val holders = mutableSetOf<String>()
    private var lingerJob: Job? = null
    private var silenceJob: Job? = null
    // A timer body may already be past its delay and waiting for the lock when it is superseded,
    // so cancelling its Job is not enough: each body checks that its generation is still current.
    private var lingerGen = 0L
    private var silenceGen = 0L
    private var lastRequestElapsed = -1L

    /** Registers [owner] as needing the listener open, starting it (or retrying a busy port) as needed. */
    fun hold(owner: String): Unit = synchronized(lock) {
        if (!holders.add(owner)) return
        lingerGen++
        lingerJob?.cancel()
        lingerJob = null
        ensureListening()
    }

    /** Drops [owner]; when nobody holds the source any more, closes the listener after [FITBIT_LINGER_MS]. */
    fun release(owner: String): Unit = synchronized(lock) {
        if (!holders.remove(owner) || holders.isNotEmpty()) return
        val gen = ++lingerGen
        lingerJob = scope.launch {
            delay(FITBIT_LINGER_MS)
            synchronized(lock) { if (gen == lingerGen) close() }
        }
    }

    /** Listener callback for each BPM the companion posts. */
    internal fun onRequest(req: HrRequest): Unit = synchronized(lock) {
        if (req !is HrRequest.Bpm) return
        val now = elapsedMs()
        val gap = if (lastRequestElapsed < 0) -1 else now - lastRequestElapsed
        lastRequestElapsed = now
        DebugCapture.event("FITBIT") { "bpm=${req.bpm} gapMs=$gap" }
        // A request already in flight when the listener closed must not reopen the connection.
        if (_status.value == FitbitStatus.Off) return
        if (!_connected.value) {
            _connected.value = true
            _deviceName.value = DEVICE_NAME
            _readings.tryEmit(PulseReading.Connected)
        }
        if (req.bpm > 0) {
            _sensorOff.value = false
            _readings.tryEmit(PulseReading.Valid(req.bpm, null, null, nowMs()))
            _latest.value = TimedPulse(req.bpm, now)
            _status.value = FitbitStatus.Receiving(req.bpm)
        } else {
            _sensorOff.value = true
            _latest.value = null
            _readings.tryEmit(PulseReading.NoSignal(nowMs()))
            _status.value = FitbitStatus.NoReading
        }
        armSilence()
    }

    override fun beginSession() = hold(HOLD_SESSION)

    override fun endSession() = release(HOLD_SESSION)

    /** Restarts the listener if the port was busy or its accept loop died while someone still holds it. */
    override fun retryIfIdle(): Unit = synchronized(lock) {
        if (_status.value == FitbitStatus.PortBusy || (holders.isNotEmpty() && !listener.isRunning)) ensureListening()
    }

    /** Starts the listener unless it is already running; call under [lock]. */
    private fun ensureListening() {
        if (listener.isRunning) return
        val result = listener.start()
        DebugCapture.event("FITBIT") { "listener start: $result" }
        _status.value = when (result) {
            StartResult.PORT_BUSY -> FitbitStatus.PortBusy
            // A restart after the accept loop died keeps a live Receiving/NoReading until the watchdog decides.
            else -> _status.value.takeUnless { it == FitbitStatus.Off || it == FitbitStatus.PortBusy } ?: FitbitStatus.Waiting
        }
    }

    /** (Re)starts the silence watchdog; call under [lock]. */
    private fun armSilence() {
        val gen = ++silenceGen
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(FITBIT_SILENCE_MS)
            synchronized(lock) { if (gen == silenceGen && _connected.value) markSilent() }
        }
    }

    private fun markSilent() {
        DebugCapture.event("FITBIT") { "silent for ${FITBIT_SILENCE_MS}ms" }
        clearConnection()
        _readings.tryEmit(PulseReading.Disconnected)
        _status.value = FitbitStatus.Waiting
    }

    /** Stops the listener and resets every flow; call under [lock]. */
    private fun close() {
        listener.stop()
        DebugCapture.event("FITBIT") { "listener stop" }
        lastRequestElapsed = -1
        silenceGen++
        silenceJob?.cancel()
        silenceJob = null
        lingerJob = null
        clearConnection()
        _status.value = FitbitStatus.Off
    }

    private fun clearConnection() {
        _connected.value = false
        _deviceName.value = null
        _latest.value = null
        // The next session collects sensorOffState at once, so a stale true would read as a sensor-off.
        _sensorOff.value = false
    }
}
