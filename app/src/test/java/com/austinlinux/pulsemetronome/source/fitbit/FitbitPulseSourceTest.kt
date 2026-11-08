package com.austinlinux.pulsemetronome.source.fitbit

import com.austinlinux.pulsemetronome.ble.TimedPulse
import com.austinlinux.pulsemetronome.ble.protocol.PulseReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Counts starts/stops, returns a settable result, and flags a stop that lands while a start is still running. */
class FakeFitbitListener : FitbitListener {
    @Volatile var result = StartResult.STARTED
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    val overlapSeen = AtomicBoolean(false)
    private val inStart = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    override var isRunning: Boolean
        get() = running.get()
        set(value) = running.set(value)

    override fun start(): StartResult {
        if (!inStart.compareAndSet(false, true)) overlapSeen.set(true)
        starts.incrementAndGet()
        // Gives a concurrent stop() a chance to land inside this start if the caller does not serialise them.
        repeat(50) { Thread.yield() }
        val r = result
        if (r != StartResult.PORT_BUSY) running.set(true)
        inStart.set(false)
        return r
    }

    override fun stop() {
        if (inStart.get()) overlapSeen.set(true)
        stops.incrementAndGet()
        running.set(false)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FitbitPulseSourceTest {
    private class Rig(val fake: FakeFitbitListener, val source: FitbitPulseSource, val readings: MutableList<PulseReading>)

    private fun TestScope.rig(): Rig {
        val fake = FakeFitbitListener()
        val source = FitbitPulseSource(
            backgroundScope,
            nowMs = { 1_000_000 + currentTime },
            elapsedMs = { 5_000 + currentTime },
            listenerFactory = { fake }
        )
        val readings = mutableListOf<PulseReading>()
        source.readings.onEach { readings += it }.launchIn(backgroundScope)
        runCurrent()
        return Rig(fake, source, readings)
    }

    @Test fun hold_startsListener_statusWaiting() = runTest {
        val r = rig()
        assertEquals(FitbitStatus.Off, r.source.status.value)
        r.source.hold(HOLD_SETUP)
        assertEquals(1, r.fake.starts.get())
        assertEquals(FitbitStatus.Waiting, r.source.status.value)
        assertFalse(r.source.connectionState.value)
        assertNull(r.source.connectedDeviceName.value)
    }

    @Test fun firstBpm_emitsConnectedThenValid() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        advanceTimeBy(100)
        r.source.onRequest(HrRequest.Bpm(64))
        runCurrent()
        assertEquals(listOf(PulseReading.Connected, PulseReading.Valid(64, null, null, 1_000_100)), r.readings)
        assertEquals(TimedPulse(64, 5_100), r.source.latestValidPulse.value)
        assertEquals(FitbitStatus.Receiving(64), r.source.status.value)
        assertTrue(r.source.connectionState.value)
        assertEquals("Fitbit · HR Service", r.source.connectedDeviceName.value)
        assertNull(r.source.batteryPercent.value)
    }

    @Test fun zeroBpm_connectedButSensorOff() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.onRequest(HrRequest.Bpm(64))
        r.source.onRequest(HrRequest.Bpm(0))
        runCurrent()
        assertTrue(r.source.connectionState.value)
        assertTrue(r.source.sensorOffState.first())
        assertTrue(r.readings.last() is PulseReading.NoSignal)
        assertEquals(1, r.readings.count { it == PulseReading.Connected })
        assertNull(r.source.latestValidPulse.value)
        assertEquals(FitbitStatus.NoReading, r.source.status.value)
    }

