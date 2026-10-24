package com.austinlinux.pulsemetronome.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic frames from the O2Ring notes and mhgue's README example (not captured from a device).
class ViatomProtocolTest {
    // Port of the table-free CRC in RAG/wellue-o2ring-protocol/README.md, as an independent reference.
    private fun readmeCrc(data: List<Int>): Int {
        var crc = 0
        for (b in data) {
            val chk = crc xor b; crc = 0
            if (chk and 0x01 != 0) crc = 0x07
            if (chk and 0x02 != 0) crc = crc xor 0x0e
            if (chk and 0x04 != 0) crc = crc xor 0x1c
            if (chk and 0x08 != 0) crc = crc xor 0x38
            if (chk and 0x10 != 0) crc = crc xor 0x70
            if (chk and 0x20 != 0) crc = crc xor 0xe0
            if (chk and 0x40 != 0) crc = crc xor 0xc7
            if (chk and 0x80 != 0) crc = crc xor 0x89
        }
        return crc
    }
    private fun response(cmd: Int, payload: List<Int>): ByteArray {
        val body = listOf(0x55, cmd, cmd xor 0xFF, 0, 0, payload.size and 0xFF, payload.size shr 8) + payload
        return (body + readmeCrc(body)).map { it.toByte() }.toByteArray()
    }
    // mhgue's example 0x17 payload: SpO2 96, HR 72, battery 20 (0x14)
    private val live = listOf(0x60, 0x48, 0x00, 0x00, 0x00, 0x00, 0x00, 0x14, 0x03, 0x01, 0x00, 0x00, 0x00)

    @Test fun `crc matches the reference algorithm`() {
        listOf(listOf(0xAA, 0x17, 0xE8, 0, 0, 0, 0), listOf(1, 2, 3, 250, 128), (0..40).toList()).forEach {
            assertEquals(readmeCrc(it), crc8Viatom(it))
        }
    }
    @Test fun `the live-values request is AA 17 E8 then zeros and the crc`() {
        val req = viatomRequest(0x17).map { it.toInt() and 0xFF }
        assertEquals(listOf(0xAA, 0x17, 0xE8, 0, 0, 0, 0), req.take(7))
        assertEquals(readmeCrc(req.take(7)), req[7])
        assertArrayEquals(viatomRequest(0x17), ViatomProtocol.keepAlive.command)
        assertEquals(1000L, ViatomProtocol.keepAlive.intervalMs)
    }
    @Test fun `the reply observed from a real device decodes`() {
        // Byte-for-byte the reply recorded in docs/OXIMETER-PROTOCOLS.md §4.3 (mhgue's Checkme O2 capture).
        val observed = listOf(0x55, 0x00, 0xFF, 0x00, 0x00, 0x0D, 0x00, 0x60, 0x48, 0x00, 0x00, 0x00, 0x00, 0x00, 0x14, 0x03, 0x01, 0x00, 0x00, 0x00, 0x5A)
            .map { it.toByte() }.toByteArray()
        assertEquals(listOf(OximeterEvent.Battery(20), OximeterEvent.Reading(72, 96, null, 0L)), ViatomProtocol.newDecoder().decode(observed, 0L))
    }

    @Test fun `a live response gives battery and a reading`() {
        assertEquals(listOf(OximeterEvent.Battery(20), OximeterEvent.Reading(72, 96, null, 0L)), ViatomProtocol.newDecoder().decode(response(0x00, live), 0L))
    }
    @Test fun `a response split across notifications is reassembled`() {
        val d = ViatomProtocol.newDecoder(); val r = response(0x00, live)
        assertTrue(d.decode(r.copyOfRange(0, 9), 0L).isEmpty())
        assertEquals(2, d.decode(r.copyOfRange(9, r.size), 0L).size)
    }
    @Test fun `a crc failure and other commands give no events`() {
        val bad = response(0x00, live).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(ViatomProtocol.newDecoder().decode(bad, 0L).isEmpty())
        assertTrue(ViatomProtocol.newDecoder().decode(response(0x01, live), 0L).isEmpty())   // 01 FE = error reply
    }
    @Test fun `a bad crc frame followed by a good one still yields the good one`() {
        val bad = response(0x00, live).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val events = ViatomProtocol.newDecoder().decode(bad + response(0x00, live), 0L)
        assertEquals(listOf(OximeterEvent.Battery(20), OximeterEvent.Reading(72, 96, null, 0L)), events)
    }
    @Test fun `a false header with a huge length does not hold the buffer`() {
        val falseHeader = byteArrayOf(0x55, 0x01, 0xFE.toByte(), 0x00, 0x00, 0xFF.toByte(), 0x00)
        val events = ViatomProtocol.newDecoder().decode(falseHeader + response(0x00, live), 0L)
        assertEquals(listOf(OximeterEvent.Battery(20), OximeterEvent.Reading(72, 96, null, 0L)), events)
    }
    @Test fun `zero values give no pulse`() {
        val noFinger = live.toMutableList().also { it[0] = 0; it[1] = 0 }
        assertEquals(OximeterEvent.Reading(null, null, null, 0L), ViatomProtocol.newDecoder().decode(response(0x00, noFinger), 0L).last())
    }
    @Test fun `registry finds viatom by name, service and gatt, and keeps ks60fw and fs20f`() {
        listOf("O2Ring 1234", "O2 0106", "Checkme O2 55", "PO3 7788").forEach { assertEquals(it, ProtocolId.VIATOM, ProtocolRegistry.forAdvertisement(it, emptySet())?.id) }
        assertEquals(ProtocolId.VIATOM, ProtocolRegistry.forGatt(setOf(ViatomProtocol.notifyCharacteristic, ViatomProtocol.writeCharacteristic))?.id)
        assertEquals(ProtocolId.VIATOM, ProtocolRegistry.forAdvertisement(null, setOf(ViatomProtocol.SERVICE))?.id)
        assertEquals(ProtocolId.KS60FW, ProtocolRegistry.forAdvertisement("KS-60FW 3C1A", emptySet())?.id)
        assertNull(ProtocolRegistry.forAdvertisement("POLAR H10", emptySet()))
        assertFalse(ViatomProtocol.tested)
    }
}
