package com.austinlinux.pulsemetronome.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalLossTrackerTest {
    @Test
    fun `no valid pulse ever received counts as signal lost`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        assertTrue(tracker.isSignalLost())
    }

    @Test
    fun `after a valid pulse signal is not lost`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        tracker.onValidPulse()
        assertFalse(tracker.isSignalLost())
    }

    @Test
    fun `disconnect event immediately marks signal lost`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        tracker.onValidPulse()
        tracker.onDisconnected()
        assertTrue(tracker.isSignalLost())
    }

    @Test
    fun `reconnect clears disconnect flag but staleness still applies from last valid pulse`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        tracker.onValidPulse()
        tracker.onDisconnected()
        tracker.onReconnected()
        assertFalse(tracker.isSignalLost())
    }

    @Test
    fun `invalid pulse reading marks signal lost immediately`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        tracker.onValidPulse()
        tracker.onInvalidPulse()
        assertTrue(tracker.isSignalLost())
    }

    @Test
    fun `staleness timeout fires at exactly 4000ms with no new pulse`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        tracker.onValidPulse()
        clock.advanceMs(3999L)
        assertFalse(tracker.isSignalLost())
        clock.advanceMs(1L)
        assertTrue(tracker.isSignalLost())
    }

    @Test
    fun `sensor off bit marks signal lost even with recent valid pulse`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        tracker.onValidPulse()
        tracker.onSensorOffChanged(true)
        assertTrue(tracker.isSignalLost())
    }

    @Test
    fun `sensor off cleared and recent valid pulse restores not-lost state`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        tracker.onSensorOffChanged(true)
        tracker.onSensorOffChanged(false)
        tracker.onValidPulse()
        assertFalse(tracker.isSignalLost())
    }

    @Test
    fun `new valid pulse after invalid reading clears the invalid flag`() {
        val clock = FakeMonotonicClock()
        val tracker = SignalLossTracker(clock)
        tracker.onValidPulse()
        tracker.onInvalidPulse()
        tracker.onValidPulse()
        assertFalse(tracker.isSignalLost())
    }
}
