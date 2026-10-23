package com.austinlinux.dfmetronome.permissions

object PermissionsManager {
    fun requiredBlePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= 31) {
            listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * The full set of dangerous/runtime permissions this app must request before BLE
     * scan/connect or the session notification can work - added in final review (C1): the
     * app previously never requested ANY runtime permission, which crashes on the first
     * real Scan/Start tap on API 31+ (SecurityException from startScan/connectGatt) and
     * silently hides the session/alarm notifications on API 33+ (POST_NOTIFICATIONS never
     * granted). requiredBlePermissions alone is retained for existing BLE-specific callers.
     */
    fun requiredRuntimePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= 33) {
            requiredBlePermissions(sdkInt) + android.Manifest.permission.POST_NOTIFICATIONS
        } else {
            requiredBlePermissions(sdkInt)
        }

    // Real-device-only checks, implemented alongside this pure function but not unit-tested:
    // notification permission state (API 33+ via ContextCompat.checkSelfPermission), location
    // services enabled (API<=30 via LocationManager.isLocationEnabled()), Bluetooth adapter
    // enabled state + ACTION_REQUEST_ENABLE intent trigger, and denial-state UI hooks
    // (explanation text + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ...) deep link).
}
