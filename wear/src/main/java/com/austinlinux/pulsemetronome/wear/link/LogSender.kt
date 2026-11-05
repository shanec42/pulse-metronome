package com.austinlinux.pulsemetronome.wear.link

import android.content.Context
import android.util.Log
import com.austinlinux.pulsemetronome.wear.log.WatchRunLog
import com.austinlinux.pulsemetronome.wearlink.WearPaths
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Sends finished run logs to the phone over a ChannelClient channel, marking each sent only after the transfer. */
class LogSender(context: Context, private val runLog: WatchRunLog) {
    private val app = context.applicationContext
    private val nodeClient = Wearable.getNodeClient(app)
    private val channelClient = Wearable.getChannelClient(app)

    /** Sends every pending log; a log that fails stays pending for the next run. */
    suspend fun sendPending() {
        val files = runLog.pending()
        if (files.isEmpty()) return
        try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) return
            for (f in files) {
                if (sendOne(nodes.map { it.id }, f)) runLog.markSent(f)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "log send failed", e)
        }
    }

    private suspend fun sendOne(nodeIds: List<String>, f: java.io.File): Boolean {
        val path = "${WearPaths.LOG_CHANNEL}/${WatchRunLog.sessionIdOf(f)}"
        var delivered = false
        for (id in nodeIds) {
            try {
                val channel = channelClient.openChannel(id, path).await()
                try {
                    channelClient.getOutputStream(channel).await().use { out -> f.inputStream().use { it.copyTo(out) } }
                    delivered = true
                } finally {
                    runCatching { channelClient.close(channel).await() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "log channel to $id failed", e)
            }
        }
        return delivered
    }

    private companion object {
        const val TAG = "LogSender"
    }
}
