package com.austinlinux.dfmetronome.ble

interface DeviceAddressStore {
    fun getRemembered(): String?
    fun remember(address: String)
}
