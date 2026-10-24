package com.austinlinux.pulsemetronome.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.austinlinux.pulsemetronome.R
import com.austinlinux.pulsemetronome.alarm.ACTION_DISMISS_ALARM
import com.austinlinux.pulsemetronome.alarm.ALARM_RING_DURATION_MS
import com.austinlinux.pulsemetronome.alarm.AlarmPlayer
import com.austinlinux.pulsemetronome.alarm.AlarmType
import com.austinlinux.pulsemetronome.audio.AudioEngine
import com.austinlinux.pulsemetronome.audio.loadClickSample
import com.austinlinux.pulsemetronome.ble.BleModule
import com.austinlinux.pulsemetronome.ble.protocol.PulseReading
import com.austinlinux.pulsemetronome.data.AppDatabase
import com.austinlinux.pulsemetronome.debug.DebugCapture
import com.austinlinux.pulsemetronome.data.EndReason as DbEndReason
import com.austinlinux.pulsemetronome.data.PulseSampleEntity
import com.austinlinux.pulsemetronome.data.SessionEntity
import com.austinlinux.pulsemetronome.data.StartingBpmSource as DbStartingBpmSource
import com.austinlinux.pulsemetronome.engine.EndReason as EngineEndReason
import com.austinlinux.pulsemetronome.engine.SessionConfig
import com.austinlinux.pulsemetronome.engine.SessionEngine
import com.austinlinux.pulsemetronome.engine.SessionEvent
import com.austinlinux.pulsemetronome.engine.SessionState
import com.austinlinux.pulsemetronome.engine.StartMode
import com.austinlinux.pulsemetronome.engine.parseDescentPattern
import com.austinlinux.pulsemetronome.engine.parseMonitorSignalLoss
import com.austinlinux.pulsemetronome.engine.parsePacedSignalLoss
import com.austinlinux.pulsemetronome.engine.shouldSilence
import com.austinlinux.pulsemetronome.engine.usableInitialPulse
import com.austinlinux.pulsemetronome.engine.StartingBpmSource as EngineStartingBpmSource
import com.austinlinux.pulsemetronome.engine.SystemMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private const val NOTIFICATION_ID = 1001
private const val SESSION_CHANNEL_ID = "session_progress"
private const val ALARM_RING_DURATION_MS_PLUS_MARGIN = ALARM_RING_DURATION_MS + 2_000L
private const val AUTO_RESCAN_INTERVAL_MS = 12_000L  // within the spec's 10-15s range

class SessionForegroundService : Service() {

    companion object {
        private const val EXTRA_TARGET_BPM = "target_bpm"
        private const val EXTRA_TOTAL_DURATION_MIN = "total_duration_min"
        private const val EXTRA_RAMP_DURATION_MIN = "ramp_duration_min"
        private const val EXTRA_MAX_OFFSET = "max_offset"
        private const val EXTRA_STATIC_FALLBACK_BPM = "static_fallback_bpm"
        private const val EXTRA_ALARM_TYPE = "alarm_type"
        private const val EXTRA_START_MODE = "start_mode"
        private const val EXTRA_DESCENT_PATTERN = "descent_pattern"
        private const val EXTRA_SAVE_TO_HISTORY = "save_to_history"
        private const val EXTRA_MONITOR = "monitor"
        private const val EXTRA_MONITOR_SIGNAL_LOSS = "monitor_signal_loss"
        private const val EXTRA_PACED_SIGNAL_LOSS = "paced_signal_loss"
        private const val ACTION_STOP = "com.austinlinux.pulsemetronome.action.STOP_SESSION"

        /** Built by the caller (Task 22's nav layer, from either Setup/Live Session's "Start
         *  Now" or "Start When Pulse Detected" button) from Setup form state + Settings. */
        fun newIntent(
            context: Context,
            config: SessionConfig,
            alarmType: AlarmType,
            startMode: StartMode,
            saveToHistory: Boolean
        ): Intent = Intent(context, SessionForegroundService::class.java).apply {
            putExtra(EXTRA_TARGET_BPM, config.targetBpm)
            putExtra(EXTRA_TOTAL_DURATION_MIN, config.totalDurationMin)
            putExtra(EXTRA_RAMP_DURATION_MIN, config.rampDurationMin)
            putExtra(EXTRA_MAX_OFFSET, config.maxOffset)  // always present now - non-nullable
            putExtra(EXTRA_STATIC_FALLBACK_BPM, config.staticFallbackStartingBpm)
            putExtra(EXTRA_ALARM_TYPE, alarmType.name)
            putExtra(EXTRA_START_MODE, startMode.name)
            putExtra(EXTRA_DESCENT_PATTERN, config.descentPattern.name)
            putExtra(EXTRA_SAVE_TO_HISTORY, saveToHistory)
            putExtra(EXTRA_MONITOR, config.monitor)
            putExtra(EXTRA_MONITOR_SIGNAL_LOSS, config.monitorSignalLoss.name)
            putExtra(EXTRA_PACED_SIGNAL_LOSS, config.pacedSignalLoss.name)
        }

        /** Sent from the notification's stop action and the Live Session screen's Stop
         *  button - a distinct Intent action on the same service, not a new service. */
        fun newStopIntent(context: Context): Intent =
            Intent(context, SessionForegroundService::class.java).setAction(ACTION_STOP)
    }

