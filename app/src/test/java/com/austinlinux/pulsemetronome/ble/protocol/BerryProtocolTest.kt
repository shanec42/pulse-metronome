package com.austinlinux.pulsemetronome.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BerryProtocolTest {
    // Synthetic packets derived from the BCI layout in Adafruit's parser (not captured from a device).
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `a valid packet gives pulse and spo2`() {
        val p = intArrayOf(0x81, 0x32, 0x04, 0x48, 0x61)
        // One packet: a reading but no finger-state event yet (debounced).
        assertEquals(listOf(OximeterEvent.Reading(72, 97, null, 0L)), BerryProtocol.newDecoder().decode(bytes(*p), 0L))
        // Three agreeing packets confirm the first state.
        val events = BerryProtocol.newDecoder().decode(bytes(*p, *p, *p), 0L)
        assertEquals(listOf(OximeterEvent.SensorOff(false), OximeterEvent.Reading(72, 97, null, 0L)), events)
    }
    @Test fun `finger absent and invalid values give no pulse`() {
        val p = intArrayOf(0x81, 0x00, 0x50, 0x7F, 0x7F)
        val events = BerryProtocol.newDecoder().decode(bytes(*p, *p, *p), 0L)
        assertEquals(listOf(OximeterEvent.SensorOff(true), OximeterEvent.Reading(null, null, null, 0L)), events)
    }
    @Test fun `the pulse high bit comes from d1`() {
        // d1 = 0x40 sets bit 7 of the pulse: 0x05 | 0x80 = 133
        val r = BerryProtocol.newDecoder().decode(bytes(0x81, 0x32, 0x40, 0x05, 0x60), 0L).filterIsInstance<OximeterEvent.Reading>().single()
        assertEquals(133, r.pulse)
    }
    @Test fun `garbage before a header is skipped and a packet split across notifications is joined`() {
        val d = BerryProtocol.newDecoder()
        assertTrue(d.decode(bytes(0x10, 0x20, 0x81, 0x32), 0L).isEmpty())
        val r = d.decode(bytes(0x04, 0x48, 0x61), 0L).filterIsInstance<OximeterEvent.Reading>().single()
        assertEquals(72, r.pulse)
    }
    @Test fun `a hundred packets within one throttle window give one reading`() {
        val d = BerryProtocol.newDecoder()
        val readings = (0 until 100).flatMap { i -> d.decode(bytes(0x81, 0x32, 0x04, 0x48, 0x61), i * 9L) }.filterIsInstance<OximeterEvent.Reading>()
        assertEquals(1, readings.size)
        assertEquals(1, d.decode(bytes(0x81, 0x32, 0x04, 0x48, 0x61), 900L).filterIsInstance<OximeterEvent.Reading>().size)
    }
    @Test fun `registry finds berry by name, by service and by gatt, and keeps ks60fw and fs20f`() {
        assertEquals(ProtocolId.BERRY, ProtocolRegistry.forAdvertisement("BerryMed\u0000\u0000", emptySet())?.id)
        assertEquals(ProtocolId.BERRY, ProtocolRegistry.forAdvertisement(null, setOf(BerryProtocol.SERVICE))?.id)
        assertEquals(ProtocolId.BERRY, ProtocolRegistry.forGatt(setOf(BerryProtocol.notifyCharacteristic))?.id)
        assertEquals(ProtocolId.KS60FW, ProtocolRegistry.forAdvertisement("KS-60FW 3C1A", emptySet())?.id)
        assertEquals(ProtocolId.FS20F, ProtocolRegistry.forGatt(setOf(uuid16(0xFFE4)))?.id)
        assertFalse(BerryProtocol.tested)
    }
    @Test fun `the throttle lets one through per interval`() {
        val t = ReadingThrottle()
        assertTrue(t.allow(0)); assertFalse(t.allow(899)); assertTrue(t.allow(900))
    }
    @Test fun `the throttle recovers when the clock goes back`() {
        val t = ReadingThrottle()
        assertTrue(t.allow(10_000)); assertTrue(t.allow(5_000))
    }
    @Test fun `a flapping finger bit is debounced`() {
        val on = intArrayOf(0x81, 0x32, 0x04, 0x48, 0x61); val off = intArrayOf(0x81, 0x00, 0x50, 0x7F, 0x7F)
        val d = BerryProtocol.newDecoder()
        d.decode(bytes(*on, *on, *on), 0L)                                   // confirmed present
        val flap = d.decode(bytes(*off, *on, *off, *on, *off, *on), 1000L)   // never 3 in a row
        assertTrue(flap.none { it is OximeterEvent.SensorOff })
        assertEquals(listOf(OximeterEvent.SensorOff(true)), d.decode(bytes(*off, *off, *off), 2000L).filterIsInstance<OximeterEvent.SensorOff>())
    }
}
