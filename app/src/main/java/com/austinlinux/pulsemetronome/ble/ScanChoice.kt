package com.austinlinux.pulsemetronome.ble

/**
 * Which meter a scan window that found two or more resolves to, or null to show the picker.
 * A scan the user started (Setup's Scan / Rescan) always asks; an automatic one (a session's
 * reconnect, where nobody is there to choose) prefers the meter already known.
 */
fun resolveMultipleFound(userChoosing: Boolean, knownAddress: String?, foundAddresses: Collection<String>): String? =
    if (userChoosing) null else knownAddress?.takeIf { it in foundAddresses }

/** The picker's list after Rescan: the meter it just closed stays listed (marked needsRestart) if the scan couldn't hear it. */
fun withClosedMeter(found: List<DeviceCandidate>, closed: DeviceCandidate?): List<DeviceCandidate> =
    if (closed == null || found.any { it.address == closed.address }) found
    else found + closed.copy(needsRestart = true)
