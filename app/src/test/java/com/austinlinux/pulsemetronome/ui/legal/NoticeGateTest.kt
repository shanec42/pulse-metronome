package com.austinlinux.pulsemetronome.ui.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeGateTest {
    @Test fun `fingerprint is sha-256 hex`() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", noticeFingerprint("abc".toByteArray()))

    @Test fun `never accepted shows the notice`() = assertTrue(shouldShowNotice(null, "h1"))
    @Test fun `accepted current wording hides it`() = assertFalse(shouldShowNotice("h1", "h1"))
    @Test fun `changed wording shows it again`() = assertTrue(shouldShowNotice("h0", "h1"))
}
