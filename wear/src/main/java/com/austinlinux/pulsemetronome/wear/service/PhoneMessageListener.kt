package com.austinlinux.pulsemetronome.wear.service

import android.app.ActivityManager
import android.util.Log
import com.austinlinux.pulsemetronome.wear.link.PhoneLink
import com.austinlinux.pulsemetronome.wear.sensing.HealthServicesHeartRateSensor
import com.austinlinux.pulsemetronome.wearlink.CantStartReason
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import com.austinlinux.pulsemetronome.wearlink.decodeWatchMessage
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only receiver of phone messages. It gates Start so startForegroundService() is never called when it
 * would fail (that crashes the process): no permission answers NO_PERMISSION here, a background app stays
 * silent so the phone's 3 s fallback opens the app, and everything else goes to [SensingService].
 */
class PhoneMessageListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val message = decodeWatchMessage(event.path, event.data) ?: return
        when (message) {
            is WatchMessage.Start -> onStart(message)
            is WatchMessage.Stop, is WatchMessage.Heartbeat -> {
                if (SensingService.isRunning) {
                    SensingService.forward(this, message)
                } else {
                    // No service to stop, but an exercise from a dead process may still be running.
                    HealthServicesHeartRateSensor.endOrphanExercise(this)
                }
            }
            else -> Unit
        }
    }

    private fun onStart(start: WatchMessage.Start) {
        if (SensingService.isRunning) {
            // Already foreground: a resend or a new session needs no new foreground start.
            SensingService.forward(this, start)
        } else if (!SensingService.hasHeartPermission(this)) {
            // onMessageReceived runs off the main thread, so blocking briefly for the send is fine.
            runBlocking {
                withTimeoutOrNull(ACK_TIMEOUT_MS) {
                    PhoneLink(this@PhoneMessageListener).send(WatchMessage.Ack(start.sessionId, false, CantStartReason.NO_PERMISSION))
                }
            }
        } else if (!isAppInForeground()) {
            // A while-in-use sensor FGS cannot start from the background. No Ack, so the phone opens the app and resends.
            Log.i(TAG, "App not in the foreground; ignoring Start")
        } else {
            SensingService.forward(this, start)
        }
    }

    private fun isAppInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val TAG = "PhoneMessageListener"
        const val ACK_TIMEOUT_MS = 4_000L
    }
}
