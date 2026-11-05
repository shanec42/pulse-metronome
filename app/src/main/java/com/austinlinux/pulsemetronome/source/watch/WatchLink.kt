package com.austinlinux.pulsemetronome.source.watch

import com.austinlinux.pulsemetronome.wearlink.CantStartReason
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface WatchAvailability {
    data class Ready(val node: WatchNode) : WatchAvailability
    object NotFound : WatchAvailability
}

sealed interface StartResult {
    data class Started(val node: WatchNode, val sessionId: String) : StartResult
    data class CantStart(val reason: CantStartReason) : StartResult
    object Cancelled : StartResult
}

/** Start/stop handshake with the watch app over a [WatchTransport]. */
class WatchLink(
    private val transport: WatchTransport,
    private val ackTimeoutMs: Long = 10_000,
    private val openAppAfterMs: Long = 3_000,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() }
) {
    private val nodeBySession = ConcurrentHashMap<String, String>()

    /** Ready with the preferred watch node if one has the app installed. */
    suspend fun availability(): WatchAvailability =
        pick(transport.nodesWithApp())?.let { WatchAvailability.Ready(it) } ?: WatchAvailability.NotFound

    /** Sends Start and waits for the Ack; cancelling the caller yields [StartResult.Cancelled]. */
    suspend fun requestStart(): StartResult {
        val node = pick(transport.nodesWithApp())
            ?: return StartResult.CantStart(
                if (transport.connectedNodes().isEmpty()) CantStartReason.NOT_REACHABLE else CantStartReason.APP_MISSING
            )
        val sid = newSessionId()
        nodeBySession[sid] = node.id
        val ack = try {
            withTimeoutOrNull(ackTimeoutMs) { sendAndAwaitAck(node, sid) }
        } catch (e: CancellationException) {
            giveUp(node, sid)
            return StartResult.Cancelled
        }
        if (ack == null) {
            giveUp(node, sid)
            return StartResult.CantStart(CantStartReason.NOT_REACHABLE)
        }
        if (!ack.ok) {
            nodeBySession.remove(sid)
            return StartResult.CantStart(ack.reason ?: CantStartReason.NOT_REACHABLE)
        }
        return StartResult.Started(node, sid)
    }

    /** Tells the watch to stop [sessionId]; best effort. */
    suspend fun stop(sessionId: String) {
        val nodeId = nodeBySession.remove(sessionId)
        val targets = if (nodeId != null) listOf(nodeId) else transport.nodesWithApp().map { it.id }
        targets.forEach { transport.send(it, WatchMessage.Stop(sessionId)) }
    }

    private suspend fun sendAndAwaitAck(node: WatchNode, sid: String): WatchMessage.Ack = coroutineScope {
        // UNDISPATCHED so the collector is subscribed before Start goes out; an instant Ack is then not lost.
        val ack = async(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming
                .mapNotNull { (from, m) -> (m as? WatchMessage.Ack)?.takeIf { from == node.id && it.sessionId == sid } }
                .first()
        }
        transport.send(node.id, WatchMessage.Start(sid))
        // No Ack soon: the watch app may not be running, so ask the OS to open it, then send Start once more.
        withTimeoutOrNull(openAppAfterMs) { ack.await() } ?: run {
            transport.openWatchApp(node.id)
            transport.send(node.id, WatchMessage.Start(sid))
            ack.await()
        }
    }

    private suspend fun giveUp(node: WatchNode, sid: String) {
        nodeBySession.remove(sid)
        withContext(NonCancellable) { transport.send(node.id, WatchMessage.Stop(sid)) }
    }

    private fun pick(nodes: List<WatchNode>): WatchNode? = nodes.firstOrNull { it.nearby } ?: nodes.firstOrNull()
}
