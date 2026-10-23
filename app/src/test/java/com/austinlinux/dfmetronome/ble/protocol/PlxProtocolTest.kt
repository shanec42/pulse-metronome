package com.austinlinux.dfmetronome.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// Synthetic records built from the Bluetooth SIG PLX spec (not captured from a device).
class PlxProtocolTest {
    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    @Test fun `sfloat decodes values and special codes`() {
        assertEquals(97.0, sfloat(0x0061)!!, 1e-9)            // mantissa 97, exponent 0
        assertEquals(72.5, sfloat(0xF2D5)!!, 1e-9)            // mantissa 725, exponent -1
        assertEquals(-5.0, sfloat(0x0FFB)!!, 1e-9)            // mantissa -5
        listOf(0x07FF, 0x0800, 0x0801, 0x07FE, 0x0802).forEach { assertNull(sfloat(it)) }
    }
    @Test fun `a normal record gives a reading`() {
        val rec = byteArrayOf(0x00) + le16(0x0061) + le16(0x0048)   // SpO2 97, PR 72
        assertEquals(listOf(OximeterEvent.Reading(72, 97, null, 0L)), PlxProtocol.newDecoder().decode(rec, 0L))
    }
    @Test fun `optional fields are skipped and a disconnected sensor is reported`() {
        val status = 1 shl 15
        val rec = byteArrayOf(0x1F) + le16(0x0061) + le16(0x0048) +      // flags: fast, slow, meas, dev/sensor, PAI
            le16(0x0062) + le16(0x0049) + le16(0x0060) + le16(0x0047) +    // fast, slow
            le16(0x0000) +                                                 // measurement status
            byteArrayOf((status and 0xFF).toByte(), ((status shr 8) and 0xFF).toByte(), 0) +
            le16(0x0032)                                                   // pulse amplitude index
        assertEquals(listOf(OximeterEvent.SensorOff(true), OximeterEvent.Reading(72, 97, null, 0L)), PlxProtocol.newDecoder().decode(rec, 0L))
    }
    @Test fun `NaN values give no pulse`() {
        val rec = byteArrayOf(0x00) + le16(0x07FF) + le16(0x07FF)
        assertEquals(listOf(OximeterEvent.Reading(null, null, null, 0L)), PlxProtocol.newDecoder().decode(rec, 0L))
    }
    @Test fun `registry finds plx by service and by gatt, and keeps ks60fw and fs20f`() {
        assertEquals(ProtocolId.PLX, ProtocolRegistry.forAdvertisement(null, setOf(uuid16(0x1822)))?.id)
        assertEquals(ProtocolId.PLX, ProtocolRegistry.forGatt(setOf(uuid16(0x2A5F)))?.id)
        assertEquals(ProtocolId.KS60FW, ProtocolRegistry.forAdvertisement("KS-60FW 3C1A", emptySet())?.id)
        assertFalse(PlxProtocol.tested)
    }
}
