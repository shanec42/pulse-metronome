package com.austinlinux.dfmetronome.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomGapTest {
    @Test fun `pinned buttons sit 12 dp above the gesture handle and 8 dp above 3-button navigation`() {
        assertEquals(12.dp, pinnedButtonGap(threeButtonNav = false))
        assertEquals(8.dp, pinnedButtonGap(threeButtonNav = true))
    }
    @Test fun `message bars and list ends use Design's gaps`() {
        assertEquals(12.dp, MESSAGE_BAR_GAP)
        assertEquals(28.dp, LIST_END_GAP)
    }
}
