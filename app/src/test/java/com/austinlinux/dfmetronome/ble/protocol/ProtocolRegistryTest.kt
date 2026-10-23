package com.austinlinux.dfmetronome.ble.protocol

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolRegistryTest {
    private val ffe0 = uuid16(0xFFE0); private val ffe4 = uuid16(0xFFE4)
    private val nusTx = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    @Test fun `ks60fw is found by its name`() { assertEquals(ProtocolId.KS60FW, ProtocolRegistry.forAdvertisement("KS-60FW 3C1A", emptySet())?.id) }
    @Test fun `fs20f is found by its name or its service`() {
        assertEquals(ProtocolId.FS20F, ProtocolRegistry.forAdvertisement("VTM 20F", emptySet())?.id)
        assertEquals(ProtocolId.FS20F, ProtocolRegistry.forAdvertisement(null, setOf(ffe0))?.id)
    }
    @Test fun `an unknown device matches nothing`() {
        assertNull(ProtocolRegistry.forAdvertisement("Galaxy Buds", emptySet()))
        assertNull(ProtocolRegistry.forGatt(setOf(uuid16(0x2A37))))
    }
    @Test fun `gatt picks fs20f over ks60fw when a unit exposes both`() {
        assertEquals(ProtocolId.FS20F, ProtocolRegistry.forGatt(setOf(ffe4, nusTx))?.id)
        assertEquals(ProtocolId.KS60FW, ProtocolRegistry.forGatt(setOf(nusTx))?.id)
    }
    @Test fun `both existing protocols are tested and listen-only`() {
        listOf(ProtocolId.FS20F, ProtocolId.KS60FW).map { ProtocolRegistry.byId(it) }.forEach { assertTrue(it.tested); assertNull(it.writeCharacteristic); assertNull(it.keepAlive) }
    }
}
