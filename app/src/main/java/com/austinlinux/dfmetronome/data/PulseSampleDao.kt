package com.austinlinux.dfmetronome.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PulseSampleDao {
    @Insert
    suspend fun insert(sample: PulseSampleEntity): Long

    @Query("SELECT * FROM pulse_samples ORDER BY sessionId ASC, timestamp ASC")
    suspend fun getAll(): List<PulseSampleEntity>

    @Query("SELECT * FROM pulse_samples WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getForSession(sessionId: Long): List<PulseSampleEntity>

    @Query("SELECT * FROM pulse_samples WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeForSession(sessionId: Long): Flow<List<PulseSampleEntity>>
}
