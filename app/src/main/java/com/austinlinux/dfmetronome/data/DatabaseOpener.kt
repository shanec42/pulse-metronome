package com.austinlinux.dfmetronome.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val PRE_UPGRADE_PREFIX = "pre-upgrade-"
const val FAILED_PREFIX = "failed-"
const val UPGRADE_FAILED_MARKER = "UPGRADE_FAILED"
private const val TAG = "DatabaseOpener"

enum class OpenPlan { FRESH, SAME, UPGRADE, DOWNGRADE, UNREADABLE }

/** What opening will do, from the file's user_version (null = no file) and the app's version. */
fun planOpen(onDiskVersion: Int?, current: Int, fileExists: Boolean = onDiskVersion != null): OpenPlan = when {
    onDiskVersion == null && fileExists -> OpenPlan.UNREADABLE
    onDiskVersion == null || onDiskVersion == 0 -> OpenPlan.FRESH
    onDiskVersion == current -> OpenPlan.SAME
    onDiskVersion < current -> OpenPlan.UPGRADE
    else -> OpenPlan.DOWNGRADE
}

/**
 * Backups of [prefix] beyond the [keep] newest (names sort by their timestamp). [protect], the backup
 * just written, is never returned and counts toward [keep]: a clock that went backwards must not
 * make the newest backup look oldest and delete it.
 */
fun backupsToPrune(names: List<String>, prefix: String, keep: Int = 2, protect: String? = null): List<String> {
    val candidates = names.filter { it.startsWith(prefix) && it.endsWith(".db") && it != protect }.sortedDescending()
    return candidates.drop(if (protect != null && protect in names) keep - 1 else keep)
}

fun defaultBackupDir(context: Context): File = File(context.filesDir, "db-backups")

/** The failed backup's file name if an upgrade failed and the person hasn't been told yet. */
fun pendingUpgradeFailure(context: Context, backupDir: File = defaultBackupDir(context)): String? =
    File(backupDir, UPGRADE_FAILED_MARKER).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

fun clearUpgradeFailure(context: Context, backupDir: File = defaultBackupDir(context)) { File(backupDir, UPGRADE_FAILED_MARKER).delete() }

fun failedBackupFile(context: Context, name: String, backupDir: File = defaultBackupDir(context)): File = File(backupDir, name)

/** SQLite opens with this handler so a corrupt file is reported, never deleted (the framework default deletes it). */
private val KEEP_CORRUPT_FILES = DatabaseErrorHandler { }

/** Best-effort fold of a database's WAL into its main file, so a single-file copy is complete. */
fun checkpointQuietly(file: File) {
    runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, KEEP_CORRUPT_FILES).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
    }
}

/**
 * The only way the app opens its database. (Room's own helper may still delete a file SQLite
 * reports as corrupt; that device-level failure is outside the app's control.) Copies the file before an upgrade; if Room can't
 * open it (missing/failed migration, or a database newer than the app), moves it into the
 * backup folder and opens a fresh one instead of wiping anything.
 */
class DatabaseOpener(
    private val context: Context,
    private val dbName: String = DB_NAME,
    private val backupDir: File = defaultBackupDir(context),
    private val build: (Context, String) -> AppDatabase = ::buildRoom,
    private val timestamp: () -> String = { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) }
) {
    fun open(): AppDatabase {
        val file = context.getDatabasePath(dbName)
        val exists = file.exists()
        val onDisk = if (exists) readUserVersion(file) else null
        val plan = planOpen(onDisk, AppDatabase.VERSION, exists)
        if (plan == OpenPlan.UPGRADE) {
            runCatching { copyAside(file, PRE_UPGRADE_PREFIX, onDisk!!) }.onFailure { Log.w(TAG, "pre-upgrade copy failed", it) }
        }
        if (plan == OpenPlan.UNREADABLE) {
            // Room's corruption handler would delete a file SQLite rejects; move it aside first so nothing is lost.
            Log.e(TAG, "database file is unreadable; moving it aside")
            runCatching { moveAside(file, 0) }.getOrElse {
                throw IllegalStateException("database could not be opened or moved aside", it)
            }
        }
        val db = build(context, dbName)
        return try {
            db.openHelper.writableDatabase
            db
        } catch (e: Exception) {
            Log.e(TAG, "database could not be opened at v${AppDatabase.VERSION} (on disk v$onDisk); moving it aside", e)
            runCatching { db.close() }
            // Moving can only fail on a broken disk; crashing then is the only outcome that deletes nothing.
            runCatching { moveAside(file, onDisk ?: 0) }.getOrElse {
                throw IllegalStateException("database could not be opened or moved aside", it)
            }
            build(context, dbName).also { it.openHelper.writableDatabase }
        }
    }

    private fun readUserVersion(file: File): Int? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, KEEP_CORRUPT_FILES).use { it.version }
    }.getOrNull()

    private fun copyAside(file: File, prefix: String, version: Int) {
        backupDir.mkdirs()
        checkpointQuietly(file)
        val target = freeFile("$prefix${timestamp()}-v$version")
        file.copyTo(target, overwrite = false)
        prune(prefix, target.name)
    }

    /** Moves (never deletes) the database and its side files into the backup folder; returns the backup's name. */
    private fun moveAside(file: File, version: Int): String {
        backupDir.mkdirs()
        checkpointQuietly(file)
        val target = freeFile("$FAILED_PREFIX${timestamp()}-v$version")
        val name = target.name
        // Marker first: if the process dies mid-move the person is still told; a marker naming an
        // unmoved file is harmless (the next open fails again and rewrites it).
        File(backupDir, UPGRADE_FAILED_MARKER).writeText(name)
        if (!file.renameTo(target)) { file.copyTo(target, overwrite = false); file.delete() }
        for (suffix in listOf("-wal", "-shm")) {
            val side = File(file.path + suffix)
            if (side.exists() && !side.renameTo(File(target.path + suffix))) { side.copyTo(File(target.path + suffix), true); side.delete() }
        }
        prune(FAILED_PREFIX, name)
        return name
    }

    /** A backup file that doesn't exist yet: `<base>.db`, else `<base>-2.db`, `-3`, ... so a same-second backup never overwrites another. */
    private fun freeFile(base: String): File {
        var n = 1
        while (true) {
            val f = File(backupDir, if (n == 1) "$base.db" else "$base-$n.db")
            if (!f.exists()) return f
            n++
        }
    }

    private fun prune(prefix: String, keepName: String) {
        backupsToPrune(backupDir.list()?.toList().orEmpty(), prefix, protect = keepName).forEach { old ->
            File(backupDir, old).delete(); File(backupDir, "$old-wal").delete(); File(backupDir, "$old-shm").delete()
        }
    }
}
