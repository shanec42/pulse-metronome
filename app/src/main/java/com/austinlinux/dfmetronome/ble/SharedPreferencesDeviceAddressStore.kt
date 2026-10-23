package com.austinlinux.dfmetronome.ble

import android.content.Context

private const val PREFS_NAME = "ble_device_address_store"
private const val KEY_REMEMBERED_ADDRESS = "remembered_address"

/** SharedPreferences-backed DeviceAddressStore, per Task 11's design (concrete
 *  implementation deferred to whichever task first needs to construct a real BleModule). */
class SharedPreferencesDeviceAddressStore(context: Context) : DeviceAddressStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getRemembered(): String? = prefs.getString(KEY_REMEMBERED_ADDRESS, null)

    override fun remember(address: String) {
        prefs.edit().putString(KEY_REMEMBERED_ADDRESS, address).apply()
    }
}
