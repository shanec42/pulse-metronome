package com.austinlinux.pulsemetronome.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanChoiceTest {
    private val found = listOf("12:34:56:00:00:01", "12:34:56:00:00:02")

    @Test fun `a scan the user started always asks, even when the known meter is among them`() {
        assertNull(resolveMultipleFound(userChoosing = true, knownAddress = "12:34:56:00:00:02", foundAddresses = found))
    }

    @Test fun `an automatic scan reconnects to the known meter when it is found`() {
        assertEquals("12:34:56:00:00:02", resolveMultipleFound(userChoosing = false, knownAddress = "12:34:56:00:00:02", foundAddresses = found))
    }

    @Test fun `an automatic scan asks when the known meter is not among them`() {
        assertNull(resolveMultipleFound(userChoosing = false, knownAddress = "AA:BB:CC:DD:EE:FF", foundAddresses = found))
        assertNull(resolveMultipleFound(userChoosing = false, knownAddress = null, foundAddresses = found))
    }

    private val ks = DeviceCandidate("12:34:56:00:00:01", 0, "KS-60FW 0001")
    private val fs = DeviceCandidate("12:34:56:00:00:02", -70, "VTM 20F")

    @Test fun `the meter Rescan closed stays listed, marked for a restart, when the scan can't hear it`() {
        val list = withClosedMeter(found = listOf(fs), closed = ks)
        assertEquals(listOf(fs, ks.copy(needsRestart = true)), list)
    }

    @Test fun `a closed meter the scan does hear is listed once, normally`() {
        val heard = ks.copy(rssi = -60)
        assertEquals(listOf(heard, fs), withClosedMeter(found = listOf(heard, fs), closed = ks))
    }

    @Test fun `with nothing closed the list is just what was found`() {
        assertEquals(listOf(fs), withClosedMeter(found = listOf(fs), closed = null))
    }
}

