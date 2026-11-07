package com.austinlinux.pulsemetronome.wear.ui

import com.austinlinux.pulsemetronome.wear.session.WatchUiState

/** The text on a watch screen; null parts are not shown. The measurement and the ECG are drawn separately. */
data class WatchCopy(val status: String?, val head: String?, val body: String?, val button: String?)

/** Exact on-screen copy for [s]; [systemStoppedPrompting] turns the denied screen's button into Open settings. */
fun watchCopy(s: WatchUiState, systemStoppedPrompting: Boolean = false): WatchCopy = when (s) {
    WatchUiState.NeedsPermission -> WatchCopy(
        null, null, "Pulse Metronome reads your heart rate during sessions you start on your phone.", "Allow",
    )
    WatchUiState.PermissionDenied -> WatchCopy(
        null, "Heart rate is off", "Your phone can't use this watch without it.",
        if (systemStoppedPrompting) "Open settings" else "Try again",
    )
    WatchUiState.Ready -> WatchCopy("READY", null, "Start a session on your phone.", null)
    WatchUiState.FindingPulse -> WatchCopy("Finding pulse", null, null, null)
    is WatchUiState.Sensing -> WatchCopy("Sending to phone", null, null, null)
    WatchUiState.ContactLost -> WatchCopy("Can't feel pulse", null, "Snug the watch on your wrist.", null)
    WatchUiState.LinkLost -> WatchCopy("Phone link", "Can't reach your phone", "Still trying. Keep it nearby.", null)
    WatchUiState.StoppedTrying -> WatchCopy("Stopped", "Lost the phone", "Start a new session on your phone.", null)
}
