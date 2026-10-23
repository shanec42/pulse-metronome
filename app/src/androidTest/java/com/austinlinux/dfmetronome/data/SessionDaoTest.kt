package com.austinlinux.dfmetronome.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.sessionDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun newSession(startingBpm: Int = 90) = SessionEntity(
        startTimestamp = 1000L,
        endTimestamp = null,
        targetBpm = 60,
        rampDurationMin = 30,
        totalDurationMin = 60,
        maxOffset = 2,
        startingBpm = startingBpm,
        startingBpmSource = StartingBpmSource.LIVE_PULSE,
        endingBpm = null,
        endReason = null,
        targetReached = false
    )

    @Test
    fun insertAtStart_thenUpdateAtEnd_persistsBothPhases() = runBlocking {
        val id = dao.insert(newSession())
        val inserted = dao.getById(id)!!
        assertNull(inserted.endTimestamp)
        assertNull(inserted.endReason)

        val updated = inserted.copy(
            endTimestamp = 5000L,
            endingBpm = 60,
            endReason = EndReason.DURATION_ELAPSED,
            targetReached = true
        )
        dao.update(updated)

        val fetched = dao.getById(id)!!
        assertEquals(5000L, fetched.endTimestamp)
        assertEquals(EndReason.DURATION_ELAPSED, fetched.endReason)
        assertTrue(fetched.targetReached)
    }

    @Test
    fun monitorFlagRoundTrips() = runBlocking {
        val id = dao.insert(newSession().copy(monitor = true))
        assertTrue(dao.getById(id)!!.monitor)
    }

    @Test
    fun focusPercentRoundTrips() = runBlocking {
        val id = dao.insert(newSession().copy(focusPercent = 73))
        assertEquals(73, dao.getById(id)!!.focusPercent)
    }

    @Test
    fun getIncompleteRows_returnsOnlyRowsWithNullEndTimestamp() = runBlocking {
        val completeId = dao.insert(newSession())
        dao.update(dao.getById(completeId)!!.copy(endTimestamp = 2000L, endReason = EndReason.USER_STOPPED))
        val incompleteId = dao.insert(newSession(startingBpm = 80))

        val incomplete = dao.getIncompleteRows()
        assertEquals(1, incomplete.size)
        assertEquals(incompleteId, incomplete[0].id)
    }

    @Test
    fun observeAll_emitsSessionsOrderedByStartTimestampDescending() = runBlocking {
        dao.insert(newSession().copy(startTimestamp = 1000L))
        dao.insert(newSession().copy(startTimestamp = 3000L))
        dao.insert(newSession().copy(startTimestamp = 2000L))

        val all = dao.observeAll().first()
        assertEquals(listOf(3000L, 2000L, 1000L), all.map { it.startTimestamp })
    }

    @Test
    fun observeById_emitsUpdatesAndNullForMissing() = runBlocking {
        val id = db.sessionDao().insert(newSession())
        assertEquals(null, db.sessionDao().observeById(id).first()!!.endingBpm)
        db.sessionDao().update(db.sessionDao().getById(id)!!.copy(endingBpm = 61))
        assertEquals(61, withTimeout(5_000) { db.sessionDao().observeById(id).first { it?.endingBpm == 61 } }!!.endingBpm)
        assertEquals(null, db.sessionDao().observeById(id + 999).first())
    }

    @Test
    fun deleteById_removesSessionAndItsSamples() = runBlocking {
        val keep = db.sessionDao().insert(newSession())
        val gone = db.sessionDao().insert(newSession())
        db.pulseSampleDao().insert(PulseSampleEntity(sessionId = gone, timestamp = 20_000L, pulse = 70, metronomeBpm = 70))
        db.sessionDao().deleteById(gone)
        assertEquals(null, db.sessionDao().getById(gone))
        assertEquals(0, db.pulseSampleDao().getForSession(gone).size)
        assertEquals(keep, db.sessionDao().getById(keep)!!.id)
    }

    @Test
    fun deleteAll_removesEverything() = runBlocking {
        val a = db.sessionDao().insert(newSession())
        db.pulseSampleDao().insert(PulseSampleEntity(sessionId = a, timestamp = 20_000L, pulse = 70, metronomeBpm = 70))
        db.sessionDao().insert(newSession())
        db.sessionDao().deleteAll()
        assertEquals(0, db.sessionDao().observeAll().first().size)
        assertEquals(0, db.pulseSampleDao().getForSession(a).size)
    }
}
