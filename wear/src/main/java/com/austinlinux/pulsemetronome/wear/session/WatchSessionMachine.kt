package com.austinlinux.pulsemetronome.wear.session

import com.austinlinux.pulsemetronome.wearlink.CantStartReason
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import com.austinlinux.pulsemetronome.wearlink.WatchStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

sealed interface WatchUiState {
    object NeedsPermission : WatchUiState
    object PermissionDenied : WatchUiState
    object Ready : WatchUiState
    object FindingPulse : WatchUiState
    data class Sensing(val bpm: Int) : WatchUiState
    object ContactLost : WatchUiState
    object LinkLost : WatchUiState
    object StoppedTrying : WatchUiState
}

/**
 * Pure watch-side session state. Not thread-safe: the service drives it from one thread (main).
 * [nowMs] must be monotonic (elapsedRealtime), not wall-clock.
 */
class WatchSessionMachine(
    private val nowMs: () -> Long,
    private val safetyStopMs: Long = 120_000,
    // The phone calls 15 s of silence Disconnected, so something must go out well inside that window.
    private val keepAliveMs: Long = 8_000,
) {
    private val _ui = MutableStateFlow<WatchUiState>(WatchUiState.Ready)
    val ui: StateFlow<WatchUiState> = _ui

    private val channel = Channel<WatchMessage>(Channel.UNLIMITED)
    /** Sample and Status messages for the phone, tagged with the current session ID. */
    val outbox: Flow<WatchMessage> = channel.receiveAsFlow()

    private var sessionId: String? = null
    private var lastPhoneMs = 0L
    private var lastSentMs = 0L
    private var linkLost = false
    private var base: WatchUiState = WatchUiState.Ready

    /** Handles Start; a repeat of the active session is a no-op that returns the same Ack. */
    fun onStart(sessionId: String, hasPermission: Boolean): WatchMessage.Ack {
        if (!hasPermission) {
            if (this.sessionId == sessionId) this.sessionId = null
            publish(WatchUiState.PermissionDenied)
            return WatchMessage.Ack(sessionId, false, CantStartReason.NO_PERMISSION)
        }
        val ack = WatchMessage.Ack(sessionId, true, null)
        if (this.sessionId == sessionId) {
            lastPhoneMs = nowMs()
            return ack
        }
        this.sessionId = sessionId
        lastPhoneMs = nowMs()
        linkLost = false
        publish(WatchUiState.FindingPulse)
        send(WatchMessage.Status(sessionId, WatchStatus.FINDING_PULSE))
        return ack
    }

    /** Ends the session if [sessionId] is the current one; other IDs are ignored. */
    fun onStop(sessionId: String) {
        if (this.sessionId != sessionId) return
        this.sessionId = null
        linkLost = false
        publish(WatchUiState.Ready)
    }

    /** A heartbeat from the phone proves the link, so it resets the safety timer and clears LinkLost. */
    fun onHeartbeat(sessionId: String) {
        if (this.sessionId != sessionId) return
        lastPhoneMs = nowMs()
        if (linkLost) {
            linkLost = false
            publish(base)
        }
    }

    /** A new heart-rate reading; also restores Sensing after LinkLost or ContactLost. */
    fun onSample(bpm: Int) {
        val sid = sessionId ?: return
        linkLost = false
        publish(WatchUiState.Sensing(bpm))
        send(WatchMessage.Sample(sid, bpm))
    }

    /** The sensor lost or regained skin contact. */
    fun onContact(lost: Boolean) {
        val sid = sessionId ?: return
        if (lost) {
            if (base == WatchUiState.ContactLost) return
            publish(WatchUiState.ContactLost)
            send(WatchMessage.Status(sid, WatchStatus.CONTACT_LOST))
        } else if (base == WatchUiState.ContactLost) {
            // Contact is back but there is no reading yet.
            publish(WatchUiState.FindingPulse)
            send(WatchMessage.Status(sid, WatchStatus.FINDING_PULSE))
        }
    }

    /** The last message to the phone failed to send; the next sample restores the state. */
    fun onSendFailed() {
        if (sessionId == null) return
        linkLost = true
        _ui.value = WatchUiState.LinkLost
    }

    /** Periodic check; true means the phone has been silent too long and sensing must stop now. */
    fun tick(): Boolean {
        val sid = sessionId ?: return false
        val now = nowMs()
        if (now - lastPhoneMs >= safetyStopMs) {
            sessionId = null
            linkLost = false
            publish(WatchUiState.StoppedTrying)
            return true
        }
        if (now - lastSentMs >= keepAliveMs) {
            // A status rather than the last bpm, so a stalled sensor never re-sends a stale reading as fresh.
            send(WatchMessage.Status(sid, statusFor(base)))
        }
        return false
    }

    private fun statusFor(state: WatchUiState) = when (state) {
        is WatchUiState.Sensing -> WatchStatus.SENSING
        WatchUiState.ContactLost -> WatchStatus.CONTACT_LOST
        else -> WatchStatus.FINDING_PULSE
    }

    private fun publish(state: WatchUiState) {
        base = state
        _ui.value = if (linkLost) WatchUiState.LinkLost else state
    }

    private fun send(message: WatchMessage) {
        lastSentMs = nowMs()
        channel.trySend(message)
    }
}
