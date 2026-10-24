package com.austinlinux.pulsemetronome.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEngineTest {

    private fun defaultConfig() = SessionConfig(
        targetBpm = 60.0,
        totalDurationMin = 60.0,
        rampDurationMin = 60.0,
        maxOffset = 2.0,
        staticFallbackStartingBpm = 70.0
    )

    private fun feedThreeReadings(engine: SessionEngine, clock: FakeMonotonicClock, pulse: Int) {
        repeat(3) {
            clock.advanceMs(1000L)
            engine.onPulseReading(rawPulse = pulse, atMs = clock.nowMs())
        }
    }

    @Test
    fun `WHEN_PULSE_DETECTED mode requires 3 consecutive valid readings before resolving starting bpm`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        clock.advanceMs(1000L)
        engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        assertNull(states.last().startingBpm)
        assertEquals(1, states.last().verificationStreakCount)

        clock.advanceMs(1000L)
        engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        assertNull(states.last().startingBpm)
        assertEquals(2, states.last().verificationStreakCount)

        clock.advanceMs(1000L)
        engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        assertEquals(90.0, states.last().startingBpm!!, 0.0001)
        assertEquals(SessionState.Phase.RUNNING, states.last().phase)
    }

    @Test
    fun `starting bpm is the mean of the 3 verification readings`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 89, atMs = clock.nowMs())
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 91, atMs = clock.nowMs())

        assertEquals(90.0, states.last().startingBpm!!, 0.0001)  // (89+90+91)/3 = 90.0
    }

    @Test
    fun `a reading deviating more than 1 bpm resets the verification streak to zero`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 255, atMs = clock.nowMs())
        assertEquals(1, states.last().verificationStreakCount)
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 0, atMs = clock.nowMs())  // |0-255|=255 > 1 - rejected, streak resets to ZERO
        assertEquals(0, states.last().verificationStreakCount)
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())  // fresh 1st reading - accepted
        assertEquals(1, states.last().verificationStreakCount)
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 91, atMs = clock.nowMs())
        assertEquals(SessionState.Phase.RUNNING, states.last().phase)
        assertEquals(90.3333, states.last().startingBpm!!, 0.001)  // (90+90+91)/3
    }

    @Test
    fun `WHEN_PULSE_DETECTED mode with resolved starting bpm below target clamps to target`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 50)

        assertEquals(60.0, states.last().startingBpm!!, 0.0001)
        assertEquals(60.0, states.last().metronomeBpm!!, 0.0001)
    }

    @Test
    fun `WHEN_PULSE_DETECTED mode keeps waiting with no time limit until a steady pulse or Stop`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        var ended = false
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = true }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        repeat(90 * 60) { clock.advanceMs(1000L); engine.onTick() }   // 90 minutes, longer than the 60-minute session

        assertFalse(ended)
        assertEquals(SessionState.Phase.AWAITING_START, states.last().phase)
    }

    @Test
    fun `Stop while still waiting ends as user stopped, never as time up`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        repeat(300) { clock.advanceMs(1000L); engine.onTick() }
        engine.stop()

        assertEquals(EndReason.USER_STOPPED, ended!!.reason)
    }

    @Test
    fun `a steady pulse after a long wait still starts the session`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        repeat(10 * 60) { clock.advanceMs(1000L); engine.onTick() }   // meter switched on 10 minutes later
        engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        clock.advanceMs(500L)
        engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        clock.advanceMs(500L)
        engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())

        assertEquals(SessionState.Phase.RUNNING, states.last().phase)
        assertNull(ended)

        engine.onTick()  // confirm a subsequent tick does not retroactively end the now-RUNNING session
        assertNull(ended)
    }

    @Test
    fun `a verification reading arriving while signal is lost does not count toward the streak`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        engine.onDisconnected()
        clock.advanceMs(1000L)
        engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())  // arrives while still disconnected - must not count
        assertEquals(0, states.last().verificationStreakCount)
        assertNull(states.last().startingBpm)
    }

    @Test
    fun `StartMode NOW resolves static fallback immediately with no wait and no verification`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.NOW)
        engine.onTick()

        assertEquals(70.0, states.last().startingBpm!!, 0.0001)
        assertEquals(StartingBpmSource.STATIC_FALLBACK, states.last().startingBpmSource)
        assertEquals(SessionState.Phase.RUNNING, states.last().phase)
    }

    @Test
    fun `StartMode NOW with static fallback below target clamps to target`() {
        val clock = FakeMonotonicClock()
        val config = defaultConfig().copy(staticFallbackStartingBpm = 40.0)
        val engine = SessionEngine(config, clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.NOW)
        engine.onTick()

        assertEquals(60.0, states.last().startingBpm!!, 0.0001)
    }

    @Test
    fun `StartMode NOW session transitions to live pulse via rate limiting not instant jump`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.NOW)
        engine.onTick()
        assertEquals(70.0, states.last().metronomeBpm!!, 0.0001)

        clock.advanceMs(1000L)
        engine.onPulseReading(rawPulse = 72, atMs = clock.nowMs())

        assertEquals(72.0, states.last().metronomeBpm!!, 0.0001)
    }

    @Test
    fun `StartMode NOW total duration still counts from tap-start not from any later anchor`() {
        val clock = FakeMonotonicClock()
        val config = defaultConfig().copy(totalDurationMin = 1.0)
        val engine = SessionEngine(config, clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }

        engine.start(StartMode.NOW)
        var t = 0L
        while (t < 61_000L) {
            clock.advanceMs(250L)
            t += 250L
            engine.onTick()
        }

        assertEquals(EndReason.DURATION_ELAPSED, ended!!.reason)
    }

    @Test
    fun `disconnect during active RUNNING session holds last metronome bpm and marks signal lost`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)
        val heldBpm = states.last().metronomeBpm!!

        engine.onDisconnected()
        clock.advanceMs(1000L)
        engine.onTick()

        assertTrue(states.last().signalLost)
        assertEquals(heldBpm, states.last().metronomeBpm!!, 0.0001)
    }

    @Test
    fun `valid pulse arriving while disconnected does not clear signal loss or change bpm`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)
        val heldBpm = states.last().metronomeBpm!!

        engine.onDisconnected()
        engine.onPulseReading(rawPulse = 91, atMs = clock.nowMs())  // must be ignored while lost, even within tolerance

        assertTrue(states.last().signalLost)
        assertEquals(heldBpm, states.last().metronomeBpm!!, 0.0001)

        engine.onReconnected()
        engine.onPulseReading(rawPulse = 91, atMs = clock.nowMs())  // now takes effect (within 1 of seeded 90)

        assertFalse(states.last().signalLost)
    }

    @Test
    fun `a reading deviating more than 1 bpm during RUNNING is discarded as noise not treated as signal loss`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)
        val heldBpm = states.last().metronomeBpm!!

        clock.advanceMs(1000L)
        engine.onPulseReading(rawPulse = 255, atMs = clock.nowMs())  // wild spike - discarded as noise

        assertFalse(states.last().signalLost)
        assertEquals(heldBpm, states.last().metronomeBpm!!, 0.0001)  // held, since the reading was discarded

        clock.advanceMs(1000L)
        engine.onPulseReading(rawPulse = 200, atMs = clock.nowMs())  // still far from 90 - rejected too
        assertFalse(states.last().signalLost)
        assertEquals(heldBpm, states.last().metronomeBpm!!, 0.0001)  // still held - NOT accepted just because the prior reading was also rejected

        clock.advanceMs(1000L)
        engine.onPulseReading(rawPulse = 91, atMs = clock.nowMs())  // within 1 of 90 - genuinely accepted
        assertFalse(states.last().signalLost)
    }

    @Test
    fun `session timers keep advancing during signal loss once RUNNING`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)
        engine.onDisconnected()
        clock.advanceMs(30_000L)
        engine.onTick()

        assertEquals((60.0 * 60_000.0).toLong() - 30_000L, states.last().remainingTotalMs)
    }

    @Test
    fun `user stop ends session immediately with USER_STOPPED reason during AWAITING_START`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        engine.stop()

        assertEquals(EndReason.USER_STOPPED, ended!!.reason)
    }

    @Test
    fun `user stop ends session immediately with USER_STOPPED reason during RUNNING`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)
        engine.stop()

        assertEquals(EndReason.USER_STOPPED, ended!!.reason)
    }

    @Test
    fun `target_reached is set once metronome genuinely equals target - pulse organically closes the gap`() {
        val clock = FakeMonotonicClock()
        val config = defaultConfig().copy(rampDurationMin = 10.0, totalDurationMin = 15.0)
        val engine = SessionEngine(config, clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 65)  // starting bpm = 65
        assertFalse(states.last().targetReached)

        var pulseTimeMs = 0L
        var pulse = 65
        while (!states.last().targetReached && pulseTimeMs < 120_000L) {
            clock.advanceMs(1000L)
            pulseTimeMs += 1000L
            if (pulse > 60 && pulseTimeMs % 5000L == 0L) pulse -= 1
            engine.onPulseReading(rawPulse = pulse, atMs = clock.nowMs())
        }
        assertTrue(states.last().targetReached)
        assertEquals(60.0, states.last().metronomeBpm!!, 0.0001)
    }

    @Test
    fun `pulse at target from session start counts as target reached`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 60)

        assertTrue(states.last().targetReached)
    }

    @Test
    fun `pulse_samples tick fires every 20 seconds independent of 1hz pulse rate`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val samples = mutableListOf<PulseSampleTick>()
        engine.addListener { if (it is SessionEvent.PulseSampleReady) samples.add(it.sample) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)
        var t = 0L
        while (t < 41_000L) {
            clock.advanceMs(1000L)
            t += 1000L
            engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
            engine.onTick()
        }
        assertEquals(2, samples.size)
    }

    @Test
    fun `pulse_samples tick during signal loss writes null pulse but still fires`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val samples = mutableListOf<PulseSampleTick>()
        engine.addListener { if (it is SessionEvent.PulseSampleReady) samples.add(it.sample) }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)
        engine.onDisconnected()
        var t = 0L
        while (t < 21_000L) {
            clock.advanceMs(1000L)
            t += 1000L
            engine.onTick()
        }
        assertTrue(samples.isNotEmpty())
        assertNull(samples.first().pulse)
    }

    @Test
    fun `highest and lowest pulse come only from the completed verification streak - abandoned readings excluded`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 255, atMs = clock.nowMs())  // abandoned streak - must be excluded
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 0, atMs = clock.nowMs())    // rejected
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())   // fresh streak begins
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        clock.advanceMs(1000L); engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())   // streak completes - RUNNING
        engine.stop()

        assertEquals(90, ended!!.highestPulse)
        assertEquals(90, ended!!.lowestPulse)
    }

    @Test
    fun `highest and lowest pulse are null when no pulse was ever accepted - StartMode NOW with no readings`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }

        engine.start(StartMode.NOW)
        engine.stop()

        assertNull(ended!!.highestPulse)
        assertNull(ended!!.lowestPulse)
    }

    @Test
    fun `pulseLostAtElapsedMs is set when signal is lost and never recovers before session end`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)  // runningStartMs set at this instant
        clock.advanceMs(10_000L)
        engine.onPulseReading(rawPulse = 90, atMs = clock.nowMs())
        engine.onDisconnected()  // never reconnects
        engine.stop()

        assertEquals(10_000L, ended!!.pulseLostAtElapsedMs)
    }

    @Test
    fun `pulseLostAtElapsedMs is null when signal loss recovers before session end`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        var ended: SessionEvent.SessionEnded? = null
        engine.addListener { if (it is SessionEvent.SessionEnded) ended = it }

        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 90)
        engine.onDisconnected()
        engine.onReconnected()
        engine.stop()

        assertNull(ended!!.pulseLostAtElapsedMs)
    }

    private fun noPulseConfig(
        total: Double = 30.0, ramp: Double = 30.0, fallback: Double = 80.0,
        pattern: DescentPattern = DescentPattern.BELL_CURVE
    ) = SessionConfig(
        targetBpm = 60.0, totalDurationMin = total, rampDurationMin = ramp,
        maxOffset = 2.0, staticFallbackStartingBpm = fallback, descentPattern = pattern
    )

    /** Ticks every 250 ms until [minutes] of running time have passed; returns the last state. */
    private fun tickFor(engine: SessionEngine, clock: FakeMonotonicClock, states: List<SessionState>, minutes: Double): SessionState {
        val steps = (minutes * 60_000.0 / 250.0).toLong()
        repeat(steps.toInt()) { clock.advanceMs(250L); engine.onTick() }
        return states.last()
    }

    @Test
    fun `no-pulse session starts running at the fallback and holds through the sync window`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(noPulseConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.NO_PULSE)
        val s = tickFor(engine, clock, states, 3.0)
        assertEquals(SessionState.Phase.RUNNING, s.phase)
        assertEquals(80.0, s.metronomeBpm!!, 1e-9)
        assertEquals(StartingBpmSource.STATIC_FALLBACK, s.startingBpmSource)
        assertTrue(s.noPulse)
        assertFalse(s.signalLost)
    }

    @Test
    fun `no-pulse session descends on the clock alone and reaches target at ramp end`() {
        for (pattern in DescentPattern.values()) {
            val clock = FakeMonotonicClock()
            val engine = SessionEngine(noPulseConfig(total = 40.0, ramp = 30.0, pattern = pattern), clock)
            val states = mutableListOf<SessionState>()
            engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
            engine.start(StartMode.NO_PULSE)
            val mid = tickFor(engine, clock, states, 15.0)
            assertTrue("$pattern mid", mid.metronomeBpm!! < 80.0 && mid.metronomeBpm!! > 60.0)
            val end = tickFor(engine, clock, states, 15.05)
            assertEquals("$pattern end", 60.0, end.metronomeBpm!!, 0.05)
            val later = tickFor(engine, clock, states, 8.0)
            assertEquals("$pattern holds", 60.0, later.metronomeBpm!!, 0.0)
            assertTrue(later.targetReached)
            assertFalse(later.signalLost)
        }
    }

    @Test
    fun `no-pulse session ignores pulse readings`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(noPulseConfig(), clock)
        val events = mutableListOf<SessionEvent>()
        engine.addListener { events.add(it) }
        engine.start(StartMode.NO_PULSE)
        repeat(5) { clock.advanceMs(1000L); engine.onPulseReading(95, clock.nowMs()) }
        engine.onInvalidPulse()
        engine.onDisconnected()
        engine.onSensorOffChanged(true)
        clock.advanceMs(15_000L)
        engine.onTick()
        val s = events.filterIsInstance<SessionEvent.StateUpdated>().last().state
        assertFalse(s.signalLost)
        assertNull(s.smoothedPulse)
        assertEquals(80.0, s.metronomeBpm!!, 1e-9)
        engine.stop()
        val ended = events.filterIsInstance<SessionEvent.SessionEnded>().single()
        assertNull(ended.highestPulse)
        assertNull(ended.pulseLostAtElapsedMs)
    }

    @Test
    fun `no-pulse session stopped mid-descent reports the current bpm as ending bpm`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(noPulseConfig(pattern = DescentPattern.LINEAR), clock)
        val events = mutableListOf<SessionEvent>()
        engine.addListener { events.add(it) }
        engine.start(StartMode.NO_PULSE)
        repeat((15.0 * 60_000 / 250).toInt()) { clock.advanceMs(250L); engine.onTick() }
        engine.stop()
        val ended = events.filterIsInstance<SessionEvent.SessionEnded>().single()
        // 12 min of linear descent at 20/27 per minute after the 3-min sync window
        assertEquals(80.0 - 20.0 / 27.0 * 12.0, ended.endingBpm!!, 0.02)
    }

    @Test
    fun `no-pulse session with a long gap between ticks drops in proportion`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(noPulseConfig(pattern = DescentPattern.LINEAR), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.NO_PULSE)
        tickFor(engine, clock, states, 10.0)
        val before = states.last().metronomeBpm!!
        clock.advanceMs(30_000L); engine.onTick()
        assertEquals(before - 20.0 / 27.0 * 0.5, states.last().metronomeBpm!!, 1e-6)
    }

    @Test
    fun `no-pulse session with fallback at target never moves`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(noPulseConfig(fallback = 60.0), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.NO_PULSE)
        val s = tickFor(engine, clock, states, 20.0)
        assertEquals(60.0, s.metronomeBpm!!, 0.0)
    }

    @Test
    fun `no-pulse pulse samples carry the descending metronome and no pulse`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(noPulseConfig(), clock)
        val samples = mutableListOf<PulseSampleTick>()
        engine.addListener { if (it is SessionEvent.PulseSampleReady) samples.add(it.sample) }
        engine.start(StartMode.NO_PULSE)
        repeat((20.0 * 60_000 / 250).toInt()) { clock.advanceMs(250L); engine.onTick() }
        assertTrue(samples.size > 50)
        assertTrue(samples.all { it.pulse == null })
        assertTrue(samples.last().metronomeBpm < samples.first().metronomeBpm)
    }

    @Test
    fun `NOW with a live reading starts from that reading`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)  // target 60, fallback 70
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.NOW, initialPulse = 82)
        clock.advanceMs(250L); engine.onTick()
        val s = states.last()
        assertEquals(SessionState.Phase.RUNNING, s.phase)
        assertEquals(82.0, s.startingBpm!!, 0.0)
        assertEquals(StartingBpmSource.LIVE_PULSE, s.startingBpmSource)
        assertEquals(82.0, s.metronomeBpm!!, 0.0)
        assertFalse(s.noPulse)
    }

    @Test
    fun `NOW with a live reading below target clamps to target and counts as reached`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.NOW, initialPulse = 55)
        clock.advanceMs(250L); engine.onTick()
        assertEquals(60.0, states.last().startingBpm!!, 0.0)
        assertTrue(states.last().targetReached)
    }

    @Test
    fun `NOW live reading is included in highest and lowest`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val events = mutableListOf<SessionEvent>()
        engine.addListener { events.add(it) }
        engine.start(StartMode.NOW, initialPulse = 82)
        engine.stop()
        val ended = events.filterIsInstance<SessionEvent.SessionEnded>().single()
        assertEquals(82, ended.highestPulse)
        assertEquals(82, ended.lowestPulse)
    }

    @Test
    fun `NOW without a reading still starts from the fallback`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.NOW, initialPulse = null)
        clock.advanceMs(250L); engine.onTick()
        assertEquals(70.0, states.last().startingBpm!!, 0.0)
        assertEquals(StartingBpmSource.STATIC_FALLBACK, states.last().startingBpmSource)
    }

    @Test
    fun `initialPulse is ignored outside NOW`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.NO_PULSE, initialPulse = 82)
        clock.advanceMs(250L); engine.onTick()
        assertEquals(70.0, states.last().startingBpm!!, 0.0)
    }

    private fun monitorConfig() = defaultConfig().copy(monitor = true)  // target 60, 60-min session

    @Test
    fun `monitor follows the smoothed pulse with zero offset`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(monitorConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 80)
        repeat(30) { clock.advanceMs(1000L); engine.onPulseReading(80, clock.nowMs()) }
        clock.advanceMs(10 * 60_000L)  // well past the sync window
        repeat(5) { clock.advanceMs(1000L); engine.onPulseReading(80, clock.nowMs()) }
        repeat(5) { clock.advanceMs(1000L); engine.onPulseReading(81, clock.nowMs()) }  // within the 1-BPM filter; EMA moves 0.3/step, under the 2-BPM cap
        val s = states.last()
        assertTrue(s.smoothedPulse!! > 80.0 && s.smoothedPulse!! < 81.0)
        assertTrue(s.monitor)
        assertEquals(0.0, s.offset, 0.0)
        assertEquals(s.smoothedPulse!!, s.metronomeBpm!!, 1e-9)
    }

    @Test
    fun `monitor start is not clamped to the target and never reaches it`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(monitorConfig(), clock)
        val events = mutableListOf<SessionEvent>()
        engine.addListener { events.add(it) }
        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 55)  // below target 60
        repeat(5) { clock.advanceMs(1000L); engine.onPulseReading(55, clock.nowMs()) }
        val s = events.filterIsInstance<SessionEvent.StateUpdated>().last().state
        assertEquals(55.0, s.startingBpm!!, 0.0)
        assertFalse(s.targetReached)
        engine.stop()
        assertFalse(events.filterIsInstance<SessionEvent.SessionEnded>().single().targetReached)
    }

    @Test
    fun `monitor metronome change is still rate limited`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(monitorConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 70)
        val before = states.last().metronomeBpm!!
        // RunningPulseFilter re-baselines on the 3rd consistent off-baseline reading; only then does the smoother/limiter run
        repeat(3) { clock.advanceMs(1000L); engine.onPulseReading(90, clock.nowMs()) }
        val s = states.last()
        assertEquals(76.0, s.smoothedPulse!!, 1e-9)                              // 70 + 0.3 * (90 - 70)
        assertEquals(before + MAX_BPM_CHANGE_PER_UPDATE, s.metronomeBpm!!, 1e-9)  // 72, capped; lags the smoothed 76
        assertEquals(0.0, s.offset, 0.0)
    }

    @Test
    fun `monitor holds the metronome through signal loss with no offset or target`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(monitorConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 55)
        engine.onDisconnected()
        clock.advanceMs(5_000L); engine.onTick()
        val s = states.last()
        assertTrue(s.signalLost); assertTrue(s.monitor)
        assertEquals(55.0, s.metronomeBpm!!, 0.0)
        assertEquals(0.0, s.offset, 0.0)
        assertFalse(s.targetReached)
    }

    @Test
    fun `non-monitor sessions report monitor false`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>()
        engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 80)
        assertFalse(states.last().monitor)
    }

    @Test
    fun `silence only in monitor sessions set to stop, and only while the pulse is lost`() {
        assertTrue(shouldSilence(monitor = true, signalLost = true, behaviour = MonitorSignalLoss.SILENT))
        assertFalse(shouldSilence(monitor = true, signalLost = false, behaviour = MonitorSignalLoss.SILENT))
        assertFalse(shouldSilence(monitor = true, signalLost = true, behaviour = MonitorSignalLoss.HOLD))
        assertFalse(shouldSilence(monitor = false, signalLost = true, behaviour = MonitorSignalLoss.SILENT))
    }

    @Test
    fun `monitor signal loss parses stored names and defaults to hold`() {
        assertEquals(MonitorSignalLoss.HOLD, parseMonitorSignalLoss(null))
        assertEquals(MonitorSignalLoss.HOLD, parseMonitorSignalLoss("WOBBLY"))
        for (b in MonitorSignalLoss.values()) assertEquals(b, parseMonitorSignalLoss(b.name))
    }

    @Test fun `focus is counted only after the sync window`() {
        val clock = FakeMonotonicClock(); val engine = SessionEngine(defaultConfig(), clock)
        val events = mutableListOf<SessionEvent>(); engine.addListener { events.add(it) }
        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 80)
        repeat(10) { clock.advanceMs(1000L); engine.onPulseReading(80, clock.nowMs()) }   // inside the sync window
        engine.stop()
        assertNull(events.filterIsInstance<SessionEvent.SessionEnded>().single().focusPercent)
    }
    @Test fun `a steady pulse just after the sync window is fully in focus`() {
        val clock = FakeMonotonicClock(); val engine = SessionEngine(defaultConfig(), clock)
        val events = mutableListOf<SessionEvent>(); engine.addListener { events.add(it) }
        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 80)
        clock.advanceMs(6 * 60_000L + 1000L)
        repeat(20) { clock.advanceMs(1000L); engine.onPulseReading(80, clock.nowMs()) }   // offset still < 1 this early in the ramp
        engine.stop()
        assertEquals(100, events.filterIsInstance<SessionEvent.SessionEnded>().single().focusPercent)
    }
    @Test fun `readings during signal loss are not counted`() {
        val clock = FakeMonotonicClock(); val engine = SessionEngine(defaultConfig(), clock)
        val states = mutableListOf<SessionState>(); engine.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        engine.start(StartMode.WHEN_PULSE_DETECTED)
        feedThreeReadings(engine, clock, 80)
        clock.advanceMs(6 * 60_000L + 1000L)
        repeat(5) { clock.advanceMs(1000L); engine.onPulseReading(80, clock.nowMs()) }
        val before = states.last().focusPercent
        assertEquals(100, before)
        engine.onDisconnected()
        repeat(5) { clock.advanceMs(1000L); engine.onPulseReading(60, clock.nowMs()) }   // arrive while lost: ignored
        assertEquals(before, states.last().focusPercent)
    }
    @Test fun `monitor and no-pulse sessions have no focus, even with readings`() {
        run {
            val c = FakeMonotonicClock(); val e = SessionEngine(monitorConfig(), c)
            val events = mutableListOf<SessionEvent>(); e.addListener { events.add(it) }
            e.start(StartMode.WHEN_PULSE_DETECTED); feedThreeReadings(e, c, 80)
            c.advanceMs(6 * 60_000L + 1000L)
            repeat(5) { c.advanceMs(1000L); e.onPulseReading(80, c.nowMs()) }
            e.stop()
            assertNull(events.filterIsInstance<SessionEvent.SessionEnded>().single().focusPercent)
        }
        run {
            val c = FakeMonotonicClock(); val e = SessionEngine(noPulseConfig(), c)
            val events = mutableListOf<SessionEvent>(); e.addListener { events.add(it) }
            e.start(StartMode.NO_PULSE)
            repeat(5) { c.advanceMs(1000L); e.onPulseReading(80, c.nowMs()) }
            e.stop()
            assertNull(events.filterIsInstance<SessionEvent.SessionEnded>().single().focusPercent)
        }
    }
    @Test fun `a pulse that stays put while the planned descent comes down falls out of focus`() {
        val clock = FakeMonotonicClock()
        val engine = SessionEngine(defaultConfig().copy(totalDurationMin = 10.0, rampDurationMin = 10.0), clock)
        val events = mutableListOf<SessionEvent>(); engine.addListener { events.add(it) }
        engine.start(StartMode.WHEN_PULSE_DETECTED); feedThreeReadings(engine, clock, 80)
        repeat(10 * 60 - 5) { clock.advanceMs(1000L); engine.onPulseReading(80, clock.nowMs()) }
        engine.stop()
        val focus = events.filterIsInstance<SessionEvent.SessionEnded>().single().focusPercent!!
        assertTrue("was $focus", focus in 1..40)   // the plan reaches 78 about a minute after sync, then keeps dropping
    }

    private fun pacedConfig(loss: PacedSignalLoss) = SessionConfig(
        targetBpm = 60.0, totalDurationMin = 30.0, rampDurationMin = 30.0, maxOffset = 2.0,
        staticFallbackStartingBpm = 70.0, descentPattern = DescentPattern.LINEAR, pacedSignalLoss = loss
    )
    private fun startPaced(loss: PacedSignalLoss, clock: FakeMonotonicClock, states: MutableList<SessionState>): SessionEngine {
        val e = SessionEngine(pacedConfig(loss), clock); e.addListener { if (it is SessionEvent.StateUpdated) states.add(it.state) }
        e.start(StartMode.WHEN_PULSE_DETECTED); feedThreeReadings(e, clock, 80); return e
    }
    private fun tick(e: SessionEngine, c: FakeMonotonicClock, minutes: Double) { repeat((minutes * 240).toInt()) { c.advanceMs(250L); e.onTick() } }

    @Test fun `continue keeps descending at the planned rate while the pulse is lost`() {
        val c = FakeMonotonicClock(); val s = mutableListOf<SessionState>(); val e = startPaced(PacedSignalLoss.CONTINUE, c, s)
        repeat(240) { c.advanceMs(1000L); e.onPulseReading(80, c.nowMs()) }   // 4 min of pulse, past the sync window
        e.onDisconnected(); val before = s.last().metronomeBpm!!
        tick(e, c, 5.0)
        assertEquals(before - (80.0 - 60.0) / 27.0 * 5.0, s.last().metronomeBpm!!, 0.05)
        assertFalse(s.last().targetReached)
    }
    @Test fun `hold keeps the last value while the pulse is lost`() {
        val c = FakeMonotonicClock(); val s = mutableListOf<SessionState>(); val e = startPaced(PacedSignalLoss.HOLD, c, s)
        repeat(240) { c.advanceMs(1000L); e.onPulseReading(80, c.nowMs()) }
        e.onDisconnected(); val before = s.last().metronomeBpm!!
        tick(e, c, 5.0)
        assertEquals(before, s.last().metronomeBpm!!, 0.0)
    }
    @Test fun `a loss inside the sync window holds until the window ends, then descends`() {
        val c = FakeMonotonicClock(); val s = mutableListOf<SessionState>(); val e = startPaced(PacedSignalLoss.CONTINUE, c, s)
        e.onDisconnected(); val start = s.last().metronomeBpm!!
        tick(e, c, 2.5); assertEquals(start, s.last().metronomeBpm!!, 0.0)
        tick(e, c, 2.0); assertTrue(s.last().metronomeBpm!! < start)
    }
    @Test fun `when the pulse returns the metronome glides back`() {
        val c = FakeMonotonicClock(); val s = mutableListOf<SessionState>(); val e = startPaced(PacedSignalLoss.CONTINUE, c, s)
        repeat(240) { c.advanceMs(1000L); e.onPulseReading(80, c.nowMs()) }
        e.onDisconnected(); tick(e, c, 5.0)
        val low = s.last().metronomeBpm!!
        e.onReconnected(); c.advanceMs(1000L); e.onPulseReading(80, c.nowMs())
        assertTrue(s.last().metronomeBpm!! > low)
        assertTrue(s.last().metronomeBpm!! - low <= MAX_BPM_CHANGE_PER_UPDATE + 1e-9)
    }
    @Test fun `monitor sessions ignore the paced setting`() {
        val c = FakeMonotonicClock(); val s = mutableListOf<SessionState>()
        val e = SessionEngine(monitorConfig().copy(pacedSignalLoss = PacedSignalLoss.CONTINUE), c)
        e.addListener { if (it is SessionEvent.StateUpdated) s.add(it.state) }
        e.start(StartMode.WHEN_PULSE_DETECTED); feedThreeReadings(e, c, 80)
        e.onDisconnected(); val before = s.last().metronomeBpm!!
        tick(e, c, 10.0); assertEquals(before, s.last().metronomeBpm!!, 0.0)   // past monitor's 6-min sync window
    }
    @Test fun `bell carry-on reaches the target and stops there`() {
        val c = FakeMonotonicClock(); val s = mutableListOf<SessionState>()
        val e = SessionEngine(pacedConfig(PacedSignalLoss.CONTINUE).copy(descentPattern = DescentPattern.BELL_CURVE), c)
        e.addListener { if (it is SessionEvent.StateUpdated) s.add(it.state) }
        e.start(StartMode.WHEN_PULSE_DETECTED); feedThreeReadings(e, c, 80)
        repeat(240) { c.advanceMs(1000L); e.onPulseReading(80, c.nowMs()) }
        e.onDisconnected(); tick(e, c, 25.9)
        assertEquals(60.0, s.last().metronomeBpm!!, 0.0); assertFalse(s.last().targetReached)
    }
}
