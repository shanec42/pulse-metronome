package com.austinlinux.pulsemetronome.ble

import java.util.UUID

/** What a screen-off scan filters on: Android pauses unfiltered BLE scans while the screen is off. */
data class ScanFilterSpec(val addresses: List<String>, val serviceUuids: List<UUID>)

/** The remembered meter's address (if any) plus every supported protocol's advertised service. */
fun screenOffScanFilters(knownAddress: String?, serviceUuids: Set<UUID>): ScanFilterSpec =
    ScanFilterSpec(listOfNotNull(knownAddress), serviceUuids.toList())
