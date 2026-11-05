package com.austinlinux.pulsemetronome.debug

import android.content.Context

/** Release builds have no upload path (and no Firebase), so there is never an uploader. */
object CaptureUploaders {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): CaptureUploader? = null
}
