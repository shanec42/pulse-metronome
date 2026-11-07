package com.austinlinux.pulsemetronome.source.watch

import com.austinlinux.pulsemetronome.ble.TimedPulse
import com.austinlinux.pulsemetronome.ble.protocol.PulseReading
import com.austinlinux.pulsemetronome.wearlink.WatchMessage
import com.austinlinux.pulsemetronome.wearlink.WatchStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchPulseSourceTest {
    private val node = WatchNode("n1", "Galaxy Watch")
    private val started = StartResult.Started(node, "sid")

    private class Rig(val t: FakeWatchTransport, val source: WatchPulseSource, val readings: MutableList<PulseReading>)

    private fun TestScope.rig(): Rig {
        val t = FakeWatchTransport().apply { connected = listOf(node); withApp = listOf(node) }
        val source = WatchPulseSource(
            WatchLink(t), t, backgroundScope,
            nowMs = { 1_000_000 + currentTime },
            elapsedMs = { 5_000 + currentTime }
        )
        val readings = mutableListOf<PulseReading>()
        source.readings.onEach { readings += it }.launchIn(backgroundScope)
        runCurrent()
        source.attach(started)
        runCurrent()
        return Rig(t, source, readings)
    }

    @Test fun sample_mapsToValid() = runTest {
        val r = rig()
        advanceTimeBy(100)
        r.t.deliver("n1", WatchMessage.Sample("sid", 64))
        runCurrent()
        assertTrue(r.readings.contains(PulseReading.Valid(64, null, null, 1_000_100)))
        assertEquals(TimedPulse(64, 5_100), r.source.latestValidPulse.value)
        assertNull(r.source.batteryPercent.value)
        assertEquals("Galaxy Watch", r.source.connectedDeviceName.value)
    }

    @Test fun statusContactLost_mapsToNoSignal_andSensorOffTrue() = runTest {
        val r = rig()
        r.t.deliver("n1", WatchMessage.Sample("sid", 64))
        runCurrent()
        r.t.deliver("n1", WatchMessage.Status("sid", WatchStatus.CONTACT_LOST))
        runCurrent()
        assertTrue(r.readings.last() is PulseReading.NoSignal)
        assertNull(r.source.latestValidPulse.value)
        var off = false
        r.source.sensorOffState.onEach { off = it }.launchIn(backgroundScope)
        runCurrent()
        assertTrue(off)
        r.t.deliver("n1", WatchMessage.Sample("sid", 70))
        runCurrent()
        assertFalse(off)
    }

    @Test fun firstSample_emitsConnected() = runTest {
        val r = rig()
        assertFalse(r.source.connectionState.value)
        r.t.deliver("n1", WatchMessage.Sample("sid", 64))
        runCurrent()
        assertEquals(PulseReading.Connected, r.readings.first())
        assertTrue(r.readings[1] is PulseReading.Valid)
        assertTrue(r.source.connectionState.value)
    }

    @Test fun silenceOver15s_emitsDisconnected() = runTest {
        val r = rig()
        r.t.deliver("n1", WatchMessage.Sample("sid", 64))
        runCurrent()
        advanceTimeBy(14_999)
        assertFalse(r.readings.contains(PulseReading.Disconnected))
        advanceTimeBy(2)
        assertEquals(PulseReading.Disconnected, r.readings.last())
        assertFalse(r.source.connectionState.value)
        assertNull(r.source.latestValidPulse.value)
        r.t.deliver("n1", WatchMessage.Sample("sid", 66))
        runCurrent()
        assertEquals(PulseReading.Connected, r.readings.takeLast(2).first())
        assertTrue(r.readings.last() is PulseReading.Valid)
        assertTrue(r.source.connectionState.value)
    }

    @Test fun noMessageWithin15sOfAttach_emitsDisconnected() = runTest {
        val r = rig()
        advanceTimeBy(14_999)
        assertFalse(r.readings.contains(PulseReading.Disconnected))
        advanceTimeBy(2)
        assertEquals(listOf(PulseReading.Disconnected), r.readings)
        assertFalse(r.source.connectionState.value)
        // Only once: a watch that stays silent does not repeat the loss every 15 s.
        advanceTimeBy(60_000)
        assertEquals(1, r.readings.count { it == PulseReading.Disconnected })
        // A late first message still connects normally.
        r.t.deliver("n1", WatchMessage.Status("sid", WatchStatus.FINDING_PULSE))
        runCurrent()
        assertEquals(PulseReading.Connected, r.readings.last())
        assertTrue(r.source.connectionState.value)
    }

    @Test fun sampleFromOtherSession_ignored() = runTest {
        val r = rig()
        r.t.deliver("n1", WatchMessage.Sample("other", 64))
        runCurrent()
        assertTrue(r.readings.isEmpty())
        assertFalse(r.source.connectionState.value)
    }

    @Test fun duplicateAck_ignored() = runTest {
        val r = rig()
        r.t.deliver("n1", WatchMessage.Ack("sid", true, null))
        r.t.deliver("n1", WatchMessage.Ack("sid", true, null))
        runCurrent()
        assertTrue(r.readings.isEmpty())
        assertFalse(r.source.connectionState.value)
    }

    @Test fun endSession_sendsStop_andStopsHeartbeat() = runTest {
        val r = rig()
        r.source.endSession()
        runCurrent()
        assertEquals(1, r.t.sentOf<WatchMessage.Stop>().size)
        advanceTimeBy(60_000)
        assertTrue(r.t.sentOf<WatchMessage.Heartbeat>().isEmpty())
    }

    @Test fun heartbeatEvery20s_whileAttached() = runTest {
        val r = rig()
        advanceTimeBy(19_999)
        assertEquals(0, r.t.sentOf<WatchMessage.Heartbeat>().size)
        advanceTimeBy(2)
        assertEquals(1, r.t.sentOf<WatchMessage.Heartbeat>().size)
        advanceTimeBy(40_000)
        assertEquals(3, r.t.sentOf<WatchMessage.Heartbeat>().size)
    }
}
