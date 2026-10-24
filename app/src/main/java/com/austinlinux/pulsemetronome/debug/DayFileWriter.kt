package com.austinlinux.pulsemetronome.debug

import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/** Appends lines to one file per local day, pruning old days and capping each day's size. Single-threaded use. */
class DayFileWriter(
    private val dir: File,
    private val clock: () -> Long,
    private val tz: TimeZone,
    private val maxDayBytes: Long,
    private val maxTotalBytes: Long = CAPTURE_MAX_TOTAL_BYTES
) {
    private var currentName: String? = null
    private var out: FileOutputStream? = null
    private var bytes = 0L
    private var paused = false
    private var bytesSincePrune = 0L

    /** Appends [line] to today's file, rolling the day and applying the size cap; I/O errors are swallowed. */
    fun write(line: String) {
        try {
            val name = captureFileName(clock(), tz)
            if (name != currentName) openDay(name)
            if (paused || out == null) return
            val data = (line + "\n").toByteArray(Charsets.UTF_8)
            if (bytes + data.size > maxDayBytes) {
                val note = (captureLine(clock(), "APP", PAUSED_TEXT, tz) + "\n")
                    .toByteArray(Charsets.UTF_8)
                out?.apply { write(note); flush() }
                paused = true
                return
            }
            out?.apply { write(data); flush() }
            bytes += data.size
            // Old days are also trimmed mid-day, so the total stays under the cap rather than old days + today's cap.
            bytesSincePrune += data.size
            if (bytesSincePrune >= PRUNE_EVERY_BYTES) { bytesSincePrune = 0; prune(currentName ?: name) }
        } catch (_: Exception) {
        }
    }

    /** Closes the file, deletes every capture file and resets the day state. */
    fun deleteAll() {
        try {
            closeQuietly()
            currentName = null
            bytes = 0
            paused = false
            dir.listFiles()?.filter { isCaptureFileName(it.name) }?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /** Closes the open file. */
    fun close() {
        closeQuietly()
        currentName = null
    }

    private fun closeQuietly() {
        try { out?.close() } catch (_: Exception) {}
        out = null
    }

    private fun openDay(name: String) {
        closeQuietly()
        currentName = null  // stays null if the open fails, so the next write retries
        dir.mkdirs()
        prune(name)
        val f = File(dir, name)
        bytes = f.length()
        // A day already stopped by the cap stays paused after a restart without a second marker line (a capped day with no marker still gets one on the next write).
        paused = endsWithPausedMarker(f)
        out = FileOutputStream(f, true)
        currentName = name
    }

    private fun prune(today: String) {
        try {
            val sizes = dir.listFiles()?.filter { it.isFile }?.associate { it.name to it.length() } ?: emptyMap()
            selectPrune(sizes, today, maxTotalBytes).forEach { File(dir, it).delete() }
        } catch (_: Exception) {
        }
    }

    private fun endsWithPausedMarker(f: File): Boolean = try {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val n = minOf(raf.length(), 300L).toInt()
            val buf = ByteArray(n)
            raf.seek(raf.length() - n); raf.readFully(buf)
            String(buf, Charsets.UTF_8).trimEnd().endsWith(PAUSED_TEXT)
        }
    } catch (_: Exception) { false }

    private companion object {
        const val PAUSED_TEXT = "capture paused for today: size cap reached"
        const val PRUNE_EVERY_BYTES = 1_000_000L
    }
}
