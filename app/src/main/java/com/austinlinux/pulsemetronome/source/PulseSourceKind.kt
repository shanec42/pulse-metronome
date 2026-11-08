package com.austinlinux.pulsemetronome.source

/** Where a session's pulse readings come from. */
enum class PulseSourceKind { OXIMETER, WATCH, FITBIT }

/** True only for the source that talks to a meter over the phone's own Bluetooth (and so needs its permission). */
val PulseSourceKind.needsPhoneBluetooth: Boolean get() = this == PulseSourceKind.OXIMETER
