package com.austinlinux.pulsemetronome.source.watch

import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Hand-written transport: records sends and lets a test inject incoming messages or react inside send(). */
class FakeWatchTransport : WatchTransport {
    var connected: List<WatchNode> = emptyList()
    var withApp: List<WatchNode> = emptyList()
    val sent = mutableListOf<Pair<String, WatchMessage>>()
    var openedApp = mutableListOf<String>()

    /** Called synchronously inside send(), before it returns; lets a test deliver an Ack "immediately". */
    var onSend: (suspend (String, WatchMessage) -> Unit)? = null

    val incomingFlow = MutableSharedFlow<Pair<String, WatchMessage>>(extraBufferCapacity = 64)
    override val incoming: Flow<Pair<String, WatchMessage>> = incomingFlow
    override val incomingLogs: Flow<Pair<String, ByteArray>> = MutableSharedFlow()

    override suspend fun connectedNodes() = connected
    override suspend fun nodesWithApp() = withApp
    override suspend fun send(nodeId: String, msg: WatchMessage): Boolean {
        sent += nodeId to msg
        onSend?.invoke(nodeId, msg)
        return true
    }

    override suspend fun openWatchApp(nodeId: String): Boolean {
        openedApp += nodeId
        return true
    }

    fun deliver(nodeId: String, msg: WatchMessage) {
        incomingFlow.tryEmit(nodeId to msg)
    }

    inline fun <reified T : WatchMessage> sentOf() = sent.map { it.second }.filterIsInstance<T>()
}