    // All mutable session wiring state below is confined to this single-threaded scope's
    // dispatcher - BLE GATT callbacks (which arrive on a binder thread inside BleModule) and
    // the ticker coroutine both funnel through a single Channel (per spec's single-dispatcher
    // confinement requirement for SessionEngine) rather than calling engine methods directly
    // from multiple threads.
    private val serviceScope = CoroutineScope(SupervisorJob())
    private val engineEventChannel = Channel<() -> Unit>(Channel.UNLIMITED)

    private lateinit var config: SessionConfig
    private lateinit var engine: SessionEngine
    private lateinit var bleModule: BleModule
    private lateinit var audioEngine: AudioEngine
    private lateinit var alarmPlayer: AlarmPlayer
    private lateinit var alarmType: AlarmType
    private var saveToHistory = true
    private lateinit var db: AppDatabase
    private var sessionRowId: Long? = null
    private var sessionRowInsertRequested = false  // guard set synchronously, before the insert coroutine launches - see handleSessionEvent
    private var sessionRowInsertJob: Job? = null    // joined in the SessionEnded handler before reading sessionRowId

    // Guards against a second onStartCommand landing while a session is already active - a
    // real bug found in final review (C2): without this, re-navigating to Setup and tapping
    // Start again (system Back from Live Session, a double-tap, or a Stop tapped during the
    // post-session alarm delay window) launches a SECOND engineEventChannel consumer, leaves
    // the old AudioEngine/BLE collectors/ticker running against the new engine, and silently
    // drops the new session's History row (sessionRowInsertRequested never resets). Set true
    // as soon as a session starts, cleared only once SessionEnded's cleanup has fully run.
    private var sessionActive = false
    private var audioStarted = false
    private var autoRescanJob: Job? = null

