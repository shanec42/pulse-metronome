package com.austinlinux.pulsemetronome.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OximeterEventTest {
    @Test fun `a reading with a pulse becomes Valid, without one NoSignal`() {
        assertEquals(PulseReading.Valid(72, 97, 1.5, 10L), OximeterEvent.Reading(72, 97, 1.5, 10L).toPulseReading())
        assertEquals(PulseReading.NoSignal(10L), OximeterEvent.Reading(null, 97, null, 10L).toPulseReading())
    }
    @Test fun `battery is low at 20 percent or below and unknown is not low`() {
        assertTrue(isBatteryLow(20)); assertTrue(isBatteryLow(5)); assertFalse(isBatteryLow(21)); assertFalse(isBatteryLow(null))
    }
}
