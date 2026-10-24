package com.austinlinux.pulsemetronome.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/** Writes every process exit Android recorded since the last one captured (API 30+). */
object ExitHistory {
    private const val WATERMARK_FILE = "exit-watermark"
    private const val FIRST_RUN_LIMIT = 5
    private val SUBREASON = Regex("""subreason=(\d+)""")

    /** Writes the new exits to the capture; call off the main thread. */
    fun record(context: Context, dir: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            DebugCapture.event("EXIT") { "exit history needs Android 11+" }
            return
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val records = am.getHistoricalProcessExitReasons(context.packageName, 0, 0).map { toRecord(it) }
            dir.mkdirs()
            val file = File(dir, WATERMARK_FILE)
            val hasWatermark = file.isFile
            val watermark = if (hasWatermark) file.readText().trim().toLongOrNull() ?: 0L else 0L
            var fresh = exitsSince(records, watermark)
            // First run: don't flood the log with the whole history.
            if (!hasWatermark) fresh = fresh.takeLast(FIRST_RUN_LIMIT)
            for (r in fresh) DebugCapture.event("EXIT") { exitLine(r) }
            val newest = records.maxOfOrNull { it.timestampMs }
            // Only advance once the lines were actually captured, so exits aren't lost if capture was just switched off.
            if (newest != null && DebugCapture.enabled) file.writeText(newest.toString())
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun toRecord(i: ApplicationExitInfo): ExitRecord = ExitRecord(
        timestampMs = i.timestamp, pid = i.pid, reason = i.reason, reasonName = reasonName(i.reason),
        subReason = SUBREASON.find(i.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1,
        status = i.status, importance = i.importance, pssKb = i.pss, rssKb = i.rss, description = i.description
    )

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN"
    }
}
