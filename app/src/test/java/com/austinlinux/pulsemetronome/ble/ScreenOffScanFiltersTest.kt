package com.austinlinux.pulsemetronome.ble

import com.austinlinux.pulsemetronome.ble.protocol.ProtocolRegistry
import com.austinlinux.pulsemetronome.ble.protocol.uuid16
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOffScanFiltersTest {
    @Test fun `the remembered meter's address comes first, then every advertised protocol service`() {
        val spec = screenOffScanFilters("12:34:56:00:00:01", setOf(uuid16(0x1822)))
        assertEquals(listOf("12:34:56:00:00:01"), spec.addresses)
        assertEquals(listOf(uuid16(0x1822)), spec.serviceUuids)
    }

    @Test fun `with no remembered meter only the service filters remain`() {
        val spec = screenOffScanFilters(null, setOf(uuid16(0xFFE0)))
        assertTrue(spec.addresses.isEmpty())
        assertEquals(listOf(uuid16(0xFFE0)), spec.serviceUuids)
    }

    @Test fun `the registry advertises the FS20F, Berry, PLX, EMAY and Viatom services`() {
        val uuids: Set<UUID> = ProtocolRegistry.advertisedServiceUuids()
        assertEquals(5, uuids.size)
        assertTrue(uuid16(0xFFE0) in uuids)
        assertTrue(uuid16(0x1822) in uuids)
    }
}
