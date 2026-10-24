package com.austinlinux.pulsemetronome.ble

interface DeviceAddressStore {
    fun getRemembered(): String?
    fun remember(address: String)
}
