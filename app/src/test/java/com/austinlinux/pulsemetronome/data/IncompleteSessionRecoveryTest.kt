package com.austinlinux.pulsemetronome.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeSessionDao : SessionDao {
    val rows = mutableMapOf<Long, SessionEntity>()
    private var nextId = 1L
    override suspend fun insert(session: SessionEntity): Long {
        val id = nextId++
        rows[id] = session.copy(id = id)
        return id
    }
    override suspend fun update(session: SessionEntity) { rows[session.id] = session }
    override suspend fun delete(session: SessionEntity) { rows.remove(session.id) }
    override suspend fun deleteById(id: Long) { rows.remove(id) }
    override suspend fun deleteAll() { rows.clear() }
    override suspend fun getById(id: Long): SessionEntity? = rows[id]
    override fun observeAll() = throw NotImplementedError("not needed for this test")
    override fun observeById(id: Long) = throw NotImplementedError("not needed for this test")
    override suspend fun getIncompleteRows(): List<SessionEntity> = rows.values.filter { it.endTimestamp == null }
    override suspend fun getAll(): List<SessionEntity> = rows.values.sortedBy { it.startTimestamp }
}

class IncompleteSessionRecoveryTest {
    @Test
    fun `rows with null end timestamp are marked INCOMPLETE with recovery time`() = runBlocking {
        val dao = FakeSessionDao()
        val incompleteId = dao.insert(SessionEntity(
            startTimestamp = 100L, endTimestamp = null, targetBpm = 60, rampDurationMin = 30,
            totalDurationMin = 60, maxOffset = 2, startingBpm = 90,
            startingBpmSource = StartingBpmSource.LIVE_PULSE, endingBpm = null, endReason = null
        ))
        val completeId = dao.insert(SessionEntity(
            startTimestamp = 200L, endTimestamp = 5000L, targetBpm = 60, rampDurationMin = 30,
            totalDurationMin = 60, maxOffset = 2, startingBpm = 90,
            startingBpmSource = StartingBpmSource.LIVE_PULSE, endingBpm = 60, endReason = EndReason.DURATION_ELAPSED
        ))

        val recovery = IncompleteSessionRecovery(dao) { 99999L }
        recovery.markIncompleteSessions()

        assertEquals(EndReason.INCOMPLETE, dao.getById(incompleteId)!!.endReason)
        assertEquals(99999L, dao.getById(incompleteId)!!.endTimestamp)
        assertEquals(EndReason.DURATION_ELAPSED, dao.getById(completeId)!!.endReason)
    }

    @Test
    fun `no incomplete rows is a no-op with no crash`() = runBlocking {
        val dao = FakeSessionDao()
        val recovery = IncompleteSessionRecovery(dao) { 0L }
        assertEquals(emptyList<Long>(), recovery.markIncompleteSessions())
    }
}
