package com.austinlinux.pulsemetronome.ui.setup

import com.austinlinux.pulsemetronome.wearlink.CantStartReason

/** What Setup's Pulse-from card shows while the Watch source is selected. */
sealed interface WatchCardState {
    data class Ready(val name: String) : WatchCardState
    object NotFound : WatchCardState
    object Waiting : WatchCardState
    data class CantStart(val reason: CantStartReason) : WatchCardState
}

/** Copy for one card state; a null field is not shown. */
data class WatchCardCopy(val title: String, val sub: String?, val message: String?, val pill: String?)

private const val CHECK_AGAIN = "Check again"

/** The exact card text for [s]. */
fun watchCardCopy(s: WatchCardState): WatchCardCopy = when (s) {
    is WatchCardState.Ready -> WatchCardCopy("Watch ready", s.name, null, null)
    WatchCardState.NotFound -> WatchCardCopy(
        "No watch found", "Wear OS watch",
        "Install Pulse Metronome on your watch from the Play Store, then open it once.", CHECK_AGAIN
    )
    WatchCardState.Waiting -> WatchCardCopy("Waiting for watch…", null, null, null)
    is WatchCardState.CantStart -> WatchCardCopy(
        "Can't start", null,
        when (s.reason) {
            CantStartReason.NOT_REACHABLE -> "Your watch isn't reachable. Make sure it's on and connected to this phone."
            CantStartReason.APP_MISSING -> "Pulse Metronome isn't on your watch. Install it from the Play Store on your watch, then open it once."
            CantStartReason.NO_PERMISSION -> "Open Pulse Metronome on your watch once to allow heart rate."
        },
        CHECK_AGAIN
    )
}
