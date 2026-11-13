package com.austinlinux.pulsemetronome.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitbitSetupGuideTest {
    @Test
    fun galleryUrls_matchSpec() {
        assertEquals(
            "https://gallery.fitbit.com/details/6503a799-37f7-43bf-8775-23f1742e2a4e",
            FitbitWatchFamily.OS5.galleryUrl
        )
        assertEquals(
            "https://gallery.fitbit.com/details/799d08f9-77d4-4a73-81dc-b187159a7124?key=c05ef0f4-c8f7-4459-94f7-50a209108fc6",
            FitbitWatchFamily.OS4.galleryUrl
        )
    }

    @Test
    fun fitbitAppInstalled_queriesFitbitPackage() {
        assertTrue(isFitbitAppInstalled { it == "com.fitbit.FitbitMobile" })
        assertFalse(isFitbitAppInstalled { false })
    }
}
