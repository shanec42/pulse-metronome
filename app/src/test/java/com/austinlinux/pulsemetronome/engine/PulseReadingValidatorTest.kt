package com.austinlinux.pulsemetronome.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseReadingValidatorTest {
    @Test
    fun `first reading after reset always accepted regardless of value`() {
        val validator = PulseReadingValidator()
        assertTrue(validator.accept(255))
    }

    @Test
    fun `second reading within 1 bpm of the first is accepted`() {
        val validator = PulseReadingValidator()
        validator.accept(90)
        assertTrue(validator.accept(91))
    }

    @Test
    fun `second reading exactly 1 bpm away is accepted - boundary is inclusive`() {
        val validator = PulseReadingValidator()
        validator.accept(90)
        assertTrue(validator.accept(91))
    }

    @Test
    fun `second reading more than 1 bpm away is rejected and resets the streak to zero`() {
        val validator = PulseReadingValidator()
        validator.accept(90)
        assertFalse(validator.accept(0))
    }

    @Test
    fun `after a rejection the next reading is treated as a fresh first reading - always accepted`() {
        val validator = PulseReadingValidator()
        validator.accept(90)
        validator.accept(0)  // rejected, resets
        assertTrue(validator.accept(45))  // fresh start - accepted unconditionally, NOT compared to 0 or 90
    }

    @Test
    fun `reproduces the human's own example - noise then stabilizing real data`() {
        // 255, 0, 255, 75, 90, 90.2, 91, 90.8 - real data recognizable once consecutive
        // readings stay within 1 BPM of each other, per the human's own description.
        val validator = PulseReadingValidator()
        assertTrue(validator.accept(255))   // 1st after reset - always accepted
        assertFalse(validator.accept(0))    // |0-255|=255 > 1 - rejected, resets
        assertTrue(validator.accept(255))   // fresh 1st - accepted
        assertFalse(validator.accept(75))   // |75-255|=180 > 1 - rejected, resets
        assertTrue(validator.accept(90))    // fresh 1st - accepted
        assertTrue(validator.accept(90))    // |90-90|=0 <= 1 - accepted (90.2 rounds to 90)
        assertTrue(validator.accept(91))    // |91-90|=1 <= 1 - accepted
        assertTrue(validator.accept(91))    // |91-91|=0 <= 1 - accepted (90.8 rounds to 91)
    }

    @Test
    fun `explicit reset clears the streak the same way a rejection would`() {
        val validator = PulseReadingValidator()
        validator.accept(90)
        validator.reset()
        assertTrue(validator.accept(200))  // treated as fresh first reading, not compared to 90
    }
}
