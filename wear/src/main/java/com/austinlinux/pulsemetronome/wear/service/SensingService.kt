package com.austinlinux.pulsemetronome.wear.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.austinlinux.pulsemetronome.wear.BuildConfig
import com.austinlinux.pulsemetronome.wear.R
import com.austinlinux.pulsemetronome.wear.link.LogSender
import com.austinlinux.pulsemetronome.wear.link.PhoneLink
import com.austinlinux.pulsemetronome.wear.log.WatchRunLog
import com.austinlinux.pulsemetronome.wear.sensing.HealthServicesHeartRateSensor
import com.austinlinux.pulsemetronome.wear.sensing.HeartRateSensor
import com.austinlinux.pulsemetronome.wear.session.WatchSessionMachine
import com.austinlinux.pulsemetronome.wear.session.WatchUiState
import com.austinlinux.pulsemetronome.wear.ui.WatchActivity
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground health service: owns the session machine, the heart-rate sensor and the Ongoing Activity.
 * Phone messages arrive only through [PhoneMessageListener] (or, later, the watch activity) as intents
 * built by [forward]; this service registers no MessageClient listener of its own.
 */
class SensingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val machine = WatchSessionMachine(nowMs = { SystemClock.elapsedRealtime() })
    private lateinit var link: PhoneLink
    private lateinit var sensor: HeartRateSensor
    // Null outside debug and watchtest builds, so release never records or sends a log.
    private var runLog: WatchRunLog? = null
    private var logSender: LogSender? = null
    private var activeSessionId: String? = null
    private var tickJob: Job? = null
    private var foregroundStarted = false
    private var outboxJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        link = PhoneLink(this)
        sensor = HealthServicesHeartRateSensor(this)
        if (BuildConfig.DEBUG_TOOLS) {
            val log = WatchRunLog(File(filesDir, "runlogs"))
            runLog = log
            logSender = LogSender(this, log)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heart rate sensing", NotificationManager.IMPORTANCE_LOW),
        )
        scope.launch {
            machine.ui.collect { state ->
                _uiState.value = state
                runLog?.event("STATE", state.toString())
                if (foregroundStarted) updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val message = intent?.let(::messageFrom)
        if (message !is WatchMessage.Start) {
            handleOther(message)
            return START_NOT_STICKY
        }
        if (!hasHeartPermission(this)) {
            // The permission really is missing: tell the phone. Satisfy the foreground contract if we can.
            ensureForeground()
            sendAckDetached(machine.onStart(message.sessionId, hasPermission = false))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!ensureForeground()) {
            // Last resort only: PhoneMessageListener already screens out the background and no-permission cases.
            // Granted but refused: no Ack, so the phone's 3 s fallback opens the watch app and resends Start.
            // A refused startForeground cannot be satisfied with another type (the manifest declares health only),
            // so the owed startForegroundService() contract may still log a crash; the listener gate avoids this path.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        handleStart(message, machine.onStart(message.sessionId, hasPermission = true))
        return START_NOT_STICKY
    }

    /** Stop, Heartbeat or junk: never starts foreground; with no active session the service just ends. */
    private fun handleOther(message: WatchMessage?) {
        when (message) {
            is WatchMessage.Stop -> {
                runLog?.event("STOP", "from phone session=${message.sessionId}")
                machine.onStop(message.sessionId)
                if (machine.ui.value == WatchUiState.Ready) endService()
            }
            is WatchMessage.Heartbeat -> {
                machine.onHeartbeat(message.sessionId)
                if (activeSessionId != null) startSensor() // retry after a sensor failure
            }
            else -> Unit
        }
        if (activeSessionId == null) endService()
    }

    private fun handleStart(start: WatchMessage.Start, ack: WatchMessage.Ack) {
        val newSession = activeSessionId != start.sessionId
        activeSessionId = start.sessionId
        if (newSession) {
            runLog?.begin(start.sessionId)
            runLog?.event("START", "from phone; ack ok=${ack.ok} reason=${ack.reason}")
        }
        acquireWakeLock()
        if (newSession) {
            // Ack goes out before any queued Status/Sample, or the phone would answer them with Stop.
            outboxJob?.cancel()
            outboxJob = scope.launch {
                link.send(ack)
                machine.outbox.collect { message ->
                    if (!link.send(message)) {
                        runLog?.event("LINK", "send failed: ${message.javaClass.simpleName}")
                        machine.onSendFailed()
                    }
                }
            }
        } else {
            scope.launch { link.send(ack) } // the phone's resend of Start: repeat the Ack only
            startSensor() // retries if the sensor failed earlier; a no-op while it runs
            return
        }
        startSensor()
        if (tickJob == null) {
            tickJob = scope.launch {
                while (true) {
                    delay(TICK_MS)
                    wakeLock?.acquire(WAKE_LOCK_MS) // renewed while ticking so a dead service cannot hold the CPU
                    if (machine.tick()) {
                        Log.i(TAG, "No phone contact for the safety window; stopping")
                        runLog?.event("SAFETY", "no phone contact for the safety window; stopping")
                        endService()
                        break
                    }
                }
            }
        }
    }

    /** Starts the exercise unless one is running (it is kept across session changes); a failed one is retried. */
    private fun startSensor() = sensor.start(sensorListener)

    private val sensorListener = object : HeartRateSensor.Listener {
        override fun onBpm(bpm: Int) {
            runLog?.event("SAMPLE", "bpm=$bpm")
            machine.onSample(bpm)
        }
        override fun onContact(lost: Boolean) {
            runLog?.event("SENSOR", "contact lost=$lost")
            machine.onContact(lost)
        }
        override fun onFailed() {
            Log.w(TAG, "Heart-rate sensor failed")
            runLog?.event("SENSOR", "failed")
            machine.onContact(lost = true)
        }
    }

    /** One-shot Ack that survives the service being stopped right after; short-lived, so no lifecycle needed. */
    private fun sendAckDetached(ack: WatchMessage.Ack) {
        val appLink = PhoneLink(applicationContext)
        detachedScope.launch { appLink.send(ack) }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            // CPU only (never a screen lock): keeps the tick loop and keep-alive running with the screen off.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseMetronome:sensing")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(WAKE_LOCK_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** Stops sensing, tells an open WatchActivity to finish, and stops the service. */
    private fun endService() {
        activeSessionId = null
        tickJob?.cancel()
        tickJob = null
        outboxJob?.cancel()
        outboxJob = null
        sensor.stop()
        releaseWakeLock()
        finishRunLog()
        sendBroadcast(Intent(ACTION_FINISH_ACTIVITY).setPackage(packageName))
        // By id, so a Start that arrived meanwhile is not killed.
        stopSelf(lastStartId)
    }

    /** Closes the run's log (if one is open) and sends every pending log to the phone off the service's lifetime. */
    private fun finishRunLog() {
        val log = runLog ?: return
        val sender = logSender ?: return
        if (log.isActive()) {
            log.event("END", "run ended")
            runCatching { log.finish() }.onFailure { Log.w(TAG, "run log write failed", it) }
        }
        detachedScope.launch { sender.sendPending() }
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) return true
        try {
            startForeground(NOTIFICATION_ID, buildNotification(machine.ui.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (e: SecurityException) {
            // Missing BODY_SENSORS/READ_HEART_RATE: a health FGS cannot start without them.
            Log.w(TAG, "Health foreground service not permitted", e)
            return false
        } catch (e: RuntimeException) {
            // API 31+ throws ForegroundServiceStartNotAllowedException for a blocked background start.
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Background foreground start blocked", e)
                return false
            }
            throw e
        }
        foregroundStarted = true
        Log.i(TAG, "SensingService running in the foreground")
        return true
    }

    private fun updateNotification(state: WatchUiState) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: WatchUiState): Notification {
        val text = statusText(state)
        val touch = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pulse Metronome")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(touch)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_foreground)
            .setTouchIntent(touch)
            .setStatus(Status.Builder().addTemplate("#text#").addPart("text", Status.TextPart(text)).build())
            .build()
            .apply(applicationContext)
        return builder.build()
    }

    override fun onDestroy() {
        isRunning = false
        _uiState.value = WatchUiState.Ready
        tickJob?.cancel()
        sensor.stop()
        releaseWakeLock()
        finishRunLog()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SensingService"
        private const val CHANNEL_ID = "sensing"
        private const val NOTIFICATION_ID = 1
        // Keep-alive worst case is keepAliveMs (5 s) + one tick, so 2 s ticks keep it under the 10 s requirement.
        private const val TICK_MS = 2_000L
        // Longer than the 2 minute safety stop; each tick renews it.
        private const val WAKE_LOCK_MS = 150_000L
        private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val TYPE_START = "start"
        private const val TYPE_STOP = "stop"
        private const val TYPE_HEARTBEAT = "heartbeat"

        /** Broadcast (package-scoped) the watch activity listens for to close itself when sensing ends. */
        const val ACTION_FINISH_ACTIVITY = "com.austinlinux.pulsemetronome.wear.FINISH_ACTIVITY"

        /** Whether the heart-rate permission the sensor needs on this Android version is granted. */
        fun hasHeartPermission(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= 36) READ_HEART_RATE else Manifest.permission.BODY_SENSORS
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

        private val _uiState = MutableStateFlow<WatchUiState>(WatchUiState.Ready)

        /** The running session's UI state for the activity; back to Ready when the service ends. */
        val uiState: StateFlow<WatchUiState> = _uiState

        /** True while an instance exists; lets the listener skip Stop and Heartbeat when nothing is running. */
        @Volatile var isRunning = false
            private set

        /** Intent carrying [message] (Start, Stop or Heartbeat) for the service; null for other messages. */
        fun intentFor(context: Context, message: WatchMessage): Intent? {
            val type = when (message) {
                is WatchMessage.Start -> TYPE_START
                is WatchMessage.Stop -> TYPE_STOP
                is WatchMessage.Heartbeat -> TYPE_HEARTBEAT
                else -> return null
            }
            return Intent(context, SensingService::class.java).putExtra(EXTRA_TYPE, type).putExtra(EXTRA_SESSION_ID, message.sessionId)
        }

        /** Starts the foreground service with [message]; a blocked background start is logged, not thrown. */
        fun forward(context: Context, message: WatchMessage) {
            val intent = intentFor(context, message) ?: return
            try {
                if (message is WatchMessage.Start) {
                    context.startForegroundService(intent)
                } else if (isRunning) {
                    // Already foreground, so a plain start is allowed and needs no startForeground() answer.
                    context.startService(intent)
                }
            } catch (e: RuntimeException) {
                if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "Background start of SensingService was blocked", e)
                } else {
                    throw e
                }
            }
        }

        private fun messageFrom(intent: Intent): WatchMessage? {
            val sid = intent.getStringExtra(EXTRA_SESSION_ID) ?: return null
            return when (intent.getStringExtra(EXTRA_TYPE)) {
                TYPE_START -> WatchMessage.Start(sid)
                TYPE_STOP -> WatchMessage.Stop(sid)
                TYPE_HEARTBEAT -> WatchMessage.Heartbeat(sid)
                else -> null
            }
        }

        private fun statusText(state: WatchUiState) = when (state) {
            is WatchUiState.Sensing -> "${state.bpm} BPM"
            WatchUiState.ContactLost -> "Can't feel pulse"
            else -> "Finding pulse"
        }
    }
}
