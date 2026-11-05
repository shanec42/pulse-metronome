package com.austinlinux.pulsemetronome.ui.setup

import com.austinlinux.pulsemetronome.source.PulseSourceKind
import com.austinlinux.pulsemetronome.wearlink.CantStartReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCardStateTest {
    private val checkAgain = "Check again"

    @Test fun copy_everyState() {
        assertEquals(WatchCardCopy("Watch ready", "Galaxy Watch6", null, null), watchCardCopy(WatchCardState.Ready("Galaxy Watch6")))
        assertEquals(
            WatchCardCopy("No watch found", "Wear OS watch", "Install Pulse Metronome on your watch from the Play Store, then open it once.", checkAgain),
            watchCardCopy(WatchCardState.NotFound)
        )
        assertEquals(WatchCardCopy("Waiting for watch…", null, null, null), watchCardCopy(WatchCardState.Waiting))
        assertEquals(
            WatchCardCopy("Can't start", null, "Your watch isn't reachable. Make sure it's on and connected to this phone.", checkAgain),
            watchCardCopy(WatchCardState.CantStart(CantStartReason.NOT_REACHABLE))
        )
        assertEquals(
            WatchCardCopy("Can't start", null, "Pulse Metronome isn't on your watch. Install it from the Play Store on your watch, then open it once.", checkAgain),
            watchCardCopy(WatchCardState.CantStart(CantStartReason.APP_MISSING))
        )
        assertEquals(
            WatchCardCopy("Can't start", null, "Open Pulse Metronome on your watch once to allow heart rate.", checkAgain),
            watchCardCopy(WatchCardState.CantStart(CantStartReason.NO_PERMISSION))
        )
    }

    @Test fun cantStart_keepsStartEnabled() {
        // Even with Bluetooth denied, the Watch source keeps Start enabled so pressing it re-runs the check.
        assertTrue(
            startEnabled(
                formValid = true, waitForPulse = false, meterConnected = false, bleGranted = false,
                scanInProgress = false, startingBpm = 70, targetBpm = 60, source = PulseSourceKind.WATCH
            )
        )
    }
}
