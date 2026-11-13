package com.austinlinux.pulsemetronome.source

import com.austinlinux.pulsemetronome.engine.STALENESS_TIMEOUT_MS

/** Where a session's pulse readings come from. */
enum class PulseSourceKind { OXIMETER, WATCH, FITBIT }

/** True only for the source that talks to a meter over the phone's own Bluetooth (and so needs its permission). */
val PulseSourceKind.needsPhoneBluetooth: Boolean get() = this == PulseSourceKind.OXIMETER

/** Fitbit readings come ~1/s but with measured 4-8 s gaps, so the engine's 4 s staleness would flag false "Signal lost". */
const val FITBIT_STALENESS_MS: Long = 10_000L

/** How long without a reading before this source's signal counts as lost. */
val PulseSourceKind.stalenessMs: Long
    get() = if (this == PulseSourceKind.FITBIT) FITBIT_STALENESS_MS else STALENESS_TIMEOUT_MS
