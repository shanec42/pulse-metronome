package com.austinlinux.pulsemetronome.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val CAPTURE_DIR = "debug-capture"
/** Under cacheDir: where Share writes its zip, so Clear can remove old shared copies too. */
const val CAPTURE_SHARE_DIR = "debug-capture-share"
const val CAPTURE_KEEP_DAYS = 7
const val CAPTURE_MAX_TOTAL_BYTES = 50L * 1024 * 1024
private val CAPTURE_NAME = Regex("""capture-(\d{4}-\d{2}-\d{2})\.log""")

/** True for the names the capture writer creates (and so the only files pruning may touch). */
fun isCaptureFileName(name: String): Boolean = CAPTURE_NAME.matches(name)

/** The day file a line written at [timeMs] belongs in. */
fun captureFileName(timeMs: Long, tz: TimeZone = TimeZone.getDefault()): String =
    "capture-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(timeMs)) + ".log"

/** One capture line; newlines are flattened so every event is exactly one line. */
fun captureLine(timeMs: Long, tag: String, message: String, tz: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = tz }.format(Date(timeMs)) +
        " " + tag + " " + message.replace("\r\n", "\n").replace("\n", " | ")

/**
 * Capture files to delete: any day older than the last [CAPTURE_KEEP_DAYS] days (today and the
 * six before it), then the oldest remaining until the total fits [maxTotalBytes]. Age-based, not
 * count-based, so a gap in use doesn't keep stale files. [today] is never selected, so the file
 * being written survives even when it alone is over the cap (the writer stops it instead).
 */
fun selectPrune(sizes: Map<String, Long>, today: String, maxTotalBytes: Long): List<String> {
    val days = sizes.keys.filter { CAPTURE_NAME.matches(it) }.sorted()
    val todayDate = java.time.LocalDate.parse(CAPTURE_NAME.matchEntire(today)!!.groupValues[1])
    val cutoff = "capture-" + todayDate.minusDays((CAPTURE_KEEP_DAYS - 1).toLong()) + ".log"  // ISO names sort by date
    val doomed = days.filter { it < cutoff && it != today }.toMutableList()
    var total = days.filter { it !in doomed }.sumOf { sizes.getValue(it) }
    for (name in days) {
        if (total <= maxTotalBytes) break
        if (name == today || name in doomed) continue
        doomed += name; total -= sizes.getValue(name)
    }
    return doomed
}

/** The muted line under the Settings switch. */
fun captureSummary(fileCount: Int, totalBytes: Long): String =
    if (fileCount == 0) "Nothing saved yet."
    else "Saved on this phone: $fileCount file${if (fileCount == 1) "" else "s"}, " +
        String.format(Locale.US, "%.1f", totalBytes / 1_000_000.0) + " MB. Kept for 7 days."

/** ApplicationExitInfo fields, copied out so formatting and filtering are plain JVM code. */
data class ExitRecord(
    val timestampMs: Long, val pid: Int, val reason: Int, val reasonName: String, val subReason: Int,
    val status: Int, val importance: Int, val pssKb: Long, val rssKb: Long, val description: String?
)

/** Exits newer than [watermark], oldest first, so a burst of restarts is written in order. */
fun exitsSince(records: List<ExitRecord>, watermark: Long): List<ExitRecord> =
    records.filter { it.timestampMs > watermark }.sortedBy { it.timestampMs }

/** One-line description of a past process exit. */
fun exitLine(r: ExitRecord, tz: TimeZone = TimeZone.getDefault()): String =
    "exit at " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = tz }.format(Date(r.timestampMs)) +
        " pid=${r.pid} reason=${r.reasonName}(${r.reason}) subreason=${r.subReason} status=${r.status}" +
        " importance=${r.importance} pssKb=${r.pssKb} rssKb=${r.rssKb} description=${r.description ?: "-"}"

/** The stored Debug capture setting; on unless the user turned it off. */
fun parseDebugCapture(stored: Boolean?): Boolean = stored ?: true
