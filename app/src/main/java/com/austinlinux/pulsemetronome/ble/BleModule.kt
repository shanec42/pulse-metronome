package com.austinlinux.pulsemetronome.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.os.PowerManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.austinlinux.pulsemetronome.ble.protocol.FrameDecoder
import com.austinlinux.pulsemetronome.ble.protocol.GattOp
import com.austinlinux.pulsemetronome.ble.protocol.GattOpQueue
import com.austinlinux.pulsemetronome.ble.protocol.OximeterEvent
import com.austinlinux.pulsemetronome.ble.protocol.OximeterProtocol
import com.austinlinux.pulsemetronome.ble.protocol.ProtocolId
import com.austinlinux.pulsemetronome.ble.protocol.ProtocolRegistry
import com.austinlinux.pulsemetronome.ble.protocol.PulseReading
import com.austinlinux.pulsemetronome.ble.protocol.startupOps
import com.austinlinux.pulsemetronome.ble.protocol.toPulseReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

private const val TAG = "BleModule"
private const val RAW_TAG = "OximeterRaw"

// Advertisement matching now lives in ProtocolRegistry. Background kept from the old
// name-prefix filter: the plan's reference material (RAG/FS20F/README.md) documented this
// device as advertising itself as "VTM 20F", but the human's actual physical unit (model
// KS-60FW, confirmed by an independent nRF-Connect-style BLE scan matching the MAC address our
// own scan also found) advertises as "KS-60FW <NNNN>" instead - matched by prefix rather than
// exact string since the trailing " <NNNN>" suffix looks like a per-unit serial/session number.
internal const val SCAN_WINDOW_MS = 4_000L
// A real bug found while debugging repeat scan failures: connectGatt(autoConnect = true)
// against a remembered address is a PASSIVE connect that can hang indefinitely with zero
// callback if the device isn't in an active-advertising window at that exact moment -
// reproduced directly (no onConnectionStateChange at all after 60+ seconds). This timeout
// bounds that wait; if it fires, the pending attempt is cancelled and a fresh active scan
// runs instead, which has connected reliably and quickly every time in practice.
private const val REMEMBERED_ADDRESS_CONNECT_TIMEOUT_MS = 5_000L
// How long close() waits for queued stop commands before closing the GATT anyway.
private const val CLOSE_STOP_COMMANDS_TIMEOUT_MS = 300L
// A write-without-response may never get onCharacteristicWrite; don't let it wedge the queue.
private const val NO_RESPONSE_WRITE_TIMEOUT_MS = 200L
// Any other op whose callback never arrives is given up on after this, so the queue can't stall.
private const val OP_TIMEOUT_MS = 3_000L
// A refused writeDescriptor/writeCharacteristic (stack busy) is retried once after this delay.
private const val OP_RETRY_DELAY_MS = 50L
private const val MAX_DISCOVERY_FAILURES = 3
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

/** Pause between Rescan closing a meter and its scan, so a meter that comes back by itself is heard. */
private const val RESCAN_SETTLE_MS = 2_000L

data class DeviceCandidate(
    val address: String,
    val rssi: Int,
    val name: String? = null,
    val protocol: ProtocolId? = null,
    val tested: Boolean = true,
    // The meter Rescan just closed but the scan couldn't hear: some (the KS-60FW) stop advertising
    // after a disconnect until switched off and on, so it's offered with that note.
    val needsRestart: Boolean = false
)

sealed class ScanOutcome {
    object NoneFound : ScanOutcome()
    data class MultipleFound(val candidates: List<DeviceCandidate>) : ScanOutcome()
    /** A single match was connected straight away (Setup counts these toward the meter-restart hint). */
    data class AutoConnected(val address: String) : ScanOutcome()
    // Single-match case does not appear here - it auto-connects without a ScanOutcome event
    // (the UI finds out via readings/connection state instead, same as before).
}

data class TimedPulse(val bpm: Int, val atElapsedMs: Long)

