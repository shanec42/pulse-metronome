package com.austinlinux.dfmetronome.debug

import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class DayFileWriterTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val t0 = 1_791_590_400_000L // 2026-10-10 00:00:00 UTC
    private fun dir() = Files.createTempDirectory("cap").toFile()

    @Test fun `lines go to the day file and roll over at midnight`() {
        val d = dir(); var now = t0 + 86_399_000L  // 23:59:59
        val w = DayFileWriter(d, { now }, utc, maxDayBytes = 1_000_000)
        w.write("a"); now = t0 + 86_401_000L; w.write("b"); w.close()
        assertEquals("a\n", File(d, "capture-2026-10-10.log").readText())
        assertEquals("b\n", File(d, "capture-2026-10-11.log").readText())
    }

    @Test fun `day cap writes one paused line then drops until the next day`() {
        val d = dir(); var now = t0
        val w = DayFileWriter(d, { now }, utc, maxDayBytes = 10)
        w.write("123456789"); w.write("next"); w.write("again")
        now = t0 + 86_400_000L; w.write("newday"); w.close()
        val lines = File(d, "capture-2026-10-10.log").readLines()
        assertEquals("123456789", lines[0])
        assertTrue(lines[1].contains("capture paused for today: size cap reached"))
        assertEquals(2, lines.size)
        assertEquals("newday\n", File(d, "capture-2026-10-11.log").readText())
    }

    @Test fun `reopening a day file continues its byte count`() {
        val d = dir(); val now = t0
        File(d, "capture-2026-10-10.log").writeText("123456789\n")
        val w = DayFileWriter(d, { now }, utc, maxDayBytes = 10)
        w.write("more"); w.close()
        assertTrue(File(d, "capture-2026-10-10.log").readLines()[1].contains("capture paused"))
    }

    @Test fun `opening a new day prunes old files but not the new one`() {
        val d = dir(); File(d, "capture-2026-09-01.log").writeText("old\n")
        val w = DayFileWriter(d, { t0 }, utc, maxDayBytes = 1_000_000)
        w.write("x"); w.close()
        assertFalse(File(d, "capture-2026-09-01.log").exists())
        assertTrue(File(d, "capture-2026-10-10.log").exists())
    }
}

class DayFileWriterFix1Test {
    private val utc = TimeZone.getTimeZone("UTC")
    private val t0 = 1_791_590_400_000L

    @Test fun `today alone over the cap is never pruned`() {
        val sizes = mapOf("capture-2026-10-10.log" to 999L)
        assertEquals(emptyList<String>(), selectPrune(sizes, today = "capture-2026-10-10.log", maxTotalBytes = 10L))
    }

    @Test fun `reopening a capped day adds no second paused line`() {
        val d = Files.createTempDirectory("cap").toFile()
        val w1 = DayFileWriter(d, { t0 }, utc, maxDayBytes = 10)
        w1.write("123456789"); w1.write("more"); w1.close()
        val w2 = DayFileWriter(d, { t0 }, utc, maxDayBytes = 10_000)
        w2.write("again"); w2.close()
        val lines = File(d, "capture-2026-10-10.log").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("capture paused"))
    }

    @Test fun `a failed open does not throw and a later write succeeds`() {
        val base = Files.createTempDirectory("cap").toFile()
        val d = File(base, "dir"); d.writeText("i am a file")
        val w = DayFileWriter(d, { t0 }, utc, maxDayBytes = 1_000_000)
        w.write("lost")
        d.delete()
        w.write("kept"); w.close()
        assertEquals("kept\n", File(d, "capture-2026-10-10.log").readText())
    }

    @Test fun `old days are trimmed mid-day once the total passes the cap`() {
        val d = Files.createTempDirectory("cap").toFile(); File(d, "capture-2026-10-09.log").writeText("x".repeat(600) + "\n")
        val w = DayFileWriter(d, { t0 }, utc, maxDayBytes = 10_000_000, maxTotalBytes = 1_000_500)
        assertTrue(File(d, "capture-2026-10-09.log").exists())  // under the cap when the day opened
        val line = "y".repeat(999)
        repeat(1_001) { w.write(line) }
        w.close()
        assertFalse(File(d, "capture-2026-10-09.log").exists())
        assertTrue(File(d, "capture-2026-10-10.log").exists())
    }
}
