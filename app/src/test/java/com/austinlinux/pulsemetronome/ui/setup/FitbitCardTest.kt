package com.austinlinux.pulsemetronome.ui.setup

import com.austinlinux.pulsemetronome.source.fitbit.FitbitStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FitbitCardTest {
    private val waiting = WatchCardCopy("Waiting for Fitbit", null, "Open HR Service on your watch.", "Set up HR Service")

    @Test fun off() = assertEquals(waiting, fitbitCardCopy(FitbitStatus.Off))

    @Test fun waiting() = assertEquals(waiting, fitbitCardCopy(FitbitStatus.Waiting))

    @Test fun receiving() = assertEquals(
        WatchCardCopy("Fitbit connected", "HR SERVICE · 72 BPM", null, null),
        fitbitCardCopy(FitbitStatus.Receiving(72))
    )

    @Test fun noReading() = assertEquals(
        WatchCardCopy("Fitbit connected", "HR SERVICE · NO READING", "Check the watch is snug on your wrist.", null),
        fitbitCardCopy(FitbitStatus.NoReading)
    )

    @Test fun portBusy() = assertEquals(
        WatchCardCopy(
            "Fitbit connection in use", null,
            "Another app is receiving from HR Service. Close it (for example FB Heart Rate Monitor), then tap Check again.",
            "Check again"
        ),
        fitbitCardCopy(FitbitStatus.PortBusy)
    )
}
