package com.austinlinux.pulsemetronome.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic frames derived from the emay-sleepo2 reference (not captured from a device).
class EmayProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun frame(pr: Int, spo2: Int): ByteArray { val b = intArrayOf(0xEB, 0x01, 0x05, pr, spo2, 0x7F, 0x00); return bytes(*b, b.sum() and 0x7F) }

    @Test fun `command bytes match the published sequence`() {
        assertArrayEquals(bytes(0x89, 0x09), emayCommand(0x89))
        assertArrayEquals(bytes(0x8E, 0x05, 0x13), emayCommand(0x8E, 0x05))
        assertArrayEquals(bytes(0x9B, 0x01, 0x1C), emayCommand(0x9B, 0x01))
        assertArrayEquals(bytes(0x86, 0x06), emayCommand(0x86))
        assertArrayEquals(bytes(0x9A, 0x1A), EmayProtocol.keepAlive.command)
        assertEquals(1500L, EmayProtocol.keepAlive.intervalMs)
        assertArrayEquals(bytes(0x9B, 0x7F, 0x1A), EmayProtocol.stopCommands().single())
    }
    @Test fun `a valid frame gives a reading`() {
        assertEquals(listOf(OximeterEvent.Reading(72, 97, null, 0L)), EmayProtocol.newDecoder().decode(frame(72, 97), 0L))
    }
    @Test fun `a bad checksum is rejected`() {
        val f = frame(72, 97).also { it[7] = (it[7] + 1).toByte() }
        assertTrue(EmayProtocol.newDecoder().decode(f, 0L).isEmpty())
    }
    @Test fun `split and joined frames are reassembled`() {
        val d = EmayProtocol.newDecoder()
        val f = frame(72, 97)
        assertTrue(d.decode(f.copyOfRange(0, 3), 0L).isEmpty())
        assertEquals(72, (d.decode(f.copyOfRange(3, 8), 0L).single() as OximeterEvent.Reading).pulse)
        val two = frame(70, 96) + frame(71, 96)
        assertEquals(71, (EmayProtocol.newDecoder().decode(two, 0L).single() as OximeterEvent.Reading).pulse)   // latest, throttled to one
    }
    @Test fun `sentinels give no value and out-of-range pulses are dropped`() {
        assertEquals(listOf(OximeterEvent.Reading(null, null, null, 0L)), EmayProtocol.newDecoder().decode(frame(0xFF, 0x00), 0L))
        assertTrue(EmayProtocol.newDecoder().decode(frame(25, 97), 0L).isEmpty())
    }
    @Test fun `a real frame starting inside a corrupted one is not lost`() {
        val junk = bytes(0xEB, 0x01, 0x05) + frame(72, 97)
        assertEquals(72, (EmayProtocol.newDecoder().decode(junk, 0L).single() as OximeterEvent.Reading).pulse)
    }
    @Test fun `registry finds emay and keeps ks60fw and fs20f`() {
        assertEquals(ProtocolId.EMAY, ProtocolRegistry.forAdvertisement("SleepO2 1234", emptySet())?.id)
        assertEquals(ProtocolId.EMAY, ProtocolRegistry.forGatt(setOf(uuid16(0xFF01), uuid16(0xFF02)))?.id)
        assertEquals(ProtocolId.EMAY, ProtocolRegistry.forAdvertisement(null, setOf(uuid16(0xFF12)))?.id)
        assertEquals(ProtocolId.KS60FW, ProtocolRegistry.forAdvertisement("KS-60FW 3C1A", emptySet())?.id)
        assertEquals(ProtocolId.FS20F, ProtocolRegistry.forGatt(setOf(uuid16(0xFFE4)))?.id)
        assertFalse(EmayProtocol.tested)
    }
}
