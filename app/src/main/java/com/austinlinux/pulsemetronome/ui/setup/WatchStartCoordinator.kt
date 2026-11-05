package com.austinlinux.pulsemetronome.ui.setup

import com.austinlinux.pulsemetronome.source.watch.StartResult
import com.austinlinux.pulsemetronome.source.watch.WatchAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Owns the watch availability check and start request behind Setup's Watch card, so a start the
 * user didn't finish (Cancel, leaving Setup, a failed session build) never leaves the watch sensing:
 * every path that drops an attached start calls [endSession].
 */
class WatchStartCoordinator(
    private val scope: CoroutineScope,
    private val availability: suspend () -> WatchAvailability,
    private val requestStart: suspend () -> StartResult,
    private val attach: (StartResult.Started) -> Unit,
    private val endSession: () -> Unit,
    private val setCard: (WatchCardState?) -> Unit,
    private val getCard: () -> WatchCardState?
) {
    // A channel (not a SharedFlow) so a start isn't lost between the Ack and the collector subscribing.
    private val events = Channel<Unit>(Channel.BUFFERED)

    /** Emits once per acknowledged start; the collector must answer with [consume]. */
    val started: Flow<Unit> = events.receiveAsFlow()
    private var job: Job? = null
    // True from attach until the Activity takes the event: the watch is sensing but no session exists yet.
    private var pending = false

    /** Re-runs the availability check; ignored while another check or start is running. */
    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            setCard(when (val a = availability()) {
                is WatchAvailability.Ready -> WatchCardState.Ready(a.node.displayName)
                WatchAvailability.NotFound -> WatchCardState.NotFound
            })
        }
    }

    /** Asks the watch to begin; on Ack attaches the source and emits [started]. */
    fun start() {
        if (getCard() == WatchCardState.Waiting) return
        job?.cancel()
        dropPending()
        setCard(WatchCardState.Waiting)
        job = scope.launch {
            when (val r = requestStart()) {
                is StartResult.Started -> {
                    // Must precede the service start so no early sample is missed.
                    attach(r)
                    pending = true
                    setCard(WatchCardState.Ready(r.node.displayName))
                    events.send(Unit)
                }
                is StartResult.CantStart -> setCard(WatchCardState.CantStart(r.reason))
                StartResult.Cancelled -> Unit
            }
        }
    }

    /**
     * Runs the Activity's session start for the last [started] event; [begin] returns false when no session
     * could be built, and the watch is then stopped.
     */
    fun consume(begin: () -> Boolean) {
        // Cleared before begin(): it navigates to Live, which stops Setup synchronously and calls leave().
        // With pending still set, leave() would send Stop to the watch the session is about to use.
        pending = false
        if (!begin()) endSession()
    }

    /** Cancel under "Waiting for watch…", also valid after the Ack but before the session started. */
    fun cancel() {
        val wasWaiting = getCard() == WatchCardState.Waiting
        job?.cancel()
        job = null
        val hadPending = dropPending()
        if (wasWaiting || hadPending) { setCard(null); refresh() }
    }

    /** Setup left the screen: abandon any unfinished start so nothing starts later. */
    fun leave() {
        job?.cancel()
        job = null
        val hadPending = dropPending()
        if (getCard() == WatchCardState.Waiting || hadPending) setCard(null)
    }

    /** Forgets the source switch's in-flight work without touching the card. */
    fun reset() {
        job?.cancel()
        job = null
        dropPending()
    }

    // Drains a queued start event and stops the watch if it was attached; true when there was one.
    private fun dropPending(): Boolean {
        while (events.tryReceive().isSuccess) { /* discard */ }
        if (!pending) return false
        pending = false
        endSession()
        return true
    }
}
