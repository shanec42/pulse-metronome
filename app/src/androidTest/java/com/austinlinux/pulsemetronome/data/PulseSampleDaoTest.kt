package com.austinlinux.pulsemetronome.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PulseSampleDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() { db.close() }

    private fun newSession() = SessionEntity(
        startTimestamp = 0L, endTimestamp = null, targetBpm = 60, rampDurationMin = 30,
        totalDurationMin = 60, maxOffset = 2, startingBpm = 90,
        startingBpmSource = StartingBpmSource.LIVE_PULSE, endingBpm = null, endReason = null
    )

    @Test
    fun insertPulseSampleWithNullPulse_duringSignalLoss_persistsCorrectly() = runBlocking {
        val sessionId = db.sessionDao().insert(newSession())
        db.pulseSampleDao().insert(PulseSampleEntity(
            sessionId = sessionId, timestamp = 20_000L, pulse = null, metronomeBpm = 75
        ))

        val samples = db.pulseSampleDao().getForSession(sessionId)
        assertEquals(1, samples.size)
        assertNull(samples[0].pulse)
        assertEquals(75, samples[0].metronomeBpm)
    }

    @Test
    fun deletingSession_cascadeDeletesItsPulseSamples() = runBlocking {
        val sessionId = db.sessionDao().insert(newSession())
        db.pulseSampleDao().insert(PulseSampleEntity(sessionId = sessionId, timestamp = 20_000L, pulse = 80, metronomeBpm = 75))
        db.sessionDao().delete(db.sessionDao().getById(sessionId)!!)

        val samples = db.pulseSampleDao().getForSession(sessionId)
        assertEquals(0, samples.size)
    }

    @Test
    fun observeForSession_emitsLateInsertedSample() = runBlocking {
        val sessionId = db.sessionDao().insert(newSession())
        db.pulseSampleDao().insert(PulseSampleEntity(sessionId = sessionId, timestamp = 20_000L, pulse = 80, metronomeBpm = 75))
        val waiter = async { withTimeout(5_000) { db.pulseSampleDao().observeForSession(sessionId).first { it.size == 2 } } }
        db.pulseSampleDao().insert(PulseSampleEntity(sessionId = sessionId, timestamp = 40_000L, pulse = 79, metronomeBpm = 74))
        val samples = waiter.await()
        assertEquals(listOf(20_000L, 40_000L), samples.map { it.timestamp })
    }
}
