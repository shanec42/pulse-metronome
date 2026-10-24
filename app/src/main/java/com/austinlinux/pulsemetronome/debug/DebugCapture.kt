package com.austinlinux.pulsemetronome.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Rolling on-phone diagnostic log for debug builds. Nothing here touches an Android API until
 * [init] / [setEnabled], so [event] is safe (and free) while capture is off, including in JVM tests.
 */
object DebugCapture {
    private sealed class Item {
        class Line(val text: String) : Item()
        class Clear(val done: CountDownLatch) : Item()
        object Open : Item()
        object Close : Item()
    }

    private val queue = LinkedBlockingQueue<Item>(10_000)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var appContext: Context? = null
    private var dir: File? = null
    private var available = false
    // Single-writer design: one daemon writer thread is started on the first enable and lives for the
    // process. Disabling only queues Close (it closes the day file; the thread idles), so exactly one
    // thread ever consumes the queue and no stop/start race can create a second writer.
    private var writerThread: Thread? = null
    // One running logcat per mirror tag (LOGCAT, SYSLOG); assigned under this object's lock.
    private val logcatProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()

    /** System log tags (all processes) that explain a kill: ActivityManager, lmkd and the events-buffer kill records, plus power-saver changes. */
    private val SYSTEM_LOG_FILTER = listOf(
        "ActivityManager:I", "lmkd:I", "lowmemorykiller:I", "libprocessgroup:I",
        "am_kill:I", "am_proc_died:I", "killinfo:I", "am_low_memory:I",
        "PowerManagerService:I", "BatterySaverController:I", "BatterySaverStateMachine:I", "*:S"
    )

    /** True once `adb shell pm grant <pkg> android.permission.READ_LOGS` has been run on this debug install. */
    private fun hasSystemLogAccess(): Boolean =
        appContext?.checkSelfPermission(android.Manifest.permission.READ_LOGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private var logcatStartedBefore = false

    // Bumped on every enable and disable; a logcat mirror only enqueues while its own generation is current.
    @Volatile private var generation = 0

    @Volatile var enabled: Boolean = false
        private set

    /** True when this is a debuggable build; release builds never capture. */
    fun isAvailable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Remembers the context and capture directory; idempotent and starts nothing. */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        dir = File(app.filesDir, CAPTURE_DIR)
        available = isAvailable(app)
    }

