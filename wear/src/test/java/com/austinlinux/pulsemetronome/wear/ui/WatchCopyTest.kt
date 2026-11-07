package com.austinlinux.pulsemetronome.wear.ui

import com.austinlinux.pulsemetronome.wear.session.WatchUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchCopyTest {
    @Test
    fun copy_everyState() {
        assertEquals(
            WatchCopy(null, null, "Pulse Metronome reads your heart rate during sessions you start on your phone.", "Allow"),
            watchCopy(WatchUiState.NeedsPermission),
        )
        assertEquals(
            WatchCopy(null, "Heart rate is off", "Your phone can't use this watch without it.", "Try again"),
            watchCopy(WatchUiState.PermissionDenied),
        )
        assertEquals(
            WatchCopy(null, "Heart rate is off", "Your phone can't use this watch without it.", "Open settings"),
            watchCopy(WatchUiState.PermissionDenied, systemStoppedPrompting = true),
        )
        assertEquals(WatchCopy("READY", null, "Start a session on your phone.", null), watchCopy(WatchUiState.Ready))
        assertEquals(WatchCopy("Finding pulse", null, null, null), watchCopy(WatchUiState.FindingPulse))
        assertEquals(WatchCopy("Sending to phone", null, null, null), watchCopy(WatchUiState.Sensing(64)))
        assertEquals(WatchCopy("Can't feel pulse", null, "Snug the watch on your wrist.", null), watchCopy(WatchUiState.ContactLost))
        assertEquals(
            WatchCopy("Phone link", "Can't reach your phone", "Still trying. Keep it nearby.", null),
            watchCopy(WatchUiState.LinkLost),
        )
        assertEquals(
            WatchCopy("Stopped", "Lost the phone", "Start a new session on your phone.", null),
            watchCopy(WatchUiState.StoppedTrying),
        )
    }
}
