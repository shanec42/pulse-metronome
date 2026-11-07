package com.austinlinux.pulsemetronome.ui.setup

import com.austinlinux.pulsemetronome.source.watch.StartResult
import com.austinlinux.pulsemetronome.source.watch.WatchAvailability
import com.austinlinux.pulsemetronome.source.watch.WatchNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchStartCoordinatorTest {
    private val node = WatchNode("n1", "Galaxy Watch")
    private val started = StartResult.Started(node, "sid")

    private class Rig(val c: WatchStartCoordinator, val ack: CompletableDeferred<StartResult>) {
        var card: WatchCardState? = null
        var attached = 0
        var ended = 0
        var events = 0
    }

    private fun TestScope.rig(collect: Boolean = true): Rig {
        val ack = CompletableDeferred<StartResult>()
        lateinit var r: Rig
        val c = WatchStartCoordinator(
            scope = backgroundScope,
            availability = { WatchAvailability.Ready(node) },
            requestStart = { ack.await() },
            attach = { r.attached++ },
            endSession = { r.ended++ },
            setCard = { r.card = it },
            getCard = { r.card }
        )
        r = Rig(c, ack)
        if (collect) c.started.onEach { r.events++ }.launchIn(backgroundScope)
        return r
    }

    @Test fun start_thenAck_attachesAndEmitsOnce() = runTest {
        val r = rig()
        r.c.start(); runCurrent()
        assertEquals(WatchCardState.Waiting, r.card)
        r.ack.complete(started); runCurrent()
        assertEquals(1, r.attached); assertEquals(1, r.events); assertEquals(0, r.ended)
        r.c.consume { true }
        r.c.leave()
        assertEquals("a consumed start must not be stopped on leaving Setup", 0, r.ended)
    }

    @Test fun leavingWhileWaiting_cancelsTheRequest_andLaterAckDoesNothing() = runTest {
        val r = rig()
        r.c.start(); runCurrent()
        r.c.leave(); runCurrent()
        assertNull(r.card)
        r.ack.complete(started); runCurrent()
        assertEquals(0, r.attached); assertEquals(0, r.events); assertEquals(0, r.ended)
    }

    @Test fun cancelAfterStarted_beforeConsumed_endsSession_andDropsEvent() = runTest {
        val r = rig(collect = false)
        r.c.start(); runCurrent()
        r.ack.complete(started); runCurrent()
        // Cancel lands while the event is queued, before the Activity's collector saw it.
        r.c.cancel()
        runCurrent()
        assertEquals(1, r.ended)
        r.c.started.onEach { r.events++ }.launchIn(backgroundScope)
        runCurrent()
        assertEquals("the queued event must be discarded", 0, r.events)
    }

    @Test fun leavingAfterStarted_beforeConsumed_endsSession() = runTest {
        val r = rig()
        r.c.start(); runCurrent()
        r.ack.complete(started); runCurrent()
        // The collector already saw the event but the Activity never answered (Setup was disposed).
        r.c.leave()
        assertEquals(1, r.ended)
        assertNull(r.card)
    }

    @Test fun failedSessionBuild_endsSession() = runTest {
        val r = rig()
        r.c.start(); runCurrent()
        r.ack.complete(started); runCurrent()
        r.c.consume { false }
        assertEquals(1, r.ended)
    }

    @Test fun setupLeftWhileTheSessionStarts_keepsTheWatchRunning() = runTest {
        val r = rig()
        r.c.start(); runCurrent()
        r.ack.complete(started); runCurrent()
        // Live-test root cause: beginSession() navigates to Live, which stops Setup synchronously and calls leave().
        r.c.consume { r.c.leave(); true }
        assertEquals("a start that became a session must not send Stop", 0, r.ended)
    }

    @Test fun consumeAfterLeave_doesNotBeginASession() = runTest {
        val r = rig()
        r.c.start(); runCurrent()
        r.ack.complete(started); runCurrent()
        r.c.leave()
        var began = false
        r.c.consume { began = true; true }
        assertEquals(false, began)
        assertEquals(1, r.ended)
    }

    @Test fun cancelWhileWaiting_returnsToAvailability() = runTest {
        val r = rig()
        r.c.start(); runCurrent()
        r.c.cancel(); runCurrent()
        assertEquals(WatchCardState.Ready("Galaxy Watch"), r.card)
        assertEquals(0, r.ended)
    }
}
