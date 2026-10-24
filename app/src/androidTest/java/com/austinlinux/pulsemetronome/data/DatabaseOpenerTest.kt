package com.austinlinux.pulsemetronome.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DatabaseOpenerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "opener-test.db"
    private lateinit var backups: File
    private var stamp = 0

    private fun opener(currentVersion: Int = AppDatabase.VERSION) =
        DatabaseOpener(context, name, backups, timestamp = { "20261010-0000%02d".format(stamp++) }, currentVersion = currentVersion)

    @Before fun setUp() {
        context.deleteDatabase(name)
        backups = File(context.cacheDir, "opener-test-backups").apply { deleteRecursively(); mkdirs() }
    }
    @After fun tearDown() { context.deleteDatabase(name); backups.deleteRecursively() }

    private fun session() = SessionEntity(
        startTimestamp = 1000L, endTimestamp = 2000L, targetBpm = 60, rampDurationMin = 30, totalDurationMin = 30,
        maxOffset = 2, startingBpm = 80, startingBpmSource = StartingBpmSource.LIVE_PULSE, endingBpm = 62, endReason = EndReason.DURATION_ELAPSED
    )

    /** Writes user_version directly, simulating a database from another app version. */
    private fun setUserVersion(v: Int) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = v }
    }

    @Test fun freshInstallOpensWithoutBackup() {
        opener().open().close()
        assertEquals(0, backups.listFiles()!!.count { it.name.endsWith(".db") })
    }

    @Test fun sameVersionKeepsDataAndMakesNoBackup() = runBlocking {
        opener().open().apply { sessionDao().insert(session()); close() }
        val db = opener().open()
        assertEquals(1, db.sessionDao().observeAll().first().size)
        db.close()
        assertEquals(0, backups.listFiles()!!.count { it.name.endsWith(".db") })
    }

    @Test fun downgradeMovesTheDatabaseAsideAndStartsFresh() = runBlocking {
        opener().open().apply { sessionDao().insert(session()); close() }
        setUserVersion(AppDatabase.VERSION + 1)
        val db = opener().open()
        assertEquals(0, db.sessionDao().observeAll().first().size)
        db.close()
        val failed = backups.listFiles()!!.single { it.name.startsWith(FAILED_PREFIX) && it.name.endsWith(".db") }
        SQLiteDatabase.openDatabase(failed.path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            old.rawQuery("SELECT count(*) FROM sessions", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
        assertEquals(failed.name, File(backups, UPGRADE_FAILED_MARKER).readText())
    }

    @Test fun olderVersionWithNoMigrationCopiesFirstThenFailsSafely() = runBlocking {
        opener().open().apply { sessionDao().insert(session()); close() }
        setUserVersion(AppDatabase.VERSION + 1)
        opener(currentVersion = AppDatabase.VERSION + 2).open().close()
        assertNotNull(backups.listFiles()!!.singleOrNull { it.name.startsWith(PRE_UPGRADE_PREFIX) })
        assertNotNull(backups.listFiles()!!.singleOrNull { it.name.startsWith(FAILED_PREFIX) && it.name.endsWith(".db") })
    }

    @Test fun noMarkerWhenNothingFailed() {
        opener().open().close()
        assertNull(pendingUpgradeFailure(context, backups))
    }

    @Test fun olderVersionFailureKeepsReadableCopiesAndMarker() = runBlocking {
        opener().open().apply { sessionDao().insert(session()); close() }
        setUserVersion(AppDatabase.VERSION + 1)
        opener(currentVersion = AppDatabase.VERSION + 2).open().close()
        val pre = backups.listFiles()!!.single { it.name.startsWith(PRE_UPGRADE_PREFIX) }
        val failed = backups.listFiles()!!.single { it.name.startsWith(FAILED_PREFIX) && it.name.endsWith(".db") }
        for (f in listOf(pre, failed)) SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            old.rawQuery("SELECT count(*) FROM sessions", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
        assertEquals(failed.name, pendingUpgradeFailure(context, backups))
    }

    @Test fun afterAFailureTheNextOpenIsNormalAndKeepsTheMarker() {
        opener().open().close()
        setUserVersion(AppDatabase.VERSION + 1)
        opener().open().close()
        val before = backups.list()!!.toSet()
        opener().open().close()
        assertEquals(before, backups.list()!!.toSet())
        assertNotNull(pendingUpgradeFailure(context, backups))
        clearUpgradeFailure(context, backups)
        assertNull(pendingUpgradeFailure(context, backups))
    }

    @Test fun onlyTheTwoNewestFailedBackupsAreKept() {
        repeat(3) {
            opener().open().close()
            setUserVersion(AppDatabase.VERSION + 1)
            opener().open().close()
        }
        assertEquals(2, backups.listFiles()!!.count { it.name.startsWith(FAILED_PREFIX) && it.name.endsWith(".db") })
    }

    @Test fun unreadableFileIsMovedAsideNotDeleted() = runBlocking {
        val garbage = ByteArray(100) { (it * 7 + 1).toByte() }
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        path.writeBytes(garbage)
        val db = opener().open()
        assertEquals(0, db.sessionDao().observeAll().first().size)
        db.close()
        val failed = backups.listFiles()!!.single { it.name.startsWith(FAILED_PREFIX) && it.name.endsWith(".db") }
        assertTrue(garbage.contentEquals(failed.readBytes()))
        assertEquals(failed.name, File(backups, UPGRADE_FAILED_MARKER).readText())
    }
}
