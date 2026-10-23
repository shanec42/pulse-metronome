package com.austinlinux.dfmetronome.debug

import java.io.File
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class CaptureFormatTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val t0 = 1_791_590_400_000L // 2026-10-10 00:00:00 UTC

    @Test fun `file name uses the local date`() {
        assertEquals("capture-2026-10-10.log", captureFileName(t0 + 5_000, utc))
        assertEquals("capture-2026-10-11.log", captureFileName(t0 + day, utc))
    }

    @Test fun `line has millisecond time, tag and message`() {
        assertEquals("2026-10-10 00:00:01.234 APP started", captureLine(t0 + 1_234, "APP", "started", utc))
    }

    @Test fun `newlines in a message are flattened so one event stays one line`() {
        assertEquals("2026-10-10 00:00:00.000 BLE a | b", captureLine(t0, "BLE", "a\nb", utc))
    }

    @Test fun `prune is by age - after a gap, old files go even if fewer than seven exist`() {
        val sizes = mapOf("capture-2026-09-20.log" to 1L, "capture-2026-10-04.log" to 1L, "capture-2026-10-10.log" to 1L)
        assertEquals(listOf("capture-2026-09-20.log"), selectPrune(sizes, today = "capture-2026-10-10.log", maxTotalBytes = 100L))
    }

    @Test fun `prune drops files older than seven days but never today`() {
        val names = (0..9).map { "capture-2026-10-%02d.log".format(1 + it) }      // 01..10
        val sizes = names.associateWith { 1L }
        val doomed = selectPrune(sizes, today = "capture-2026-10-10.log", maxTotalBytes = 50L * 1024 * 1024)
        assertEquals(listOf("capture-2026-10-01.log", "capture-2026-10-02.log", "capture-2026-10-03.log"), doomed)
    }

    @Test fun `prune trims oldest first until under the total cap, keeping today`() {
        val sizes = linkedMapOf("capture-2026-10-08.log" to 30L, "capture-2026-10-09.log" to 30L, "capture-2026-10-10.log" to 30L)
        assertEquals(listOf("capture-2026-10-08.log", "capture-2026-10-09.log"),
            selectPrune(sizes, today = "capture-2026-10-10.log", maxTotalBytes = 50L))
    }

    @Test fun `prune ignores files that are not capture files`() {
        val sizes = mapOf("exit-watermark" to 999L, "capture-2026-10-10.log" to 1L)
        assertEquals(emptyList<String>(), selectPrune(sizes, today = "capture-2026-10-10.log", maxTotalBytes = 10L))
    }

    @Test fun `summary text`() {
        assertEquals("Nothing saved yet.", captureSummary(0, 0))
        assertEquals("Saved on this phone: 1 file, 0.1 MB. Kept for 7 days.", captureSummary(1, 52_000))
        assertEquals("Saved on this phone: 3 files, 4.4 MB. Kept for 7 days.", captureSummary(3, 4_400_000))
    }

    @Test fun `exit records newer than the watermark, oldest first`() {
        val recs = listOf(
            ExitRecord(3000, 30, 2, "SIGNALED", 0, 9, 125, 0, 265_000, null),
            ExitRecord(2000, 20, 10, "USER_REQUESTED", 0, 0, 100, 0, 0, "stop"),
            ExitRecord(1000, 10, 2, "SIGNALED", 0, 9, 400, 0, 0, null)
        )
        assertEquals(listOf(2000L, 3000L), exitsSince(recs, watermark = 1000).map { it.timestampMs })
        assertEquals(emptyList<Long>(), exitsSince(recs, watermark = 3000).map { it.timestampMs })
    }

    @Test fun `exit line carries reason name and number, status, importance and memory`() {
        val r = ExitRecord(t0, 29944, 2, "SIGNALED", 0, 9, 125, 0, 265_000, null)
        assertEquals("exit at 2026-10-10 00:00:00.000 pid=29944 reason=SIGNALED(2) subreason=0 status=9 importance=125 pssKb=0 rssKb=265000 description=-",
            exitLine(r, utc))
    }

    @Test fun `debug capture defaults on when unset`() {
        assertTrue(parseDebugCapture(null))
        assertFalse(parseDebugCapture(false))
        assertTrue(parseDebugCapture(true))
    }
}