    // Debug-capture bookkeeping so STATE lines are written on change or at most ~1/s.
    private var lastPhase: SessionState.Phase? = null
    private var lastSignalLost: Boolean? = null
    private var lastMuted: Boolean? = null
    private var lastStateLineAt = 0L
    private var lastRunningElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        (application as com.austinlinux.pulsemetronome.PulseMetronomeApp).noteComponent("SessionForegroundService")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugCapture.event("LIFE") {
            "service onStartCommand intent=${if (intent == null) "null" else (intent.action ?: "start")} flags=$flags startId=$startId"
        }
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }

        if (intent.action == ACTION_STOP) {
            // Service is already running (this is the notification/UI stop button, not a
            // fresh session start) - just relay to the engine via the same serialized channel
            // everything else uses. If the service isn't actually running yet (stray stop
            // intent), engineEventChannel.trySend silently no-ops since nothing is consuming
            // it yet - acceptable, there's no session to stop in that case anyway.
            engineEventChannel.trySend { if (::engine.isInitialized) engine.stop() }
            return START_NOT_STICKY
        }

        if (sessionActive) {
            // A session is already running - ignore this start request rather than
            // corrupting the in-progress session's engine/audio/BLE wiring. The UI (Setup)
            // is also expected to hide/disable its Start buttons while a session is active
            // (SessionStateBus.state != null), so reaching this path means a stray duplicate
            // request (e.g. a queued Intent from a double-tap) got through anyway.
            return START_NOT_STICKY
        }
        sessionActive = true
        // One Service instance can host several sessions, so per-session capture state restarts here.
        lastPhase = null
        lastSignalLost = null
        lastMuted = null
        lastStateLineAt = 0L
        lastRunningElapsedMs = 0L

        // startForeground() must be called within ~5s of the service starting on API 26+
        // (immediately, in practice) or the system kills it - do this before any suspending
        // work, using a minimal initial notification that gets updated once the session's
        // real state is available.
        ensureNotificationChannel()
        val startMode = StartMode.valueOf(intent.getStringExtra(EXTRA_START_MODE) ?: StartMode.NOW.name)
        // NO_PULSE never touches Bluetooth and may run without its permission; claiming the
        // connectedDevice type without BLUETOOTH_CONNECT throws on Android 14.
        if (startMode == StartMode.NO_PULSE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildInitialNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, buildInitialNotification())
        }

        config = SessionConfig(
            targetBpm = intent.getDoubleExtra(EXTRA_TARGET_BPM, 60.0),
            totalDurationMin = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_MIN, 30.0),
            rampDurationMin = intent.getDoubleExtra(EXTRA_RAMP_DURATION_MIN, 30.0),
            maxOffset = intent.getDoubleExtra(EXTRA_MAX_OFFSET, 2.0),  // 2.0 fallback only if the extra is somehow missing entirely
            staticFallbackStartingBpm = intent.getDoubleExtra(EXTRA_STATIC_FALLBACK_BPM, 70.0),
            descentPattern = parseDescentPattern(intent.getStringExtra(EXTRA_DESCENT_PATTERN)),
            // Monitor only applies to Wait-for-pulse sessions; other modes ignore the extra.
            monitor = startMode == StartMode.WHEN_PULSE_DETECTED && intent.getBooleanExtra(EXTRA_MONITOR, false),
            monitorSignalLoss = parseMonitorSignalLoss(intent.getStringExtra(EXTRA_MONITOR_SIGNAL_LOSS)),
            pacedSignalLoss = parsePacedSignalLoss(intent.getStringExtra(EXTRA_PACED_SIGNAL_LOSS))
        )
        alarmType = AlarmType.valueOf(intent.getStringExtra(EXTRA_ALARM_TYPE) ?: AlarmType.SOUND.name)
        saveToHistory = intent.getBooleanExtra(EXTRA_SAVE_TO_HISTORY, true)
        DebugCapture.event("SESSION") { "start config=$config alarm=$alarmType history=$saveToHistory" }
        DebugCapture.event("SESSION") { "start mode=$startMode" }

        db = AppDatabase.instance(applicationContext)  // shared singleton, see Task 10/22
        engine = SessionEngine(config, SystemMonotonicClock())
        // Reuses the app-scoped BleModule (constructed once in PulseMetronomeApp) - NOT a new
        // instance - so a connection Setup already established isn't torn down and rebuilt
        // when a session starts. See Task 18's Ownership clarification.
        bleModule = (application as com.austinlinux.pulsemetronome.PulseMetronomeApp).bleModule
        // Decoded once here, not inside the lambda AudioEngine calls per-beat (a real bug
        // found in final review, I2) - re-running MediaExtractor on every click adds
        // decode jitter directly to beat timing and risks an uncaught exception on the
        // non-daemon audio thread if the decode ever fails mid-session.
        val clickSample = loadClickSample(applicationContext, R.raw.click)
        audioEngine = AudioEngine(applicationContext) { clickSample }
        alarmPlayer = AlarmPlayer(applicationContext)
        alarmPlayer.onDismissed = { SessionStateBus.clearAlarm() }

        wireEngineListener()
        // A no-pulse session is for people without a meter: no scanning, and a meter that appears is ignored.
        if (startMode != StartMode.NO_PULSE) wireBleFlows()
        wireBleCapture()
        startTicker()
        if (startMode != StartMode.NO_PULSE) startAutoRescan()

        // audioEngine.start() is no longer called here unconditionally - it now starts on
        // the first RUNNING StateUpdated (handleSessionEvent), so the metronome stays
        // silent through AWAITING_START (connecting + verification) under
        // WHEN_PULSE_DETECTED, instead of audibly clicking at a stale default 60 BPM while
        // waiting (bell-curve pacing redesign, spec section 6).
        //
        // Safe to call unconditionally: BleModule.scanAndConnect() (Task 11) checks
        // connectionState.value first and, if already connected, only resets
        // shouldReconnect = true (undoing a prior session's stopReconnecting()) without
        // triggering a new scan or a second concurrent GATT connection.
        //
        // engine.start(...) is routed through engineEventChannel, NOT called directly here -
        // onStartCommand runs on the main thread, and SessionEngine must only ever be touched
        // from the single serialized consumer coroutine started by wireEngineListener() above,
        // per the spec's single-dispatcher confinement requirement.
        if (startMode != StartMode.NO_PULSE) bleModule.scanAndConnect()
        // A previous session's unconsumed end signal must not open its Summary over this session.
        SessionStateBus.clearEndedSessionId()
        val latest = bleModule.latestValidPulse.value
        val initialPulse = if (startMode == StartMode.NOW)
            usableInitialPulse(latest?.bpm, latest?.atElapsedMs, SystemClock.elapsedRealtime()) else null
        engineEventChannel.trySend { engine.start(startMode, initialPulse) }

        return START_NOT_STICKY
    }

    /** While a WHEN_PULSE_DETECTED session is still AWAITING_START and not connected,
     *  auto-retries via BleModule.retryConnectIfIdle() - resolves the "checkbox allows Start
     *  with no device connected, but nothing then connects it" gap (bell-curve pacing
     *  redesign). Uses retryConnectIfIdle(), NOT scanAndConnect() directly, specifically to
     *  avoid opening a second concurrent GATT client while a connection attempt from a prior
     *  scan is still pending. Stops once the session leaves AWAITING_START (RUNNING or
     *  ENDED), read from SessionStateBus each tick. Runs on Dispatchers.Main to match every
     *  other BleModule caller in this codebase. */
    private fun startAutoRescan() {
        autoRescanJob = serviceScope.launch(Dispatchers.Main) {
            while (true) {
                delay(AUTO_RESCAN_INTERVAL_MS)
                val currentPhase = SessionStateBus.state.value?.phase
                if (currentPhase != SessionState.Phase.AWAITING_START) break
                bleModule.retryConnectIfIdle()
            }
        }
    }

    /** All calls into `engine` from any source (BLE flows, ticker, stop action) go through
     *  this single serialized channel consumer - the one coroutine that actually touches
     *  `engine` - satisfying the spec's single-dispatcher confinement requirement. */
    private fun wireEngineListener() {
        serviceScope.launch {
            for (action in engineEventChannel) action()
        }
        engine.addListener { event -> handleSessionEvent(event) }
    }

    private fun wireBleFlows() {
        serviceScope.launch {
            bleModule.readings.collect { reading ->
                DebugCapture.event("READING") {
                    when (reading) {
                        is PulseReading.Valid -> "valid pulse=${reading.pulseRate} at=${reading.timestampMs}"
                        is PulseReading.NoSignal -> "no-signal"
                        PulseReading.Disconnected -> "disconnected"
                        PulseReading.Connected -> "connected"
                    }
                }
                engineEventChannel.trySend {
                    when (reading) {
                        is PulseReading.Valid -> engine.onPulseReading(reading.pulseRate, reading.timestampMs)
                        is PulseReading.NoSignal -> engine.onInvalidPulse()
                        PulseReading.Disconnected -> engine.onDisconnected()
                        // Clears SignalLossTracker.disconnected - REQUIRED or signal loss is
                        // permanent after the first disconnect (Task 9's wiring contract).
                        PulseReading.Connected -> engine.onReconnected()
                    }
                }
            }
        }
        serviceScope.launch {
            bleModule.sensorOffState.collect { isOff ->
                DebugCapture.event("READING") { "sensorOff=$isOff" }
                engineEventChannel.trySend { engine.onSensorOffChanged(isOff) }
            }
        }
    }

    /** Session-scoped capture of BLE connection, device name and battery changes (ends with serviceScope). */
    private fun wireBleCapture() {
        serviceScope.launch { bleModule.connectionState.collect { b -> DebugCapture.event("BLE") { "connected=$b" } } }
        serviceScope.launch { bleModule.connectedDeviceName.collect { n -> DebugCapture.event("BLE") { "device=$n" } } }
        serviceScope.launch { bleModule.batteryPercent.collect { n -> DebugCapture.event("BLE") { "battery=$n%" } } }
    }

    private fun startTicker() {
        serviceScope.launch {
            while (true) {
                delay(250L)
                engineEventChannel.trySend { engine.onTick() }
            }
        }
    }

    private fun handleSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.StateUpdated -> {
                SessionStateBus.publish(event.state)
                SessionStateBus.audioPlaybackHeadPositionMs = { audioEngine.playbackHeadPositionMs() }
                event.state.metronomeBpm?.let { audioEngine.setBpm(it) }
                val muted = shouldSilence(config.monitor, event.state.signalLost, config.monitorSignalLoss)
                audioEngine.setMuted(muted)
                logStateUpdate(event.state, muted)
                // Audio starts on the first RUNNING state, AFTER setBpm() above has already
                // set the real resolved BPM - AudioEngine's scheduler thread reads
                // currentBpm at the top of its very first beat, so starting it before the
                // first setBpm() call risks that first beat using the stale default 60 BPM
                // instead (bell-curve pacing redesign design-review finding).
                if (!audioStarted && event.state.phase == SessionState.Phase.RUNNING) {
                    audioStarted = true
                    audioEngine.start()
                }
                updateNotification(event.state)
                // Session row is inserted the FIRST time phase reaches RUNNING (starting BPM
                // resolved), not at engine.start() - startingBpm/startingBpmSource are
                // non-null columns (Task 10) and aren't known before that point.
                //
                // Race fix: handleSessionEvent is called synchronously from
                // engine.addListener {...}, itself invoked from the single serialized
                // engineEventChannel consumer - so THIS check-and-launch is not itself racing
                // against another StateUpdated (they're processed one at a time, in order).
                // Setting the guard flag synchronously, BEFORE launching the insert coroutine,
                // is what prevents a second StateUpdated arriving before the insert completes
                // from launching a duplicate insert.
                if (!sessionRowInsertRequested && event.state.phase == SessionState.Phase.RUNNING) {
                    sessionRowInsertRequested = true
                    sessionRowInsertJob = insertSessionRow(event.state)
                }
            }
            is SessionEvent.PulseSampleReady -> {
                val rowId = sessionRowId ?: return  // no row yet (still AWAITING_START) - drop
                serviceScope.launch {
                    db.pulseSampleDao().insert(PulseSampleEntity(
                        sessionId = rowId,
                        timestamp = System.currentTimeMillis(),  // epoch ms for storage, per
                        // spec's pulse_samples.timestamp - event.sample.timestampMs is
                        // elapsedRealtime-based (SessionEngine's clock), NOT epoch ms.
                        pulse = event.sample.pulse,
                        metronomeBpm = event.sample.metronomeBpm
                    ))
                }
            }
            is SessionEvent.SessionEnded -> {
                DebugCapture.event("SESSION") {
                    "end reason=${event.reason} run=${lastRunningElapsedMs / 1000}s endingBpm=${event.endingBpm} " +
                        "reached=${event.targetReached} focus=${event.focusPercent ?: "-"}"
                }
                serviceScope.launch {
                    // Wait for a still-in-flight insertSessionRow() to actually finish before
                    // reading sessionRowId - without this, a session that reaches RUNNING and
                    // is stopped again within the same ~event-loop tick could see sessionRowId
                    // still null here even though sessionRowInsertRequested is true.
                    sessionRowInsertJob?.join()

                    // Finish the row update BEFORE stopping the service, and keep the service
                    // alive through the alarm's ring duration - stopSelf() would tear down the
                    // process's ability to keep AlarmPlayer's MediaPlayer/Vibrator running.
                    // sessionRowId is null when Stop is tapped during AWAITING_START (possible
                    // under StartMode.WHEN_PULSE_DETECTED, before any row was ever inserted) -
                    // the ?.let below correctly no-ops rather than writing a partial row.
                    sessionRowId?.let { rowId ->
                        val existing = db.sessionDao().getById(rowId)
                        if (existing != null) {
                            db.sessionDao().update(existing.copy(
                                endTimestamp = System.currentTimeMillis(),
                                endingBpm = event.endingBpm?.let { Math.round(it).toInt() },
                                endReason = when (event.reason) {
                                    EngineEndReason.DURATION_ELAPSED -> DbEndReason.DURATION_ELAPSED
                                    EngineEndReason.USER_STOPPED -> DbEndReason.USER_STOPPED
                                },
                                targetReached = event.targetReached,
                                highestPulse = event.highestPulse,
                                lowestPulse = event.lowestPulse,
                                pulseLostAtElapsedMs = event.pulseLostAtElapsedMs,
                                focusPercent = event.focusPercent
                            ))
                        }
                    }
                    bleModule.stopReconnecting()
                    audioEngine.stop()
                    // Published BEFORE the state is cleared so the Live Session screen, on seeing
                    // the state go null, already knows whether there is a Summary to open.
                    sessionRowId?.let { SessionStateBus.publishEndedSessionId(it) }
                    // Only a session that actually ran can time out; one still waiting for a pulse never rings.
                    val timedOut = event.reason == EngineEndReason.DURATION_ELAPSED && sessionRowInsertRequested
                    val ringing = timedOut && alarmType != AlarmType.SILENT
                    if (ringing) SessionStateBus.publishAlarm(alarmType)
                    SessionStateBus.clear()

                    if (timedOut) {
                        alarmPlayer.play(alarmType)
                        if (ringing) {
                            // Posted after play() so the dismiss receiver is registered before the
                            // button can be tapped; the old "Stop" did nothing once the session ended.
                            showAlarmRingingNotification()
                            // Keeps the service (and its process-tied AlarmPlayer/Vibrator) alive
                            // while ringing; ends early on Dismiss (Summary screen or notification).
                            withTimeoutOrNull(ALARM_RING_DURATION_MS_PLUS_MARGIN) {
                                SessionStateBus.alarm.first { it == null }
                            }
                            SessionStateBus.clearAlarm()
                            alarmPlayer.dismiss()  // idempotent; covers a dismiss that raced the wait
                        }
                        // Silent: nothing to keep alive, so the service ends now - waiting here used
                        // to leave a ~17 s window where a new Start was silently dropped.
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    sessionActive = false
                    stopSelf()
                }
            }
        }
    }

    /** Writes SESSION phase and STATE capture lines: on phase/signal/mute change, else at most once per second. */
    private fun logStateUpdate(state: SessionState, muted: Boolean) {
        lastRunningElapsedMs = state.runningElapsedMs
        if (lastPhase != state.phase) {
            val old = lastPhase
            DebugCapture.event("SESSION") { "phase ${old ?: "-"} -> ${state.phase}" }
            lastPhase = state.phase
        }
        val now = SystemClock.elapsedRealtime()
        if (state.signalLost != lastSignalLost || muted != lastMuted || now - lastStateLineAt >= 1000L) {
            lastSignalLost = state.signalLost
            lastMuted = muted
            lastStateLineAt = now
            DebugCapture.event("STATE") {
                "phase=${state.phase} run=${state.runningElapsedMs / 1000}s " +
                    "pulse=${state.smoothedPulse?.let { String.format(Locale.US, "%.1f", it) } ?: "-"} " +
                    "bpm=${state.metronomeBpm?.let { String.format(Locale.US, "%.1f", it) } ?: "-"} " +
                    "lost=${state.signalLost} muted=$muted reached=${state.targetReached} " +
                    "focus=${state.focusPercent ?: "-"}"
            }
        }
    }

    private fun insertSessionRow(state: SessionState): Job = serviceScope.launch {
        sessionRowId = db.sessionDao().insert(SessionEntity(
            startTimestamp = System.currentTimeMillis(),
            endTimestamp = null,
            targetBpm = Math.round(config.targetBpm).toInt(),
            rampDurationMin = Math.round(config.rampDurationMin).toInt(),
            totalDurationMin = Math.round(config.totalDurationMin).toInt(),
            maxOffset = Math.round(config.maxOffset).toInt(),
            startingBpm = Math.round(state.startingBpm!!).toInt(),
            startingBpmSource = when (state.startingBpmSource!!) {
                EngineStartingBpmSource.LIVE_PULSE -> DbStartingBpmSource.LIVE_PULSE
                EngineStartingBpmSource.STATIC_FALLBACK -> DbStartingBpmSource.STATIC_FALLBACK
            },
            endingBpm = null,
            endReason = null,
            targetReached = state.targetReached,
            monitor = config.monitor
        ))
        // History off: the session is kept only until its Summary is left.
        if (!saveToHistory) (application as com.austinlinux.pulsemetronome.PulseMetronomeApp).settingsRepository.addPendingDelete(sessionRowId!!)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                SESSION_CHANNEL_ID,
                "Session Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildInitialNotification() =
        NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setContentTitle("Pulse Metronome")
            .setContentText("Starting session…")
            .setSmallIcon(R.drawable.ic_stat_metronome)
            .setColor(getColor(R.color.pm_red))
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent())
            .build()

    private fun updateNotification(state: SessionState) {
        val bpmText = state.metronomeBpm?.let { "%.0f BPM".format(it) } ?: "Waiting for pulse…"
        val remainingMin = state.remainingTotalMs / 60_000
        val remainingSec = (state.remainingTotalMs / 1000) % 60
        val notification = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setContentTitle(bpmText)
            .setContentText("%d:%02d remaining".format(remainingMin, remainingSec))
            .setSmallIcon(R.drawable.ic_stat_metronome)
            .setColor(getColor(R.color.pm_red))
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Replaces the session notification while a time-out alarm rings: "Session complete" + Dismiss alarm. */
    private fun showAlarmRingingNotification() {
        val dismiss = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_DISMISS_ALARM).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setContentTitle("Session complete")
            .setSmallIcon(R.drawable.ic_stat_metronome)
            .setColor(getColor(R.color.pm_red))
            .setOngoing(true)
            .addAction(0, "Dismiss alarm", dismiss)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this, 0, newStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onDestroy() {
        DebugCapture.event("LIFE") { "service onDestroy" }
        // Does NOT call bleModule.close() - BleModule is app-scoped (owned by
        // PulseMetronomeApp, not this service), so it must survive the service stopping and
        // stay connected for the next session / for Setup to show connection status.
        // stopReconnecting() (called from SessionEnded, above) already stops this service's
        // session from driving further reconnect attempts; the underlying GATT connection
        // itself is left alone.
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