    /** Starts or stops the writer, logcat mirror and listeners; no-op unless initialised and available. */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (appContext == null || !available || enabled == this.enabled) return
        if (enabled) start() else stop()
    }

    private fun start() {
        val d = dir ?: return
        d.mkdirs()
        if (writerThread == null) {
            val writer = DayFileWriter(d, System::currentTimeMillis, TimeZone.getDefault(), CAPTURE_MAX_TOTAL_BYTES)
            writerThread = Thread({ runWriter(writer) }, "PMCaptureWriter").apply { isDaemon = true; start() }
        }
        offerWithin(Item.Open)
        val gen = ++generation
        val pid = android.os.Process.myPid()
        val tail = logcatStartedBefore
        logcatStartedBefore = true
        Thread({
            // PMCapture:S keeps our own Log.i mirror out, so events aren't recorded twice.
            runLogcat("LOGCAT", tail, gen) { fromNewest ->
                listOf("logcat", "-v", "threadtime", "--pid=$pid") + (if (fromNewest) listOf("-T", "1") else emptyList()) + listOf("PMCapture:S", "*:V")
            }
        }, "PMCaptureLogcat").apply { isDaemon = true; start() }
        val systemLogs = hasSystemLogAccess()
        if (systemLogs) {
            // Only the kill and power lines: they say who ended the process (the app's own lines can't).
            Thread({
                runLogcat("SYSLOG", true, gen) { _ ->
                    listOf("logcat", "-b", "main,system,events", "-v", "threadtime", "-T", "1") + SYSTEM_LOG_FILTER
                }
            }, "PMCaptureSyslog").apply { isDaemon = true; start() }
        }
        this.enabled = true
        notifyListeners(true)
        event("APP") { "system log access: " + if (systemLogs) "granted (SYSLOG lines on)" else "not granted (adb shell pm grant ${appContext?.packageName} android.permission.READ_LOGS)" }
    }

    private fun stop() {
        this.enabled = false
        generation++
        notifyListeners(false)
        for (p in logcatProcesses.values) try { p.destroy() } catch (_: Exception) {}
        logcatProcesses.clear()
        // A full queue must not leave the file open after disable: drop the backlog rather than the Close.
        if (!offerWithin(Item.Close)) { queue.clear(); queue.offer(Item.Close) }
    }

    private fun offerWithin(item: Item): Boolean =
        try { queue.offer(item, 2, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }

    private fun notifyListeners(on: Boolean) {
        for (l in listeners) runCatching { l(on) }
    }

    private fun runWriter(writer: DayFileWriter) {
        // Lines that slip in between Close and the next Open (a race with disable) are dropped, so the
        // day file is never reopened while capture is off.
        var open = false
        while (true) {
            try {
                when (val item = queue.take()) {
                    is Item.Line -> if (open) writer.write(item.text)
                    is Item.Clear -> { writer.deleteAll(); item.done.countDown() }
                    Item.Open -> open = true
                    Item.Close -> { open = false; writer.close() }
                }
            } catch (_: InterruptedException) {
                // Never interrupted by us; ignore so the single writer keeps running.
            } catch (_: Throwable) {
            }
        }
    }

    /** Mirrors one logcat command into the capture under [tag], restarting with backoff while [gen] is current. */
    private fun runLogcat(tag: String, tail: Boolean, gen: Int, command: (fromNewest: Boolean) -> List<String>) {
        // Restarts while this generation is current: logcat can end mid-night (logd restart, buffer clear)
        // and the BLE/raw-frame lines it carries are the ones an overnight failure needs.
        var fromNewest = tail
        var backoffMs = 5_000L
        while (generation == gen) {
            val startedAt = System.currentTimeMillis()
            try {
                val p = ProcessBuilder(command(fromNewest)).redirectErrorStream(true).start()
                // Checked under the same lock as stop(), so either stop() sees this process or we see the new generation.
                synchronized(this) {
                    if (generation != gen) { p.destroy(); return }  // disabled while starting
                    logcatProcesses[tag] = p
                }
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { if (generation == gen) enqueue(tag, it) }
                }
                if (generation != gen) return
                val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
                if (generation == gen) enqueue(tag, "mirror ended (exit=$code), restarting")
            } catch (e: Exception) {
                if (generation == gen) enqueue(tag, "unavailable: $e")
            }
            fromNewest = true
            // A mirror that dies quickly backs off (up to 5 min) so a broken logcat can't spin.
            backoffMs = if (System.currentTimeMillis() - startedAt < 60_000L) minOf(backoffMs * 2, 300_000L) else 5_000L
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { return }
        }
    }

    /** Records an event when capture is on; [message] is not evaluated when it is off. */
    inline fun event(tag: String, message: () -> String) {
        if (enabled) record(tag, message())
    }

    @PublishedApi
    internal fun record(tag: String, message: String) {
        runCatching { Log.i("PMCapture", "$tag $message") }
        enqueue(tag, message)
    }

    @PublishedApi
    internal fun enqueue(tag: String, message: String) {
        queue.offer(Item.Line(captureLine(System.currentTimeMillis(), tag, message)))
    }

    /** Registers a callback for capture being switched on or off. */
    fun addEnabledListener(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    /** Capture files, oldest first. */
    fun files(): List<File> =
        dir?.listFiles()?.filter { it.isFile && isCaptureFileName(it.name) }?.sortedBy { it.name } ?: emptyList()

    /** Deletes all capture files then logs the outcome; blocks, so call off the main thread. */
    @Synchronized
    fun clear() {
        var ok = true
        if (writerThread != null) {
            val latch = CountDownLatch(1)
            try {
                ok = queue.offer(Item.Clear(latch), 3, TimeUnit.SECONDS) && latch.await(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                ok = false
            }
        } else {
            files().forEach { it.delete() }
        }
        // Old shared zips hold a full copy of the capture, so Clear removes them as well.
        runCatching { appContext?.let { File(it.cacheDir, CAPTURE_SHARE_DIR).listFiles()?.forEach { f -> f.delete() } } }
        if (ok) event("APP") { "capture cleared" } else event("APP") { "capture clear failed" }
    }

    /** Zips the capture files into [dest] (replacing old shares beside it); false when empty or on error. */
    fun zipTo(dest: File): Boolean {
        val list = files()
        if (list.isEmpty()) return false
        return try {
            dest.parentFile?.mkdirs()
            dest.parentFile?.listFiles()?.forEach { if (it != dest) it.delete() }
            if (dest.exists()) dest.delete()
            ZipOutputStream(dest.outputStream().buffered()).use { zip ->
                for (f in list) {
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
