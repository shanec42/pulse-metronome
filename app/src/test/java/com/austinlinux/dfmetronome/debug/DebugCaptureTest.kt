package com.austinlinux.dfmetronome.debug

import org.junit.Assert.assertFalse
import org.junit.Test

class DebugCaptureTest {
    @Test fun `event does not build the message when capture is off`() {
        var built = false
        DebugCapture.event("APP") { built = true; "x" }
        assertFalse(built)
    }
}
