package com.austinlinux.pulsemetronome.source.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import com.austinlinux.pulsemetronome.wearlink.WearPaths
import com.austinlinux.pulsemetronome.wearlink.decodeWatchMessage
import com.austinlinux.pulsemetronome.wearlink.encode
import com.austinlinux.pulsemetronome.wearlink.path
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

data class WatchNode(val id: String, val displayName: String, val nearby: Boolean = false)

/** The phone's view of the Wear Data Layer; faked in unit tests. */
interface WatchTransport {
    suspend fun connectedNodes(): List<WatchNode>

    /** Nodes advertising [WearPaths.CAPABILITY], i.e. with the watch app installed. */
    suspend fun nodesWithApp(): List<WatchNode>
    suspend fun send(nodeId: String, msg: WatchMessage): Boolean

    /** Asks the watch to open the app (used when a Start gets no Ack); true if the request was accepted. */
    suspend fun openWatchApp(nodeId: String): Boolean

    /** nodeId to message; hot and app-scoped. */
    val incoming: Flow<Pair<String, WatchMessage>>

    /** sessionId to log bytes, from channel path `/pm/log/{sessionId}`. */
    val incomingLogs: Flow<Pair<String, ByteArray>>
}

/** Deep link the watch app's activity answers; RemoteActivityHelper only accepts ACTION_VIEW + BROWSABLE + data. */
const val WATCH_START_URI = "pulsemetronome://start"

class DataLayerWatchTransport(private val context: Context) : WatchTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messages = MutableSharedFlow<Pair<String, WatchMessage>>(extraBufferCapacity = 64)
    private val logs = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 16)
    override val incoming: Flow<Pair<String, WatchMessage>> = messages
    override val incomingLogs: Flow<Pair<String, ByteArray>> = logs

    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val channelClient: ChannelClient by lazy { Wearable.getChannelClient(context) }

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            val prefix = WearPaths.LOG_CHANNEL + "/"
            if (!channel.path.startsWith(prefix)) return
            val sessionId = channel.path.removePrefix(prefix)
            scope.launch {
                runCatching {
                    val bytes = channelClient.getInputStream(channel).await().use { it.readBytes() }
                    logs.tryEmit(sessionId to bytes)
                }
            }
        }
    }

    /** Registers the app-scoped Data Layer listeners; safe to call when Play services are missing. */
    fun start() {
        runCatching {
            messageClient.addListener { event ->
                decodeWatchMessage(event.path, event.data)?.let { messages.tryEmit(event.sourceNodeId to it) }
            }
            channelClient.registerChannelCallback(channelCallback)
        }
    }

    override suspend fun connectedNodes(): List<WatchNode> = safely(emptyList()) {
        Wearable.getNodeClient(context).connectedNodes.await().map { WatchNode(it.id, it.displayName, it.isNearby) }
    }

    override suspend fun nodesWithApp(): List<WatchNode> = safely(emptyList()) {
        Wearable.getCapabilityClient(context)
            .getCapability(WearPaths.CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.map { WatchNode(it.id, it.displayName, it.isNearby) }
    }

    override suspend fun send(nodeId: String, msg: WatchMessage): Boolean = safely(false) {
        messageClient.sendMessage(nodeId, msg.path(), msg.encode()).await()
        true
    }

    override suspend fun openWatchApp(nodeId: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse(WATCH_START_URI))
        return safely(false) {
            val future = RemoteActivityHelper(context).startRemoteActivity(intent, nodeId)
            suspendCancellableCoroutine { cont ->
                future.addListener({ cont.resume(runCatching { future.get() }.isSuccess) }, Runnable::run)
            }
        }
    }

    /** Returns [default] on any failure except cancellation, which must propagate. */
    private suspend fun <T> safely(default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }
}
