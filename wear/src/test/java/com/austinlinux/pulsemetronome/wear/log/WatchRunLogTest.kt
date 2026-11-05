package com.austinlinux.pulsemetronome.wear.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchRunLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun log(maxBytes: Int = 256 * 1024) = WatchRunLog(tmp.newFolder(), maxBytes, clock = { 1_000L })

    @Test fun finish_writesAllEvents() {
        val log = log()
        log.begin("s1")
        log.event("START", "from phone")
        log.event("SAMPLE", "bpm=72")
        val text = log.finish().readText()
        val lines = text.trim().lines()
        assertEquals(3, lines.size) // begin line plus two events
        assertTrue(lines[0].contains("BEGIN") && lines[0].contains("s1"))
        assertTrue(lines[1].endsWith("START from phone"))
        assertTrue(lines[2].endsWith("SAMPLE bpm=72"))
    }

    @Test fun maxBytes_dropsOldestLines() {
        val log = log(maxBytes = 300)
        log.begin("s2")
        repeat(100) { log.event("SAMPLE", "bpm=$it") }
        val f = log.finish()
        val text = f.readText()
        assertTrue(f.length() <= 300)
        assertTrue(text.contains("bpm=99"))
        assertFalse(text.contains("bpm=0\n"))
    }

    @Test fun unsent_staysPending_untilMarkSent() {
        val log = log()
        log.begin("s3")
        log.event("X", "y")
        val f = log.finish()
        assertEquals(listOf(f), log.pending())
        assertEquals("s3", WatchRunLog.sessionIdOf(f))
        assertEquals(listOf(f), log.pending()) // still pending until confirmed
        log.markSent(f)
        assertTrue(log.pending().isEmpty())
    }

    @Test fun finish_withoutBegin_isHarmless() {
        val log = log()
        assertTrue(log.pending().isEmpty())
        assertFalse(File(tmp.root, "x").exists())
    }
}
