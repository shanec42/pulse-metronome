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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.austinlinux.pulsemetronome.wear.R
import com.austinlinux.pulsemetronome.wear.link.PhoneLink
import com.austinlinux.pulsemetronome.wear.sensing.HealthServicesHeartRateSensor
import com.austinlinux.pulsemetronome.wear.sensing.HeartRateSensor
import com.austinlinux.pulsemetronome.wear.session.WatchSessionMachine
import com.austinlinux.pulsemetronome.wear.session.WatchUiState
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private var activeSessionId: String? = null
    private var tickJob: Job? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        link = PhoneLink(this)
        sensor = HealthServicesHeartRateSensor(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heart rate sensing", NotificationManager.IMPORTANCE_LOW),
        )
        scope.launch {
            machine.outbox.collect { message ->
                if (!link.send(message)) machine.onSendFailed()
            }
        }
        scope.launch {
            machine.ui.collect { state -> if (foregroundStarted) updateNotification(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.let(::messageFrom)
        val ack = (message as? WatchMessage.Start)?.let { machine.onStart(it.sessionId, hasHeartPermission()) }
        if (!ensureForeground()) {
            // Foreground start refused (typically permission): answer the phone, then give up.
            if (ack != null) sendAck(ack) { stopSelf() } else stopSelf()
            return START_NOT_STICKY
        }
        when (message) {
            is WatchMessage.Start -> handleStart(message, requireNotNull(ack))
            is WatchMessage.Stop -> {
                machine.onStop(message.sessionId)
                if (machine.ui.value == WatchUiState.Ready) endService()
            }
            is WatchMessage.Heartbeat -> machine.onHeartbeat(message.sessionId)
            else -> if (activeSessionId == null) endService()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(start: WatchMessage.Start, ack: WatchMessage.Ack) {
        sendAck(ack) { if (!ack.ok) endService() }
        if (!ack.ok) return
        // A resend of the running session only needs the repeated Ack.
        if (activeSessionId == start.sessionId) return
        activeSessionId = start.sessionId
        sensor.start(object : HeartRateSensor.Listener {
            override fun onBpm(bpm: Int) = machine.onSample(bpm)
            override fun onContact(lost: Boolean) = machine.onContact(lost)
            override fun onFailed() {
                Log.w(TAG, "Heart-rate sensor failed")
                machine.onContact(lost = true)
            }
        })
        if (tickJob == null) {
            tickJob = scope.launch {
                while (true) {
                    delay(TICK_MS)
                    if (machine.tick()) {
                        Log.i(TAG, "No phone contact for the safety window; stopping")
                        endService()
                        break
                    }
                }
            }
        }
    }

    private fun sendAck(ack: WatchMessage.Ack, then: () -> Unit) {
        scope.launch {
            link.send(ack)
            then()
        }
    }

    /** Stops sensing, tells an open WatchActivity to finish, and stops the service. */
    private fun endService() {
        activeSessionId = null
        tickJob?.cancel()
        tickJob = null
        sensor.stop()
        sendBroadcast(Intent(ACTION_FINISH_ACTIVITY).setPackage(packageName))
        stopSelf()
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
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val touch = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
        tickJob?.cancel()
        sensor.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun hasHeartPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 36) READ_HEART_RATE else Manifest.permission.BODY_SENSORS
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "SensingService"
        private const val CHANNEL_ID = "sensing"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 5_000L
        private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val TYPE_START = "start"
        private const val TYPE_STOP = "stop"
        private const val TYPE_HEARTBEAT = "heartbeat"

        /** Broadcast (package-scoped) the watch activity listens for to close itself when sensing ends. */
        const val ACTION_FINISH_ACTIVITY = "com.austinlinux.pulsemetronome.wear.FINISH_ACTIVITY"

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
                context.startForegroundService(intent)
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
