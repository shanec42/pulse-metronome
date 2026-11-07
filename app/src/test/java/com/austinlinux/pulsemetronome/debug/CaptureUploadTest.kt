package com.austinlinux.pulsemetronome.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CaptureUploadTest {
    @Test fun uploadPath_format() {
        // 2026-11-04 09:05:07 UTC
        val ms = 1_793_783_107_000L
        assertEquals(
            "uploads/com.austinlinux.pulsemetronome.debug/1.4.0/0b6f1c2e-aaaa-bbbb-cccc-123456789abc/20261104-090507.zip",
            uploadPath("com.austinlinux.pulsemetronome.debug", "1.4.0", "0b6f1c2e-aaaa-bbbb-cccc-123456789abc", ms)
        )
    }

    @Test fun messages_exact() {
        assertEquals("Capture uploaded. Thank you.", uploadMessage(UploadResult.Done))
        assertEquals("Upload didn't finish.", uploadMessage(UploadResult.Failed("boom")))
        assertEquals("You're offline. Connect to the internet, then try Upload again.", uploadMessage(UploadResult.Offline))
    }

    @Test fun installId_stable() {
        val store = HashMap<String, String>()
        val a = installId({ store["id"] }, { store["id"] = it })
        val b = installId({ store["id"] }, { store["id"] = it })
        assertEquals(a, b)
        assertEquals(36, a.length)
        val other = installId({ null }, { })
        assertNotEquals(a, other)
    }
}