    @Test fun silence_15s_disconnects() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.onRequest(HrRequest.Bpm(64))
        runCurrent()
        advanceTimeBy(15_001)
        assertEquals(PulseReading.Disconnected, r.readings.last())
        assertFalse(r.source.connectionState.value)
        assertNull(r.source.latestValidPulse.value)
        assertNull(r.source.connectedDeviceName.value)
        assertEquals(FitbitStatus.Waiting, r.source.status.value)
    }

    @Test fun silence_14s_staysConnected() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.onRequest(HrRequest.Bpm(64))
        runCurrent()
        advanceTimeBy(14_000)
        assertTrue(r.source.connectionState.value)
        assertFalse(r.readings.contains(PulseReading.Disconnected))
        // Each request re-arms the watchdog.
        r.source.onRequest(HrRequest.Bpm(65))
        advanceTimeBy(14_000)
        assertTrue(r.source.connectionState.value)
        assertEquals(FitbitStatus.Receiving(65), r.source.status.value)
    }

    @Test fun release_stopsAfterLinger() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.release(HOLD_SETUP)
        advanceTimeBy(4_999)
        assertEquals(0, r.fake.stops.get())
        advanceTimeBy(2)
        assertEquals(1, r.fake.stops.get())
        assertEquals(FitbitStatus.Off, r.source.status.value)
    }

    @Test fun handoff_withinLinger_keepsListenerOpen() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.onRequest(HrRequest.Bpm(64))
        r.source.release(HOLD_SETUP)
        advanceTimeBy(1_000)
        r.source.beginSession()
        advanceTimeBy(10_000)
        assertEquals(0, r.fake.stops.get())
        assertEquals(1, r.fake.starts.get())
        assertEquals(64, r.source.latestValidPulse.value?.bpm)
    }

    @Test fun portBusy_thenRetry_recovers() = runTest {
        val r = rig()
        r.fake.result = StartResult.PORT_BUSY
        r.source.hold(HOLD_SETUP)
        assertEquals(FitbitStatus.PortBusy, r.source.status.value)
        r.fake.result = StartResult.STARTED
        r.source.retryIfIdle()
        assertEquals(FitbitStatus.Waiting, r.source.status.value)
        assertEquals(2, r.fake.starts.get())
    }

    @Test fun portBusy_holdRetries() = runTest {
        val r = rig()
        r.fake.result = StartResult.PORT_BUSY
        r.source.hold(HOLD_SETUP)
        r.fake.result = StartResult.STARTED
        r.source.beginSession()
        assertEquals(FitbitStatus.Waiting, r.source.status.value)
    }

    @Test fun retryIfIdle_whileRunning_doesNotRestart() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.retryIfIdle()
        assertEquals(1, r.fake.starts.get())
    }

    @Test fun heldButListenerDied_retryIfIdle_restarts() = runTest {
        val r = rig()
        r.source.beginSession()
        r.source.onRequest(HrRequest.Bpm(64))
        r.fake.isRunning = false
        r.source.retryIfIdle()
        assertEquals(2, r.fake.starts.get())
        assertTrue(r.fake.isRunning)
        // Still connected until the watchdog says otherwise.
        assertEquals(FitbitStatus.Receiving(64), r.source.status.value)
    }

    @Test fun retryIfIdle_whenOff_doesNothing() = runTest {
        val r = rig()
        r.source.retryIfIdle()
        assertEquals(0, r.fake.starts.get())
        assertEquals(FitbitStatus.Off, r.source.status.value)
    }

    @Test fun twoHolders_releaseOne_keepsRunning() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.hold(HOLD_SESSION)
        r.source.release(HOLD_SETUP)
        advanceTimeBy(10_000)
        assertEquals(0, r.fake.stops.get())
        assertEquals(1, r.fake.starts.get())
    }

    @Test fun endSession_withoutBegin_isNoOp() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.endSession()
        advanceTimeBy(10_000)
        assertEquals(0, r.fake.stops.get())
        assertEquals(FitbitStatus.Waiting, r.source.status.value)
    }

    @Test fun releaseThenHoldDuringLinger_noDoubleStartOrStop() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.release(HOLD_SETUP)
        advanceTimeBy(2_000)
        r.source.hold(HOLD_SETUP)
        advanceTimeBy(10_000)
        assertEquals(1, r.fake.starts.get())
        assertEquals(0, r.fake.stops.get())
    }

    @Test fun noMessage_afterHold_doesNotEmitDisconnected() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        advanceTimeBy(20_000)
        assertTrue(r.readings.isEmpty())
        assertEquals(FitbitStatus.Waiting, r.source.status.value)
    }

    @Test fun stop_resetsSensorOff() = runTest {
        val r = rig()
        r.source.hold(HOLD_SETUP)
        r.source.onRequest(HrRequest.Bpm(0))
        r.source.release(HOLD_SETUP)
        advanceTimeBy(5_001)
        assertFalse(r.source.sensorOffState.first())
        assertFalse(r.source.connectionState.value)
        assertNull(r.source.connectedDeviceName.value)
    }

    @Test fun concurrentHoldRelease_isConsistent() {
        val fake = FakeFitbitListener()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = FitbitPulseSource(scope, nowMs = { System.currentTimeMillis() }, elapsedMs = { System.nanoTime() / 1_000_000 }) { fake }
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    go.await()
                    repeat(1_000) {
                        source.hold("owner-$t")
                        source.release("owner-$t")
                    }
                }
            }
            go.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            // Every owner is released, so the last linger must close the listener within FITBIT_LINGER_MS.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FITBIT_LINGER_MS + 5_000)
            while (source.status.value != FitbitStatus.Off && System.nanoTime() < deadline) Thread.sleep(20)

            assertEquals(FitbitStatus.Off, source.status.value)
            assertTrue(fake.starts.get() >= 1)
            assertEquals(0, fake.starts.get() - fake.stops.get())
            assertFalse(fake.overlapSeen.get())
        } finally {
            pool.shutdownNow()
            scope.cancel()
        }
    }
}
