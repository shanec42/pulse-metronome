package com.austinlinux.pulsemetronome.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionsManagerTest {
    @Test
    fun `api 31 and above requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT`() {
        val perms = PermissionsManager.requiredBlePermissions(sdkInt = 31)
        assertEquals(setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"), perms.toSet())
    }

    @Test
    fun `api 30 and below requires ACCESS_FINE_LOCATION only`() {
        val perms = PermissionsManager.requiredBlePermissions(sdkInt = 30)
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), perms)
    }

    @Test
    fun `runtime permissions on api 33+ include BLE permissions plus POST_NOTIFICATIONS`() {
        val perms = PermissionsManager.requiredRuntimePermissions(sdkInt = 33)
        assertEquals(
            setOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.POST_NOTIFICATIONS"
            ),
            perms.toSet()
        )
    }

    @Test
    fun `runtime permissions on api 31-32 are BLE permissions only - no POST_NOTIFICATIONS yet`() {
        val perms = PermissionsManager.requiredRuntimePermissions(sdkInt = 31)
        assertEquals(
            setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            perms.toSet()
        )
    }

    @Test
    fun `runtime permissions on api 30 and below are just the BLE permissions`() {
        val perms = PermissionsManager.requiredRuntimePermissions(sdkInt = 30)
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), perms)
    }
}