class BleModule(
    private val context: Context,
    private val addressStore: DeviceAddressStore
) {
    private val _latestValidPulse = MutableStateFlow<TimedPulse?>(null)
    /** Latest valid reading and when it arrived; cleared on disconnect or no-signal. */
    val latestValidPulse: StateFlow<TimedPulse?> = _latestValidPulse

    private fun publishReading(reading: PulseReading) {
        _readings.tryEmit(reading)
        when (reading) {
            is PulseReading.Valid -> _latestValidPulse.value = TimedPulse(reading.pulseRate, SystemClock.elapsedRealtime())
            is PulseReading.NoSignal, PulseReading.Disconnected -> _latestValidPulse.value = null
            else -> Unit
        }
    }

    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // Threading: ops, protocol, keep-alive, op timers and closing are only touched on
    // mainHandler. GATT callbacks arrive on binder threads, so they post to mainHandler before
    // touching any of it - one thread instead of locks, because the queue's correctness
    // depends on enqueue/complete/run happening as one step, which a lock around ops alone
    // wouldn't give. decoder is the one exception: onCharacteristicChanged decodes on the
    // binder thread (no hop per notification), reading the @Volatile field once.
    private var protocol: OximeterProtocol? = null
    @Volatile private var decoder: FrameDecoder? = null
    private val ops = GattOpQueue()
    private var keepAliveRunnable: Runnable? = null
    // Timeout (or pending retry) for the in-flight op; at most one, since one op is in flight.
    private var opTimer: Runnable? = null
    private var closing = false
    // The device this connect attempt was aimed at. The DISCONNECTED reconnect prefers it over
    // the remembered address, since remember() only happens after a protocol match: without
    // this, a failed discovery on a newly picked device would reconnect to the old one.
    // @Volatile because DISCONNECTED reads it on a binder thread.
    @Volatile private var targetAddress: String? = null
    // Consecutive failed discoveries; at MAX_DISCOVERY_FAILURES auto-reconnect stops so a stale
    // GATT cache (e.g. status 129 every time) can't loop disconnect/reconnect forever.
    private var discoveryFailures = 0

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    /** Last battery level the connected oximeter reported; null when unknown or disconnected. */
    val batteryPercent: StateFlow<Int?> = _batteryPercent

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    /** Advertised name of the connected oximeter; null when disconnected. */
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName
    // Every connect path assigns this field, so its setter is the one place that tracks how
    // long the current connect attempt has been pending (cleared again once connected).
    // @Volatile: read and written from binder, main and Default threads.
    @Volatile private var gatt: BluetoothGatt? = null
        set(value) {
            field = value
            _connectingSince.value = if (value != null && !_connectionState.value) SystemClock.elapsedRealtime() else null
        }
    @Volatile private var shouldReconnect = true
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _readings = MutableSharedFlow<PulseReading>(extraBufferCapacity = 64)
    val readings: Flow<PulseReading> = _readings

    private val _sensorOffState = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    val sensorOffState: Flow<Boolean> = _sensorOffState

    private val _scanResults = MutableSharedFlow<ScanOutcome>(extraBufferCapacity = 4)
    val scanResults: Flow<ScanOutcome> = _scanResults

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _scanInProgressSince = MutableStateFlow<Long?>(null)
    /** elapsedRealtime() at which the current scan window opened; null when no scan is running. */
    val scanInProgressSince: StateFlow<Long?> = _scanInProgressSince

    private val _connectingSince = MutableStateFlow<Long?>(null)
    /** elapsedRealtime() at which the pending connect attempt began; null when connected or idle. */
    val connectingSince: StateFlow<Long?> = _connectingSince

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // Temporarily disabled per the human's explicit instruction while debugging BLE
    // connection issues: connectGatt(autoConnect = true) against a remembered address can
    // hang indefinitely with zero callback (reproduced directly - see
    // REMEMBERED_ADDRESS_CONNECT_TIMEOUT_MS's comment). A timeout-and-fallback-to-scan is
    // implemented below and ready to use, but stays off until confirmed solid - re-enable by
    // flipping this back to true once the timeout path has been verified reliable on-device.
    private val useRememberedAddressFastPath = false

    // scanAndConnect(): FIRST checks connectionState.value - if already true (a live GATT
    // connection exists), only resets shouldReconnect = true (undoing a prior session's
    // stopReconnecting(), so THIS session's disconnects still trigger reconnect attempts) and
    // returns immediately - no scan, no new connectGatt call. This is required so that
    // SessionForegroundService (Task 16) can call this unconditionally on every session start
    // without risking a second concurrent BluetoothGatt client against an already-connected
    // device (which would corrupt the decoder's buffered state with duplicate notification
    // streams) or a fresh scan finding and connecting to a DIFFERENT physical device.
    //
    // Only when NOT currently connected does it proceed: sets shouldReconnect = true, then (if
    // useRememberedAddressFastPath) tries addressStore.getRemembered() via
    // connectGatt(autoConnect = true) first, bounded by REMEMBERED_ADDRESS_CONNECT_TIMEOUT_MS -
    // if that times out (or the fast path is disabled, or there's no remembered address),
    // falls back to a fresh scan filtered by the registered protocols.
    fun scanAndConnect(userChoosing: Boolean = false, closedMeter: DeviceCandidate? = null) {
        Log.d(TAG, "scanAndConnect() called, connectionState=${_connectionState.value}")
        if (_connectionState.value) {
            shouldReconnect = true
            return
        }
        shouldReconnect = true
        discoveryFailures = 0

        val remembered = addressStore.getRemembered()
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "scanAndConnect(): no BluetoothAdapter available (BLE unsupported or adapter null)")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "scanAndConnect(): Bluetooth adapter is present but disabled")
            return
        }
        if (useRememberedAddressFastPath && remembered != null) {
            Log.d(TAG, "scanAndConnect(): remembered address=$remembered, attempting direct connect (bounded by ${REMEMBERED_ADDRESS_CONNECT_TIMEOUT_MS}ms timeout)")
            val device = adapter.getRemoteDevice(remembered)
            targetAddress = remembered
            val attemptGatt = device.connectGatt(context, true, gattCallback)
            gatt = attemptGatt
            mainHandler.postDelayed({
                // If we're still not connected by the timeout, this specific attempt never
                // got a callback - cancel it and fall back to an active scan rather than
                // leaving the user stuck on a silently-hanging connect with no feedback.
                if (!_connectionState.value && gatt === attemptGatt) {
                    Log.w(TAG, "scanAndConnect(): remembered-address connect timed out, falling back to scan")
                    attemptGatt.close()
                    gatt = null
                    startScan(userChoosing, closedMeter)
                }
            }, REMEMBERED_ADDRESS_CONNECT_TIMEOUT_MS)
        } else {
            Log.d(TAG, "scanAndConnect(): starting fresh scan (remembered address ${if (remembered == null) "absent" else "present but fast path disabled"})")
            startScan(userChoosing, closedMeter)
        }
    }

    /** Safe-to-call-repeatedly variant of scanAndConnect(), for a caller that wants to retry
     *  on a timer (SessionForegroundService's auto-rescan while WHEN_PULSE_DETECTED is
     *  AWAITING_START, bell-curve pacing redesign). Unlike scanAndConnect(), which only
     *  no-ops when ALREADY connected, this also no-ops while a connection attempt is already
     *  in flight (gatt != null but connectionState is still false) - closing that pending
     *  attempt and starting a second one would risk two concurrent BluetoothGatt clients
     *  feeding the same decoder (a real bug found in design review). */
    fun retryConnectIfIdle() {
        if (_connectionState.value) return  // already connected - scanAndConnect() would no-op anyway
        if (gatt != null) return  // a connect attempt is already pending - do not start a second one
        scanAndConnect()
    }

    /** User-initiated rescan from the waiting screen; abandons a stuck pending connect. */
    fun manualRescan() {
        when (manualRescanAction(_connectionState.value, _scanInProgressSince.value != null, gatt != null)) {
            ManualRescanAction.NO_OP -> return
            ManualRescanAction.CLOSE_THEN_SCAN -> {
                Log.d(TAG, "manualRescan(): closing pending connect attempt before rescanning")
                gatt?.close()
                gatt = null
                scanAndConnect()
            }
            ManualRescanAction.SCAN -> scanAndConnect()
        }
    }

    /** Called by the UI (Task 18) after the user picks one of a ScanOutcome.MultipleFound
     *  list's candidates. Connects directly to that address via connectGatt. */
    fun connectTo(address: String, waitForAdvertising: Boolean = false) {
        val adapter = bluetoothAdapter ?: return
        val device = adapter.getRemoteDevice(address)
        targetAddress = address
        discoveryFailures = 0
        // Re-arm: a picked device must reconnect on drop even after an earlier stopReconnecting().
        shouldReconnect = true
        // Direct connect: the device was just seen advertising, so don't use autoConnect's slow
        // background wait (it left post-scan connects pending indefinitely on the KS-60FW).
        // A meter that needs a restart isn't advertising yet: autoConnect waits in the background
        // and completes once it's switched back on.
        gatt = device.connectGatt(context, waitForAdvertising, gattCallback)
    }

    fun stopReconnecting() { shouldReconnect = false }

    private var afterClose: (() -> Unit)? = null

    /**
     * Setup's Scan / Rescan: the user wants to choose. Drops any connected or pending meter
     * first (with its stop commands), then scans; two or more found always shows the picker.
     */
    fun rescanAndChoose() = onMain {
        // Passed with the scan rather than kept as a flag, so a scan that never starts (Bluetooth
        // off, scan failure) can't leave a later automatic reconnect asking a user who isn't there.
        if (_connectionState.value || gatt != null) {
            val address = targetAddress ?: gatt?.device?.address
            val closed = if (_connectionState.value && address != null)
                DeviceCandidate(address, 0, _connectedDeviceName.value, protocol?.id, protocol?.tested ?: true)
            else null
            // A closed meter can take a few seconds to advertise again (the FS20F does); scanning at
            // once missed it and wrongly asked for a restart. The KS-60FW stays silent regardless.
            afterClose = { mainHandler.postDelayed({ scanAndConnect(userChoosing = true, closedMeter = closed) }, RESCAN_SETTLE_MS) }
            close()
        } else {
            scanAndConnect(userChoosing = true)
        }
    }

    /** Sends the protocol's stop commands (if any), then closes the GATT; used by rescanAndChoose(). */
    fun close() = onMain {
        shouldReconnect = false
        targetAddress = null
        closing = true
        cancelKeepAlive()
        val g = gatt
        val p = protocol
        val stops = p?.stopCommands().orEmpty()
        val writeChar = p?.writeCharacteristic
        if (g != null && _connectionState.value && stops.isNotEmpty() && writeChar != null) {
            Log.d(TAG, "close(): sending ${stops.size} stop command(s) before closing")
            stops.forEach { runOp(ops.enqueue(GattOp.Write(writeChar, it)), g) }
            // A synchronous failure may already have drained the queue and finished the close.
            if (closing) mainHandler.postDelayed(closeTimeout, CLOSE_STOP_COMMANDS_TIMEOUT_MS)
        } else {
            finishClose()
        }
    }

    private val closeTimeout = Runnable { if (closing) { Log.d(TAG, "close(): stop commands timed out, closing anyway"); finishClose() } }

    private fun finishClose() {
        mainHandler.removeCallbacks(closeTimeout)
        closing = false
        resetLinkState()
        _batteryPercent.value = null
        _connectedDeviceName.value = null
        gatt?.close()
        gatt = null
        _connectionState.value = false
        _latestValidPulse.value = null
        afterClose?.let { afterClose = null; it() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Drops the main-thread link state: queue, timers, protocol, decoder. Battery and name are
     *  cleared by the callers that own them (DISCONNECTED on the binder thread, finishClose), so a
     *  posted reset can't wipe values a newer connection has already set. */
    private fun resetLinkState() {
        ops.clear()
        cancelKeepAlive()
        cancelOpTimer()
        protocol = null
        decoder = null
    }

    private fun cancelOpTimer() {
        opTimer?.let { mainHandler.removeCallbacks(it) }
        opTimer = null
    }

    private fun postOpTimer(delayMs: Long, block: () -> Unit) {
        cancelOpTimer()
        val r = Runnable { opTimer = null; block() }
        opTimer = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelKeepAlive() {
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
    }

    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.services?.asSequence()?.flatMap { (it.characteristics ?: emptyList()).asSequence() }?.firstOrNull { it.uuid == uuid }

    /** Starts [op] on [g] (mainHandler only). A refused call is retried once; a missing target or a
     *  second refusal counts as completed. Every started op gets a timeout keyed to it. */
    private fun runOp(op: GattOp?, g: BluetoothGatt, isRetry: Boolean = false) {
        if (op == null) return
        if (g !== gatt || !_connectionState.value) return
        val timeoutMs = startOp(op, g)
        if (timeoutMs != null) {
            // Keyed to this op: if its callback already completed it, inFlight is something else.
            postOpTimer(timeoutMs) {
                if (ops.inFlight === op) { Log.w(TAG, "runOp: $op got no callback in ${timeoutMs}ms, treating as complete"); afterComplete(g) }
            }
        } else if (!isRetry) {
            Log.d(TAG, "runOp: $op did not start, retrying once in ${OP_RETRY_DELAY_MS}ms")
            postOpTimer(OP_RETRY_DELAY_MS) {
                if (g === gatt && _connectionState.value && ops.inFlight === op) runOp(op, g, isRetry = true)
            }
        } else {
            Log.w(TAG, "runOp: $op did not start, skipping it")
            afterComplete(g)
        }
    }

    /** Issues [op]'s GATT call; returns its completion timeout if it started, null if not. */
    private fun startOp(op: GattOp, g: BluetoothGatt): Long? = when (op) {
        is GattOp.EnableNotify -> {
            val cccd = findCharacteristic(g, op.characteristic)?.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "runOp: CCCD descriptor $CCCD_UUID not found on ${op.characteristic}")
                null
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (g.writeDescriptor(cccd)) OP_TIMEOUT_MS else null
            }
        }
        is GattOp.Write -> {
            val c = findCharacteristic(g, op.characteristic)
            if (c == null) {
                Log.w(TAG, "runOp: write characteristic ${op.characteristic} not found")
                null
            } else {
                val props = c.properties
                val noResponse = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                c.writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = op.bytes
                if (g.writeCharacteristic(c)) (if (noResponse) NO_RESPONSE_WRITE_TIMEOUT_MS else OP_TIMEOUT_MS) else null
            }
        }
    }

    /** The single completion path: start the next op, then finish a close or start the keep-alive once idle. */
    private fun afterComplete(g: BluetoothGatt) {
        if (g !== gatt || !_connectionState.value) return
        cancelOpTimer()
        runOp(ops.complete(), g)
        if (!ops.isIdle) return
        if (closing) { finishClose(); return }
        if (protocol?.keepAlive != null && keepAliveRunnable == null) scheduleKeepAlive(g)
    }

    private fun scheduleKeepAlive(g: BluetoothGatt) {
        val p = protocol ?: return
        val keepAlive = p.keepAlive ?: return
        val writeChar = p.writeCharacteristic
        if (writeChar == null) { Log.w(TAG, "scheduleKeepAlive: ${p.id} has a keep-alive but no write characteristic"); return }
        val r = object : Runnable {
            override fun run() {
                if (g !== gatt || !_connectionState.value || protocol == null || closing) {
                    if (keepAliveRunnable === this) keepAliveRunnable = null
                    return
                }
                // Skip this tick while anything is queued, so keep-alives can't pile up behind a stuck op.
                if (ops.isIdle) runOp(ops.enqueue(GattOp.Write(writeChar, keepAlive.command)), g)
                mainHandler.postDelayed(this, keepAlive.intervalMs)
            }
        }
        keepAliveRunnable = r
        mainHandler.postDelayed(r, keepAlive.intervalMs)
    }

    /** Picks the protocol from the discovered GATT layout and queues its startup ops (mainHandler only). */
    private fun setUpProtocol(g: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS || g.services.isNullOrEmpty()) {
            // A failed discovery (e.g. 129/133 on a mid-session reconnect) is transient: disconnect
            // and let the DISCONNECTED handler retry, rather than stay connected with no data.
            discoveryFailures++
            if (discoveryFailures >= MAX_DISCOVERY_FAILURES) {
                // Manual Scan / Connect still works: scanAndConnect() sets shouldReconnect again.
                Log.w(TAG, "onServicesDiscovered: discovery failed $discoveryFailures times in a row (status=$status); disconnecting and stopping auto-reconnect")
                shouldReconnect = false
            } else {
                Log.w(TAG, "onServicesDiscovered: discovery failed (status=$status, services=${g.services?.size ?: 0}, attempt $discoveryFailures/$MAX_DISCOVERY_FAILURES); disconnecting to retry")
            }
            g.disconnect()
            return
        }
        val allCharacteristics = g.services?.flatMap { it.characteristics ?: emptyList() } ?: emptyList()
        val uuids = allCharacteristics.map { it.uuid }.toSet()
        // The human's actual KS-60FW unit doesn't expose 0xFFE4 at all - its GATT services
        // (confirmed via a device dump with full characteristic logging) are Generic Access, a
        // DFU service, and the generic Nordic UART Service (NUS), reused by many vendors as a
        // raw UART-over-BLE transport for their own byte protocol. 6E400003 (NUS TX, notify)
        // is what selects KS60FW here; a unit exposing 0xFFE4 selects FS20F first.
        // No advertisement fallback: every protocol's GATT match is "my notify characteristic is
        // present", so a protocol that fails it could never produce data anyway.
        val chosen = ProtocolRegistry.forGatt(uuids)
        if (chosen == null) {
            val address = g.device.address
            if (address == targetAddress || address == addressStore.getRemembered()) {
                // A device the user already chose: an unmatched layout is most likely a partial
                // or stale discovery, so retry like any failed discovery (same 3-failure cap).
                discoveryFailures++
                if (discoveryFailures >= MAX_DISCOVERY_FAILURES) {
                    Log.w(TAG, "onServicesDiscovered: no registered protocol matches characteristics $uuids on known device $address, $discoveryFailures times in a row; disconnecting and stopping auto-reconnect")
                    shouldReconnect = false
                } else {
                    Log.w(TAG, "onServicesDiscovered: no registered protocol matches characteristics $uuids on known device $address (attempt $discoveryFailures/$MAX_DISCOVERY_FAILURES); disconnecting to retry")
                }
                g.disconnect()
                return
            }
            // Never decode with a guess. Stop reconnecting first, or the DISCONNECTED handler
            // would reconnect to this same device forever. It was never remembered (see below).
            Log.w(TAG, "onServicesDiscovered: no registered protocol matches characteristics $uuids; disconnecting from $address")
            shouldReconnect = false
            g.disconnect()
            return
        }
        Log.d(TAG, "onServicesDiscovered: protocol chosen=${chosen.id} (by GATT), subscribing to ${chosen.notifyCharacteristic}")
        // Remembered only once a protocol matches, so a non-oximeter lookalike (e.g. an FFE0
        // advertiser) never replaces the user's remembered meter.
        addressStore.remember(g.device.address)
        discoveryFailures = 0
        _connectedDeviceName.value = g.device.name
        ops.clear()
        cancelKeepAlive()
        cancelOpTimer()
        protocol = chosen
        decoder = chosen.newDecoder()
        val notifyChar = allCharacteristics.firstOrNull { it.uuid == chosen.notifyCharacteristic }
        if (notifyChar != null) g.setCharacteristicNotification(notifyChar, true)
        else Log.w(TAG, "onServicesDiscovered: notify characteristic ${chosen.notifyCharacteristic} not found")
        if (chosen.startCommands().isNotEmpty() && chosen.writeCharacteristic == null) {
            Log.w(TAG, "onServicesDiscovered: ${chosen.id} has start commands but no write characteristic; skipping them")
        }
        // Enqueue everything before starting the first op, so a synchronous failure of the
        // first can't find the queue idle and start the keep-alive ahead of the start commands.
        var first: GattOp? = null
        startupOps(chosen).forEach { op -> ops.enqueue(op)?.let { first = it } }
        runOp(first, g)
    }

    // Fresh scan: runs for SCAN_WINDOW_MS (4s), collecting every distinct advertisement that
    // matches a registered protocol (ProtocolRegistry.forAdvertisement) by MAC address (a Map<String, DeviceCandidate> keyed on address, updated
    // on each ScanCallback.onScanResult, so repeated adverts from the same device don't
    // double-count). When the window's timer fires (stopScan() called):
    //  - 0 candidates -> emit ScanOutcome.NoneFound
    //  - 1 candidate -> connect directly to it (no ScanOutcome event; same fast path as before
    //    for the common single-device case)
    //  - 2+ candidates -> if one is the target/remembered device, connect to it; otherwise emit
    //    ScanOutcome.MultipleFound(candidates.values.toList()) and STOP - do not auto-connect to
    //    any of them. Waits for an explicit connectTo(address) call.
    private var currentScanWindow: Any? = null

    private fun startScan(askUser: Boolean = false, closedMeter: DeviceCandidate? = null) {
        // One window at a time: the single-slot scan state (and the one-GATT-client invariant,
        // since each window may connectGatt on a match) would break if windows overlapped.
        if (_scanInProgressSince.value != null) { Log.d(TAG, "startScan(): window already open, skipping"); return }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "startScan(): no BluetoothAdapter")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "startScan(): bluetoothLeScanner is null (adapter off, or scanning unsupported right now)")
            return
        }
        val found = LinkedHashMap<String, DeviceCandidate>()
        val window = Any()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                // Keep a name seen in an earlier advert: some adverts from the same device omit it.
                val name = result.device.name ?: result.scanRecord?.deviceName ?: found[address]?.name
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }?.toSet().orEmpty()
                val match = ProtocolRegistry.forAdvertisement(name, uuids) ?: return
                found[address] = DeviceCandidate(address, result.rssi, name, match.id, match.tested)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "onScanFailed: errorCode=$errorCode")
                // Only this window may clear shared state; its timer then sees the token gone and no-ops.
                if (currentScanWindow === window) {
                    currentScanWindow = null
                    _scanInProgressSince.value = null
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Android pauses unfiltered scans while the screen is off, so a session waiting in the dark
        // never saw the meter switch on; filtered scans keep running. Screen-on scans stay unfiltered
        // so the picker still sees every supported meter (name-prefix matches can't be filters).
        val screenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val filters: List<ScanFilter>? = if (screenOn) null else {
            val spec = screenOffScanFilters(targetAddress ?: addressStore.getRemembered(), ProtocolRegistry.advertisedServiceUuids())
            spec.addresses.map { ScanFilter.Builder().setDeviceAddress(it).build() } +
                spec.serviceUuids.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        }
        Log.d(TAG, "startScan(): starting $SCAN_WINDOW_MS ms scan window, matching registered protocols ${ProtocolRegistry.all.map { it.id }}" +
            if (filters == null) " (unfiltered, screen on)" else " (screen off: ${filters.size} filters)")
        // Mark the window open before starting the scan, so an onScanFailed for this window
        // always finds both the token and the timestamp set and clears them together.
        currentScanWindow = window
        _scanInProgressSince.value = SystemClock.elapsedRealtime()
        scanner.startScan(filters, settings, scanCallback)

        mainHandler.postDelayed({
            // A failed window already cleared its state; a newer window may own it now.
            if (currentScanWindow !== window) return@postDelayed
            currentScanWindow = null
            _scanInProgressSince.value = null
            scanner.stopScan(scanCallback)
            Log.d(TAG, "startScan(): window closed, matched candidates=${found.size} (${found.values.map { "${it.address}/${it.protocol}" }})")
            val listed = withClosedMeter(found.values.toList(), if (askUser) closedMeter else null)
            if (listed.any { it.needsRestart }) {
                // The user asked to choose, and the meter just closed may only come back after a restart.
                _scanResults.tryEmit(ScanOutcome.MultipleFound(listed))
                return@postDelayed
            }
            when (found.size) {
                0 -> _scanResults.tryEmit(ScanOutcome.NoneFound)
                1 -> {
                    val only = found.values.first()
                    Log.d(TAG, "startScan(): single match, connecting to ${only.address}")
                    _scanResults.tryEmit(ScanOutcome.AutoConnected(only.address))
                    val device = adapter.getRemoteDevice(only.address)
                    targetAddress = only.address
                    // Direct connect for the same reason as connectTo(): autoConnect = true never
                    // completed after a fresh scan, and the next rescan then cancelled it.
                    gatt = device.connectGatt(context, false, gattCallback)
                }
                else -> {
                    // During a session nothing collects the picker, so a lookalike (e.g. a generic
                    // FFE0 UART gadget) would strand it; prefer a device the user already chose.
                    val known = targetAddress ?: addressStore.getRemembered()
                    val preferred = resolveMultipleFound(askUser, known, found.keys)?.let { found[it] }
                    if (preferred != null) {
                        Log.d(TAG, "startScan(): ${found.size} matches, connecting to known device ${preferred.address}")
                        targetAddress = preferred.address
                        gatt = adapter.getRemoteDevice(preferred.address).connectGatt(context, false, gattCallback)
                    } else {
                        _scanResults.tryEmit(ScanOutcome.MultipleFound(found.values.toList()))
                    }
                }
            }
        }, SCAN_WINDOW_MS)
    }

    // GattCallback implementation (once connecting, regardless of how the address was chosen)
    // handles:
    //  - onServicesDiscovered: on a failed/empty discovery, disconnects so reconnect retries;
    //    otherwise chooses the protocol from the characteristic UUIDs (ProtocolRegistry.forGatt;
    //    no hardcoded service UUID), remembers the address, then setCharacteristicNotification + queues the CCCD
    //    ENABLE_NOTIFICATION_VALUE write and the protocol's start commands through ops.
    //  - onDescriptorWrite / onCharacteristicWrite: complete the in-flight op (afterComplete).
    //  - onCharacteristicChanged: runs the bytes through the protocol's FrameDecoder, wrapped
    //    in try/catch - log and skip a bad frame rather than letting it propagate out of the
    //    GATT callback and crash the app. Reading -> _readings (Valid/NoSignal), SensorOff ->
    //    _sensorOffState, Battery -> _batteryPercent.
    //  - onConnectionStateChange CONNECTED (newState == BluetoothProfile.STATE_CONNECTED):
    //    sets _connectionState.value = true, then emits PulseReading.Connected (Task 2's
    //    sealed type) - this is what SessionEngine's onReconnected() is wired to in Task 16,
    //    and it's what clears SignalLossTracker.disconnected after a reconnect. Without
    //    emitting this, signal loss becomes permanent after the first disconnect (see Task 9's
    //    wiring contract note). addressStore.remember(address) is NOT called here but in
    //    onServicesDiscovered once a protocol matches, so a chosen oximeter (whether
    //    auto-connected or user-picked) becomes the remembered address for next launch and a
    //    non-oximeter never does.
    //  - onConnectionStateChange DISCONNECTED: sets _connectionState.value = false, emits
    //    PulseReading.Disconnected immediately, closes the old BluetoothGatt, then (if
    //    shouldReconnect) connectGatt(remembered address, autoConnect = true) again -
    //    reconnect always targets the specific remembered address, never re-runs the
    //    multi-device scan/picker mid-session. GATT status 133 is treated as retriable, not
    //    terminal.
    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: address=${g.device.address} status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = true
                _connectingSince.value = null
                _connectedDeviceName.value = g.device.name
                g.discoverServices()
                publishReading(PulseReading.Connected)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Cleared at once so a reconnect to a device that reports none shows no stale value.
                _batteryPercent.value = null
                _connectedDeviceName.value = null
                // Queue/keep-alive/protocol live on mainHandler; FIFO order there means this
                // runs before any setup posted for the next connection.
                mainHandler.post { resetLinkState() }
                _connectionState.value = false
                publishReading(PulseReading.Disconnected)
                g.close()
                // Only the current link reconnects: a late callback from a gatt already closed by
                // rescanAndChoose() must not autoConnect the old meter beside the one being picked.
                val wasCurrent = gatt === g
                if (wasCurrent) gatt = null
                // GATT status 133 (GATT_ERROR, common spurious connection failure) is treated
                // as retriable, not terminal - the reconnect attempt below runs regardless of
                // the specific status code, as long as shouldReconnect is still true.
                if (shouldReconnect && wasCurrent) {
                    val remembered = targetAddress ?: addressStore.getRemembered()
                    val adapter = bluetoothAdapter
                    if (remembered != null && adapter != null) {
                        val device = adapter.getRemoteDevice(remembered)
                        // Self-reference via `this` (the anonymous BluetoothGattCallback
                        // instance) rather than the `gattCallback` property name - referencing
                        // the property by name from within its own initializer is a compile
                        // error ("Variable 'gattCallback' must be initialized"), but `this`
                        // inside the object expression's own method body is always valid since
                        // the object is fully constructed by the time any callback method runs.
                        gatt = device.connectGatt(context, true, this)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            g.services?.forEach { service ->
                Log.d(TAG, "  service=${service.uuid}")
                service.characteristics?.forEach { c ->
                    Log.d(TAG, "    characteristic=${c.uuid} properties=${c.properties} descriptors=${c.descriptors?.map { it.uuid }}")
                }
            }
            mainHandler.post {
                if (g !== gatt || !_connectionState.value) return@post
                setUpProtocol(g, status)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: ${descriptor.uuid} status=$status")
            mainHandler.post {
                if (g !== gatt || !_connectionState.value) return@post
                if (ops.inFlight is GattOp.EnableNotify) afterComplete(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onCharacteristicWrite: ${characteristic.uuid} status=$status")
            val uuid = characteristic.uuid
            mainHandler.post {
                if (g !== gatt || !_connectionState.value) return@post
                // Only completes a matching write, so a late ack for a timed-out no-response
                // write can't complete an unrelated op.
                val inFlight = ops.inFlight
                if (inFlight is GattOp.Write && inFlight.characteristic == uuid) afterComplete(g)
            }
        }

        // Only the 2-arg form: on API 33+ overriding the 3-arg one too would decode every
        // notification twice.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            if (debuggable) Log.d(RAW_TAG, value.joinToString(" ") { "%02X".format(it) })
            // KS-60FW over NUS TX: each notification is decoded independently (the reference
            // implementation, github.com/pythag/pc-60fw, never buffers across notifications);
            // a notification with no matching header yields no events and is dropped. FS20F's
            // decoder buffers across notifications via its MessageFramer.
            val d = decoder ?: return
            val events = try {
                d.decode(value, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "onCharacteristicChanged: decode failed, skipping: ${e.message}")
                return
            }
            for (event in events) {
                when (event) {
                    is OximeterEvent.Reading -> publishReading(event.toPulseReading())
                    is OximeterEvent.SensorOff -> _sensorOffState.tryEmit(event.off)
                    is OximeterEvent.Battery -> _batteryPercent.value = event.percent
                }
            }
        }
    }
}
