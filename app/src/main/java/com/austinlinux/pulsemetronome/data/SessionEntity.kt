package com.austinlinux.pulsemetronome.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class StartingBpmSource { LIVE_PULSE, STATIC_FALLBACK }
enum class EndReason { DURATION_ELAPSED, USER_STOPPED, INCOMPLETE }

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimestamp: Long,
    val endTimestamp: Long?,
    val targetBpm: Int,
    val rampDurationMin: Int,
    val totalDurationMin: Int,
    val maxOffset: Int,  // non-nullable - max_offset is now a required setting, see Task 19
    val startingBpm: Int,
    val startingBpmSource: StartingBpmSource,
    val endingBpm: Int?,
    val endReason: EndReason?,
    val targetReached: Boolean = false,
    // New in schema v2 (bell-curve pacing redesign) - all nullable since they're only
    // populated at SessionEnded, so an INCOMPLETE (crashed) row never gets them, and a
    // session with no accepted pulse reading (mainly a StartMode.NO_PULSE session) has none to record.
    val highestPulse: Int? = null,
    val lowestPulse: Int? = null,
    // Elapsed session time (ms since RUNNING began, NOT wall-clock) at which the pulse
    // signal was lost and never recovered before session end. Null if the signal was never
    // lost, or was lost and recovered before the session ended.
    val pulseLostAtElapsedMs: Long? = null,
    // New in schema v3 (monitor mode): the metronome followed the pulse, with no target.
    @ColumnInfo(defaultValue = "0") val monitor: Boolean = false,
    // New in schema v4: Focus %, paced sessions only
    val focusPercent: Int? = null
)
