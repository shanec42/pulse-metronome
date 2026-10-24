package com.austinlinux.pulsemetronome.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormattingTest {
    @Test
    fun `formats zero as 0 00`() { assertEquals("0:00", formatElapsed(0L)) }

    @Test
    fun `formats under a minute correctly`() { assertEquals("0:45", formatElapsed(45_000L)) }

    @Test
    fun `formats over a minute correctly`() { assertEquals("2:05", formatElapsed(125_000L)) }

    @Test
    fun `formats over an hour with hour component`() { assertEquals("1:00:00", formatElapsed(3_600_000L)) }

    @Test
    fun `formats just under an hour without hour component`() { assertEquals("59:59", formatElapsed(3_599_000L)) }
}
