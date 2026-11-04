package com.austinlinux.pulsemetronome.wear

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.austinlinux.pulsemetronome.wearlink.WearPaths
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives phone messages in the background; a Start message launches [SensingService]. */
class PhoneMessageListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WearPaths.START) startSensing(this)
    }

    private fun startSensing(context: Context) {
        try {
            context.startForegroundService(Intent(context, SensingService::class.java))
        } catch (e: RuntimeException) {
            // API 31+ throws ForegroundServiceStartNotAllowedException when the background start is blocked.
            if (Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Background start of SensingService was blocked", e)
            } else {
                throw e
            }
        }
    }

    private companion object {
        const val TAG = "PhoneMessageListener"
    }
}
