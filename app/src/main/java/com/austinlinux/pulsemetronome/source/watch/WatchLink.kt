package com.austinlinux.pulsemetronome.source.watch

import com.austinlinux.pulsemetronome.wearlink.CantStartReason
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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

private const val STOP_REPLY_INTERVAL_MS = 5_000L
private const val START_RESEND_INTERVAL_MS = 2_000L

/** Start/stop handshake with the watch app over a [WatchTransport]. */
class WatchLink(
    private val transport: WatchTransport,
    private val ackTimeoutMs: Long = 10_000,
    private val openAppAfterMs: Long = 3_000,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    // Sessions in flight or started and not yet stopped; anything else the watch talks about is stale.
    private val nodeBySession = ConcurrentHashMap<String, String>()
    private val lastStopMs = HashMap<String, Long>()

    /**
     * Answers Ack/Sample/Status from a session this phone no longer knows with Stop, at most once per 5 s per
     * session, so a watch that acked after we gave up (or missed our Stop) doesn't keep sensing.
     */
    fun startResponder(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        transport.incoming.collect { (from, m) ->
            if (m !is WatchMessage.Ack && m !is WatchMessage.Sample && m !is WatchMessage.Status) return@collect
            val sid = m.sessionId
            if (nodeBySession.containsKey(sid)) return@collect
            val now = clockMs()
            val last = lastStopMs[sid]
            if (last != null && now - last < STOP_REPLY_INTERVAL_MS) return@collect
            if (lastStopMs.size > 64) lastStopMs.entries.removeAll { now - it.value >= STOP_REPLY_INTERVAL_MS }
            lastStopMs[sid] = now
            transport.send(from, WatchMessage.Stop(sid))
        }
    }

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
        // No Ack soon: the watch app may not be running, so ask the OS to open it, then keep resending Start.
        withTimeoutOrNull(openAppAfterMs) { ack.await() } ?: run {
            if (!ack.isCompleted) transport.openWatchApp(node.id)
            // The Ack may have landed while the open request was suspended; yield lets the collector see it.
            yield()
            // The open future completes when the watch issues startActivity, not when it is resumed, so a cold
            // start can drop the first resend at the foreground gate; repeat Starts are no-op re-Acks.
            var result: WatchMessage.Ack? = null
            while (result == null && !ack.isCompleted) {
                transport.send(node.id, WatchMessage.Start(sid))
                result = withTimeoutOrNull(START_RESEND_INTERVAL_MS) { ack.await() }
            }
            result ?: ack.await()
        }
    }

    private suspend fun giveUp(node: WatchNode, sid: String) {
        nodeBySession.remove(sid)
        withContext(NonCancellable) { transport.send(node.id, WatchMessage.Stop(sid)) }
    }

    private fun pick(nodes: List<WatchNode>): WatchNode? = nodes.firstOrNull { it.nearby } ?: nodes.firstOrNull()
}
