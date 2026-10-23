package com.austinlinux.dfmetronome.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PulseSmootherTest {
    @Test
    fun `first reading seeds smoothed value exactly with no lag`() {
        val smoother = PulseSmoother()
        assertEquals(80.0, smoother.update(80.0), 0.0001)
    }

    @Test
    fun `second reading applies EMA formula with alpha 0_3`() {
        val smoother = PulseSmoother()
        smoother.update(80.0)
        val result = smoother.update(90.0)
        assertEquals(83.0, result, 0.0001)
    }

    @Test
    fun `repeated identical readings converge to that value immediately`() {
        val smoother = PulseSmoother()
        smoother.update(70.0)
        val result = smoother.update(70.0)
        assertEquals(70.0, result, 0.0001)
    }

    @Test
    fun `single spike is damped not fully reflected`() {
        val smoother = PulseSmoother()
        repeat(10) { smoother.update(80.0) }
        val spiked = smoother.update(120.0)
        assertEquals(92.0, spiked, 0.0001)
    }

    @Test
    fun `reset clears state so next update seeds fresh`() {
        val smoother = PulseSmoother()
        smoother.update(80.0)
        smoother.update(100.0)
        smoother.reset()
        assertEquals(50.0, smoother.update(50.0), 0.0001)
    }

    @Test
    fun `seed sets the smoothed value directly without going through the EMA formula`() {
        val smoother = PulseSmoother()
        smoother.seed(75.0)
        assertEquals(75.0, smoother.currentValue()!!, 0.0001)
        val result = smoother.update(85.0)
        assertEquals(78.0, result, 0.0001)  // 75 + 0.3*(85-75) = 78.0
    }
}
