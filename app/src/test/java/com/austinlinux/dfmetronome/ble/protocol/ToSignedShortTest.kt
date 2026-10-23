package com.austinlinux.dfmetronome.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ToSignedShortTest {
    @Test
    fun `zero value`() {
        assertEquals(0, toSignedShort(low = 0x00, high = 0x00))
    }

    @Test
    fun `positive value combines little endian`() {
        assertEquals(79, toSignedShort(low = 0x4F, high = 0x00))
    }

    @Test
    fun `negative value via high bit set`() {
        assertEquals(-1, toSignedShort(low = 0xFF, high = 0xFF))
    }

    @Test
    fun `max positive signed short`() {
        assertEquals(32767, toSignedShort(low = 0xFF, high = 0x7F))
    }

    @Test
    fun `invalid pulse rate sentinel round trips as plain int 511`() {
        assertEquals(511, toSignedShort(low = 0xFF, high = 0x01))
    }
}
