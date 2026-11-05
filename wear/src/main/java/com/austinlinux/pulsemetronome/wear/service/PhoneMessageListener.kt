package com.austinlinux.pulsemetronome.wear.service

import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import com.austinlinux.pulsemetronome.wearlink.decodeWatchMessage
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** The only receiver of phone messages; forwards Start, Stop and Heartbeat to [SensingService]. */
class PhoneMessageListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val message = decodeWatchMessage(event.path, event.data) ?: return
        when (message) {
            is WatchMessage.Start -> SensingService.forward(this, message)
            // Stop and Heartbeat only matter to a running service; starting one just to ignore them wastes a foreground start.
            is WatchMessage.Stop, is WatchMessage.Heartbeat -> if (SensingService.isRunning) SensingService.forward(this, message)
            else -> Unit
        }
    }
}
