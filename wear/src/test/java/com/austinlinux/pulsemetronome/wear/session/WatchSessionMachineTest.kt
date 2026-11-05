package com.austinlinux.pulsemetronome.wear.session

import com.austinlinux.pulsemetronome.wearlink.CantStartReason
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import com.austinlinux.pulsemetronome.wearlink.WatchStatus
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSessionMachineTest {
    private var now = 1_000L
    private val machine = WatchSessionMachine(nowMs = { now })

    private fun takeOutbox(n: Int): List<WatchMessage> = runBlocking {
        withTimeoutOrNull(2_000) { machine.outbox.take(n).toList() } ?: emptyList()
    }

    @Test fun start_withoutPermission_ackNoPermission() {
        val ack = machine.onStart("s1", hasPermission = false)
        assertEquals(WatchMessage.Ack("s1", false, CantStartReason.NO_PERMISSION), ack)
        assertEquals(WatchUiState.PermissionDenied, machine.ui.value)
    }

    @Test fun start_ok_findingPulse_thenSensingOnSample() {
        val ack = machine.onStart("s1", hasPermission = true)
        assertEquals(WatchMessage.Ack("s1", true, null), ack)
        assertEquals(WatchUiState.FindingPulse, machine.ui.value)
        machine.onSample(72)
        assertEquals(WatchUiState.Sensing(72), machine.ui.value)
    }

    @Test fun stop_matchingSession_returnsReady() {
        machine.onStart("s1", true)
        machine.onSample(70)
        machine.onStop("s1")
        assertEquals(WatchUiState.Ready, machine.ui.value)
    }

    @Test fun stop_otherSession_ignored() {
        machine.onStart("s1", true)
        machine.onSample(70)
        machine.onStop("other")
        assertEquals(WatchUiState.Sensing(70), machine.ui.value)
    }

    @Test fun contactLost_thenSample_recovers() {
        machine.onStart("s1", true)
        machine.onSample(70)
        machine.onContact(lost = true)
        assertEquals(WatchUiState.ContactLost, machine.ui.value)
        machine.onSample(74)
        assertEquals(WatchUiState.Sensing(74), machine.ui.value)
    }

    @Test fun sendFailed_linkLost_thenSuccessfulSend_restoresSensing() {
        machine.onStart("s1", true)
        machine.onSample(70)
        machine.onSendFailed()
        assertEquals(WatchUiState.LinkLost, machine.ui.value)
        machine.onSample(71)
        assertEquals(WatchUiState.Sensing(71), machine.ui.value)
    }

    @Test fun noPhoneFor2Minutes_stops() {
        machine.onStart("s1", true)
        now += 119_999
        assertFalse(machine.tick())
        now += 1
        assertTrue(machine.tick())
        assertEquals(WatchUiState.StoppedTrying, machine.ui.value)
    }

    @Test fun heartbeatResetsSafetyTimer() {
        machine.onStart("s1", true)
        now += 100_000
        machine.onHeartbeat("s1")
        now += 100_000
        assertFalse(machine.tick())
        now += 20_000
        assertTrue(machine.tick())
    }

    @Test fun newStartWhileSensing_restartsWithNewSession() {
        machine.onStart("s1", true)
        machine.onSample(70)
        val ack = machine.onStart("s2", true)
        assertEquals(WatchMessage.Ack("s2", true, null), ack)
        assertEquals(WatchUiState.FindingPulse, machine.ui.value)
        machine.onStop("s1")
        assertEquals(WatchUiState.FindingPulse, machine.ui.value)
    }

    @Test fun repeatedStartSameSession_isNoOpAndAcksAgain() {
        machine.onStart("s1", true)
        machine.onSample(70)
        val ack = machine.onStart("s1", true)
        assertEquals(WatchMessage.Ack("s1", true, null), ack)
        assertEquals(WatchUiState.Sensing(70), machine.ui.value)
    }

    @Test fun outbox_tagsCurrentSessionId() {
        machine.onStart("s1", true)
        machine.onSample(70)
        machine.onStart("s2", true)
        machine.onSample(80)
        val sent = takeOutbox(4)
        assertEquals(WatchMessage.Status("s1", WatchStatus.FINDING_PULSE), sent[0])
        assertEquals(WatchMessage.Sample("s1", 70), sent[1])
        assertEquals(WatchMessage.Status("s2", WatchStatus.FINDING_PULSE), sent[2])
        assertEquals(WatchMessage.Sample("s2", 80), sent[3])
    }

    @Test fun tick_sendsKeepAliveStatusWhileSensing() {
        machine.onStart("s1", true)
        machine.onSample(70)
        takeOutbox(2)
        now += 5_000
        machine.onHeartbeat("s1")
        assertFalse(machine.tick())
        assertEquals(listOf<WatchMessage>(WatchMessage.Status("s1", WatchStatus.SENSING)), takeOutbox(1))
    }

    @Test fun keepAlive_gapNeverReaches10sAtWorstCaseTickPhase() {
        val tickMs = 2_000L // the service's tick period
        machine.onStart("s1", true)
        machine.onSample(70)
        takeOutbox(2)
        var lastSent = now
        var maxGap = 0L
        // Offset the first tick by 1 ms so keep-alive deadlines always fall just after a tick.
        now += 1
        repeat(30) {
            machine.onHeartbeat("s1")
            machine.tick()
            if (takeNonBlocking() > 0) {
                maxGap = maxOf(maxGap, now - lastSent)
                lastSent = now
            }
            now += tickMs
        }
        assertTrue("max silent gap was $maxGap ms", maxGap in 1..9_999)
        assertTrue("still silent at the end: ${now - lastSent} ms", now - lastSent < 10_000)
    }

    private fun takeNonBlocking(): Int = runBlocking {
        var n = 0
        while (withTimeoutOrNull(20) { machine.outbox.take(1).toList() }?.isNotEmpty() == true) n++
        n
    }
}
