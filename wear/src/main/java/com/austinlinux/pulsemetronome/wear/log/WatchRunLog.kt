package com.austinlinux.pulsemetronome.wear.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Per-run event log kept in memory and written to [dir] by [finish]. A finished log stays in
 * [pending] until [markSent], so a run that ended with the phone out of reach is sent later.
 */
class WatchRunLog(
    private val dir: File,
    private val maxBytes: Int = 256 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lines = ArrayDeque<String>()
    private var bytes = 0
    private var sessionId: String? = null

    /** Starts a new run's log, discarding any unfinished one. */
    @Synchronized
    fun begin(sessionId: String) {
        lines.clear()
        bytes = 0
        this.sessionId = sessionId
        add("BEGIN", "session=$sessionId")
    }

    /** Appends one timestamped line; the oldest lines are dropped once the log exceeds the size cap. */
    @Synchronized
    fun event(tag: String, text: String) {
        if (sessionId == null) return
        add(tag, text)
    }

    private fun add(tag: String, text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(clock()))
        val line = "$stamp $tag ${text.replace("\r\n", "\n").replace("\n", " | ")}\n"
        lines.addLast(line)
        bytes += line.toByteArray(Charsets.UTF_8).size
        while (bytes > maxBytes && lines.isNotEmpty()) {
            bytes -= lines.removeFirst().toByteArray(Charsets.UTF_8).size
        }
    }

    /** Writes the run's log into [dir] and ends the run; the file is pending until [markSent]. */
    @Synchronized
    fun finish(): File {
        val id = checkNotNull(sessionId) { "finish() without begin()" }
        dir.mkdirs()
        val f = File(dir, "$PREFIX${sanitize(id)}$SUFFIX")
        f.writeText(lines.joinToString(""), Charsets.UTF_8)
        sessionId = null
        lines.clear()
        bytes = 0
        return f
    }

    /** True between [begin] and [finish]. */
    @Synchronized
    fun isActive(): Boolean = sessionId != null

    /** Finished logs not yet confirmed sent, oldest first. */
    fun pending(): List<File> {
        val all = dir.listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) } ?: return emptyList()
        // A log the phone never accepted is dropped after a week, matching the phone's retention.
        val cutoff = clock() - EXPIRY_MS
        val (old, fresh) = all.partition { it.lastModified() < cutoff }
        old.forEach { it.delete() }
        return fresh.sortedBy { it.lastModified() }
    }

    /** Removes [f] once the phone has received it. */
    fun markSent(f: File) {
        f.delete()
    }

    companion object {
        private const val PREFIX = "runlog-"
        private const val SUFFIX = ".log"
        private const val EXPIRY_MS = 7L * 24 * 3600 * 1000

        /** File-name-safe form of a session id (it arrives off the wire): other characters become '_'. */
        fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "unknown" }

        /** The session id encoded in a pending log's file name. */
        fun sessionIdOf(f: File): String = f.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
    }
}
