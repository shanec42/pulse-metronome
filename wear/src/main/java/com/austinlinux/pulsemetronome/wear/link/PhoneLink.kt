package com.austinlinux.pulsemetronome.wear.link

import android.content.Context
import android.util.Log
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import com.austinlinux.pulsemetronome.wearlink.encode
import com.austinlinux.pulsemetronome.wearlink.path
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Sends [WatchMessage]s to the connected phone over the Data Layer. */
class PhoneLink(context: Context) {
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)
    private val messageClient = Wearable.getMessageClient(context.applicationContext)

    /** True if the message was handed to a connected node; false when no phone is connected or the send failed. */
    suspend fun send(message: WatchMessage): Boolean = try {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            false
        } else {
            nodes.forEach { messageClient.sendMessage(it.id, message.path(), message.encode()).await() }
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "send ${message.path()} failed", e)
        false
    }

    private companion object {
        const val TAG = "PhoneLink"
    }
}
