package com.austinlinux.dfmetronome.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualRescanTest {
    @Test fun `connected is a no-op`() =
        assertEquals(ManualRescanAction.NO_OP, manualRescanAction(connected = true, scanOpen = false, gattPending = false))

    @Test fun `open scan window is a no-op`() =
        assertEquals(ManualRescanAction.NO_OP, manualRescanAction(connected = false, scanOpen = true, gattPending = true))

    @Test fun `pending connect attempt is closed before scanning`() =
        assertEquals(ManualRescanAction.CLOSE_THEN_SCAN, manualRescanAction(connected = false, scanOpen = false, gattPending = true))

    @Test fun `idle scans`() =
        assertEquals(ManualRescanAction.SCAN, manualRescanAction(connected = false, scanOpen = false, gattPending = false))

    @Test fun `fill fraction resumes mid-window`() =
        assertEquals(0.5f, scanFillFraction(sinceMs = 1_000L, nowMs = 3_000L, windowMs = 4_000L), 0.0001f)

    @Test fun `fill fraction clamps to 0 and 1`() {
        assertEquals(0f, scanFillFraction(sinceMs = 5_000L, nowMs = 4_000L, windowMs = 4_000L), 0.0001f)
        assertEquals(1f, scanFillFraction(sinceMs = 0L, nowMs = 9_000L, windowMs = 4_000L), 0.0001f)
    }

    @Test fun `pill is idle with no scan and no connect attempt`() =
        assertEquals(ScanPillState.IDLE, scanPillState(scanningSince = null, connectingSince = null, nowMs = 50_000L))

    @Test fun `an open scan window shows scanning even if a connect is also pending`() =
        assertEquals(ScanPillState.SCANNING, scanPillState(scanningSince = 49_000L, connectingSince = 10_000L, nowMs = 50_000L))

    @Test fun `a connect attempt younger than 20 s shows connecting`() =
        assertEquals(ScanPillState.CONNECTING, scanPillState(scanningSince = null, connectingSince = 31_000L, nowMs = 50_000L))

    @Test fun `a connect attempt 20 s old or more offers retry`() {
        assertEquals(ScanPillState.RETRY, scanPillState(scanningSince = null, connectingSince = 30_000L, nowMs = 50_000L))
        assertEquals(ScanPillState.RETRY, scanPillState(scanningSince = null, connectingSince = 0L, nowMs = 50_000L))
    }
}
