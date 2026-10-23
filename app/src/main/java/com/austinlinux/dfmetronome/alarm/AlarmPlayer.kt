package com.austinlinux.dfmetronome.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.austinlinux.dfmetronome.R

const val ALARM_RING_DURATION_MS: Long = 15_000L

private const val ALARM_CHANNEL_ID = "session_alarm"
private const val ALARM_NOTIFICATION_ID = 2001
const val ACTION_DISMISS_ALARM = "com.austinlinux.dfmetronome.action.DISMISS_ALARM"

class AlarmPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null
    private var receiverRegistered = false

    // Lets the service clear the in-app alarm strip when the notification's Dismiss (or auto-stop) silences the alarm.
    var onDismissed: (() -> Unit)? = null

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS_ALARM) dismiss()
        }
    }

    /**
     * Plays via MediaPlayer using AudioAttributes.USAGE_ALARM (routes through the STREAM_ALARM
     * equivalent, audible even if media volume is muted). Triggers Vibrator per AlarmType.
     * Posts a dismissible notification. Auto-stops after ALARM_RING_DURATION_MS or on dismiss,
     * whichever first. SILENT plays nothing and vibrates nothing but still lets the caller
     * proceed with normal session-end bookkeeping (i.e. this method still returns normally).
     */
    // play() runs on the service's background dispatcher while dismiss() can come from the main
    // thread (notification receiver, auto-stop); synchronizing them keeps the player/receiver
    // state consistent, and a dismiss arriving mid-play() simply runs right after it.
    @Synchronized
    fun play(type: AlarmType) {
        ensureChannel()
        registerDismissReceiver()

        if (type == AlarmType.SOUND || type == AlarmType.SOUND_AND_VIBRATE) {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        }

        if (type == AlarmType.VIBRATE_ONLY || type == AlarmType.SOUND_AND_VIBRATE) {
            val pattern = longArrayOf(0, 500, 500)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }

        if (type != AlarmType.SILENT) {
            postNotification()
            autoStopRunnable = Runnable { dismiss() }.also {
                mainHandler.postDelayed(it, ALARM_RING_DURATION_MS)
            }
        }
    }

    @Synchronized
    fun dismiss() {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        vibrator.cancel()
        notificationManager.cancel(ALARM_NOTIFICATION_ID)
        unregisterDismissReceiver()
        onDismissed?.invoke()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "Session Alarm",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun postNotification() {
        val dismissIntent = Intent(ACTION_DISMISS_ALARM).setPackage(context.packageName)
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, 0, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setContentTitle("Session complete")
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(context.getColor(R.color.pm_red))
            .setOngoing(true)
            .addAction(0, "Dismiss", dismissPendingIntent)
            .build()
        notificationManager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun registerDismissReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_DISMISS_ALARM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(dismissReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterDismissReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(dismissReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered - ignore.
        }
        receiverRegistered = false
    }
}
