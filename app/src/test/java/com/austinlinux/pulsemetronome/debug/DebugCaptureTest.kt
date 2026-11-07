package com.austinlinux.pulsemetronome.debug

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugCaptureTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `event does not build the message when capture is off`() {
        var built = false
        DebugCapture.event("APP") { built = true; "x" }
        assertFalse(built)
    }

    @Test fun `addWatchLog included in zip`() {
        DebugCapture.initForTest(tmp.newFolder("cap"))
        DebugCapture.addWatchLog("abc-123", "line one\nline two\n".toByteArray())
        val zip = File(tmp.root, "out/share.zip")
        assertTrue(DebugCapture.zipTo(zip))
        ZipFile(zip).use { z ->
            val entry = z.getEntry("watch-abc-123.log")
            assertEquals("line one\nline two\n", z.getInputStream(entry).readBytes().decodeToString())
        }
    }
}
