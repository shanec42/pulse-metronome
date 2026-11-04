package com.austinlinux.pulsemetronome.wear

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Stub watch sensing service: proves a health foreground service can start from a phone message. */
class SensingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heart rate sensing", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pulse Metronome")
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (e: SecurityException) {
            // Missing BODY_SENSORS/READ_HEART_RATE: a health FGS cannot start without them.
            Log.w(TAG, "Health foreground service not permitted", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: RuntimeException) {
            // API 31+ throws ForegroundServiceStartNotAllowedException for a blocked background start.
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Background foreground start blocked", e)
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }
        Log.i(TAG, "SensingService running in the foreground")
        return START_NOT_STICKY
    }

    private companion object {
        const val TAG = "SensingService"
        const val CHANNEL_ID = "sensing"
        const val NOTIFICATION_ID = 1
    }
}
