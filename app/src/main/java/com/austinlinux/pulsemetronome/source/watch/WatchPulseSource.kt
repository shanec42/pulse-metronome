package com.austinlinux.pulsemetronome.source.watch

import android.os.SystemClock
import com.austinlinux.pulsemetronome.ble.TimedPulse
import com.austinlinux.pulsemetronome.ble.protocol.PulseReading
import com.austinlinux.pulsemetronome.source.PulseSource
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import com.austinlinux.pulsemetronome.wearlink.WatchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val HEARTBEAT_MS = 20_000L
private const val SILENCE_MS = 15_000L

/** Pulse source backed by a watch session that [WatchLink] already started; Setup calls [attach] first. */
class WatchPulseSource(
    private val link: WatchLink,
    private val transport: WatchTransport,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }
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

    private var sessionId: String? = null
    private var node: WatchNode? = null
    private var jobs = emptyList<Job>()
    // Bumped on every message from the watch; the watchdog restarts its 15 s wait on each bump.
    private val activity = MutableStateFlow(0)

    /** Binds to a session the watch has acknowledged and starts listening, heartbeating and watching for silence. */
    fun attach(started: StartResult.Started) {
        jobs.forEach { it.cancel() }
        sessionId = started.sessionId
        node = started.node
        _deviceName.value = started.node.displayName
        _connected.value = false
        _sensorOff.value = false
        _latest.value = null
        val sid = started.sessionId
        jobs = listOf(
            // UNDISPATCHED so the subscription exists before attach returns and no early sample is missed.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.incoming.collect { (from, m) -> if (from == started.node.id && m.sessionId == sid) handle(m) }
            },
            scope.launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    transport.send(started.node.id, WatchMessage.Heartbeat(sid))
                }
            },
            scope.launch {
                activity.collectLatest {
                    delay(SILENCE_MS)
                    if (_connected.value) markDisconnected()
                }
            }
        )
    }

    private fun handle(m: WatchMessage) {
        when (m) {
            is WatchMessage.Sample -> {
                alive()
                _sensorOff.value = false
                _readings.tryEmit(PulseReading.Valid(m.bpm, null, null, nowMs()))
                _latest.value = TimedPulse(m.bpm, elapsedMs())
            }
            is WatchMessage.Status -> {
                alive()
                if (m.status == WatchStatus.CONTACT_LOST) {
                    _sensorOff.value = true
                    _latest.value = null
                    _readings.tryEmit(PulseReading.NoSignal(nowMs()))
                } else {
                    _sensorOff.value = false
                }
            }
            else -> Unit
        }
    }

    private fun alive() {
        if (!_connected.value) {
            _connected.value = true
            _readings.tryEmit(PulseReading.Connected)
        }
        activity.value++
    }

    private fun markDisconnected() {
        _connected.value = false
        _latest.value = null
        _readings.tryEmit(PulseReading.Disconnected)
    }

    // The handshake already happened in attach(), so there is nothing to start or retry.
    override fun beginSession() = Unit

    override fun retryIfIdle() = Unit

    override fun endSession() {
        val sid = sessionId ?: return
        sessionId = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _connected.value = false
        _latest.value = null
        scope.launch { link.stop(sid) }
    }
}
