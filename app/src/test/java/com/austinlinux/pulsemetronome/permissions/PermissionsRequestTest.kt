package com.austinlinux.pulsemetronome.permissions

import com.austinlinux.pulsemetronome.source.PulseSourceKind
import com.austinlinux.pulsemetronome.source.needsPhoneBluetooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsRequestTest {
    private val ble = listOf("SCAN", "CONNECT")
    private val required = ble + "NOTIF"

    @Test fun notificationOnlyRequest_doesNotCountAsBleDenial() {
        assertFalse(PermissionsManager.countsAsBleDenial(listOf("NOTIF"), ble))
        assertTrue(PermissionsManager.countsAsBleDenial(required, ble))
    }

    @Test fun watchSource_requestsNotificationsOnly() {
        assertEquals(listOf("NOTIF"), PermissionsManager.initialRequest(required, ble, skipBluetooth = true) { false })
    }

    @Test fun alreadyGranted_isNotRequested() {
        assertEquals(emptyList<String>(), PermissionsManager.initialRequest(required, ble, skipBluetooth = true) { it == "NOTIF" })
        assertEquals(listOf("SCAN", "CONNECT"), PermissionsManager.initialRequest(required, ble, skipBluetooth = false) { it == "NOTIF" })
    }

    @Test fun initialRequest_fitbit_skipsBluetooth() {
        val requested = PermissionsManager.initialRequest(required, ble, skipBluetooth = !PulseSourceKind.FITBIT.needsPhoneBluetooth) { false }
        assertTrue(requested.none { it in ble })
    }
}
