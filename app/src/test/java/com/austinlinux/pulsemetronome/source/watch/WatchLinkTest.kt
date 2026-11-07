package com.austinlinux.pulsemetronome.source.watch

import com.austinlinux.pulsemetronome.wearlink.CantStartReason
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLinkTest {
    private val node = WatchNode("n1", "Galaxy Watch")
    private fun fake() = FakeWatchTransport().apply { connected = listOf(node); withApp = listOf(node) }
    private fun link(t: FakeWatchTransport) = WatchLink(t, newSessionId = { "sid" })

    @Test fun availability_noAppNode_notFound() = runTest {
        val t = FakeWatchTransport().apply { connected = listOf(node) }
        assertEquals(WatchAvailability.NotFound, link(t).availability())
    }

    @Test fun availability_appNode_ready() = runTest {
        assertEquals(WatchAvailability.Ready(node), link(fake()).availability())
    }

    @Test fun requestStart_noConnectedNodes_notReachable() = runTest {
        val r = link(FakeWatchTransport()).requestStart()
        assertEquals(StartResult.CantStart(CantStartReason.NOT_REACHABLE), r)
    }

    @Test fun requestStart_connectedButNoApp_appMissing() = runTest {
        val t = FakeWatchTransport().apply { connected = listOf(node) }
        assertEquals(StartResult.CantStart(CantStartReason.APP_MISSING), link(t).requestStart())
    }

    @Test fun ackTimeout_yieldsNotReachable() = runTest {
        val t = fake()
        val r = async { link(t).requestStart() }
        runCurrent()
        advanceTimeBy(9_999)
        assertTrue(r.isActive)
        advanceTimeBy(2)
        assertEquals(StartResult.CantStart(CantStartReason.NOT_REACHABLE), r.await())
        assertEquals(1, t.sentOf<WatchMessage.Stop>().size)
    }

    @Test fun ackNoPermission_yieldsNoPermission() = runTest {
        val t = fake()
        val r = async { link(t).requestStart() }
        runCurrent()
        t.deliver("n1", WatchMessage.Ack("sid", false, CantStartReason.NO_PERMISSION))
        assertEquals(StartResult.CantStart(CantStartReason.NO_PERMISSION), r.await())
    }

    @Test fun ackOk_started() = runTest {
        val t = fake()
        val r = async { link(t).requestStart() }
        runCurrent()
        t.deliver("n1", WatchMessage.Ack("sid", true, null))
        assertEquals(StartResult.Started(node, "sid"), r.await())
        assertTrue(t.sentOf<WatchMessage.Stop>().isEmpty())
    }

    @Test fun ackArrivesImmediatelyAfterSend_started() = runTest {
        val t = fake()
        t.onSend = { id, m -> if (m is WatchMessage.Start) t.deliver(id, WatchMessage.Ack(m.sessionId, true, null)) }
        assertEquals(StartResult.Started(node, "sid"), link(t).requestStart())
    }

    private fun stopsOf(t: FakeWatchTransport) = t.sent.filter { it.second is WatchMessage.Stop }

    @Test fun cancel_afterLateAck_sendsStop() = runTest {
        val t = fake()
        val l = link(t).also { it.startResponder(backgroundScope) }
        val r = async { l.requestStart() }
        runCurrent()
        r.cancel()
        runCurrent()
        val before = stopsOf(t).size
        t.deliver("n1", WatchMessage.Ack("sid", true, null))
        runCurrent()
        assertEquals(before + 1, stopsOf(t).size)
        assertEquals("n1" to WatchMessage.Stop("sid"), stopsOf(t).last())
    }

    @Test fun ackTimeout_thenLateAck_sendsStop() = runTest {
        val t = fake()
        val l = link(t).also { it.startResponder(backgroundScope) }
        val r = async { l.requestStart() }
        advanceTimeBy(10_001)
        r.await()
        val before = stopsOf(t).size
        advanceTimeBy(6_000)
        t.deliver("n1", WatchMessage.Ack("sid", true, null))
        runCurrent()
        assertEquals(before + 1, stopsOf(t).size)
        // Rate limited: an immediate Sample from the same stale session gets no second Stop.
        t.deliver("n1", WatchMessage.Sample("sid", 70))
        runCurrent()
        assertEquals(before + 1, stopsOf(t).size)
    }

    @Test fun responder_ignoresInFlightAndStartedSessions() = runTest {
        val t = fake()
        val l = link(t).also { it.startResponder(backgroundScope) }
        val r = async { l.requestStart() }
        runCurrent()
        t.deliver("n1", WatchMessage.Ack("sid", true, null))
        assertEquals(StartResult.Started(node, "sid"), r.await())
        t.deliver("n1", WatchMessage.Sample("sid", 70))
        t.deliver("n1", WatchMessage.Status("sid", com.austinlinux.pulsemetronome.wearlink.WatchStatus.SENSING))
        runCurrent()
        assertTrue(stopsOf(t).isEmpty())
    }

    @Test fun requestStart_ackDuringOpenWatchApp_doesNotResend() = runTest {
        val t = fake()
        t.onOpen = { id -> t.deliver(id, WatchMessage.Ack("sid", true, null)) }
        val r = async { link(t).requestStart() }
        advanceTimeBy(3_001)
        assertEquals(StartResult.Started(node, "sid"), r.await())
        assertEquals(1, t.sentOf<WatchMessage.Start>().size)
    }

    @Test fun requestStart_noAckIn3s_opensWatchAppAndResends() = runTest {
        val t = fake()
        val r = async { link(t).requestStart() }
        runCurrent()
        advanceTimeBy(2_999)
        assertTrue(t.openedApp.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("n1"), t.openedApp)
        assertEquals(2, t.sentOf<WatchMessage.Start>().size)
        t.deliver("n1", WatchMessage.Ack("sid", true, null))
        assertEquals(StartResult.Started(node, "sid"), r.await())
        assertEquals(1, t.openedApp.size)
    }

    @Test fun requestStart_noAckAfterOpen_resendsEvery2sUntilAck() = runTest {
        val t = fake()
        val r = async { link(t).requestStart() }
        runCurrent()
        advanceTimeBy(3_001)
        assertEquals(2, t.sentOf<WatchMessage.Start>().size)
        advanceTimeBy(2_000)
        assertEquals(3, t.sentOf<WatchMessage.Start>().size)
        advanceTimeBy(2_000)
        assertEquals(4, t.sentOf<WatchMessage.Start>().size)
        t.deliver("n1", WatchMessage.Ack("sid", true, null))
        assertEquals(StartResult.Started(node, "sid"), r.await())
        advanceTimeBy(6_000)
        assertEquals(4, t.sentOf<WatchMessage.Start>().size)
    }

    @Test fun requestStart_ackWithin3s_doesNotOpenWatchApp() = runTest {
        val t = fake()
        val r = async { link(t).requestStart() }
        runCurrent()
        advanceTimeBy(2_000)
        t.deliver("n1", WatchMessage.Ack("sid", true, null))
        r.await()
        advanceTimeBy(5_000)
        assertTrue(t.openedApp.isEmpty())
        assertEquals(1, t.sentOf<WatchMessage.Start>().size)
    }

    @Test fun requestStart_prefersNearbyNode() = runTest {
        val far = WatchNode("far", "Far")
        val near = WatchNode("near", "Near", nearby = true)
        val t = FakeWatchTransport().apply { connected = listOf(far, near); withApp = listOf(far, near) }
        val r = async { link(t).requestStart() }
        runCurrent()
        assertEquals("near", t.sent.first().first)
        r.cancel()
    }
}
