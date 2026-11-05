package com.austinlinux.pulsemetronome.source.watch

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CappedReadTest {
    private fun bytes(n: Int) = ByteArray(n) { 7 }

    @Test fun underLimit_returnsAll() {
        assertArrayEquals(bytes(100), readCapped(ByteArrayInputStream(bytes(100)), 1000))
    }

    @Test fun atLimit_returnsAll() {
        assertArrayEquals(bytes(20_000), readCapped(ByteArrayInputStream(bytes(20_000)), 20_000))
    }

    @Test fun overLimit_returnsNull() {
        assertNull(readCapped(ByteArrayInputStream(bytes(20_001)), 20_000))
    }

    @Test fun empty_returnsEmpty() {
        assertArrayEquals(ByteArray(0), readCapped(ByteArrayInputStream(ByteArray(0)), 10))
    }
}
