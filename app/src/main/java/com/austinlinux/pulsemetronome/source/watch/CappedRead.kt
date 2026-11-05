package com.austinlinux.pulsemetronome.source.watch

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Largest watch log the phone accepts: the watch caps a log at 256 KiB, plus a margin. */
const val MAX_WATCH_LOG_BYTES = 320 * 1024

/** Reads [input] fully, or returns null as soon as it holds more than [limit] bytes (never buffers past limit + one chunk). */
fun readCapped(input: InputStream, limit: Int = MAX_WATCH_LOG_BYTES): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (out.size() + n > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
