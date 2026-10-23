package com.austinlinux.dfmetronome.service

import com.austinlinux.dfmetronome.alarm.AlarmType
import com.austinlinux.dfmetronome.engine.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide bridge between SessionForegroundService (writer) and the Live Session UI
 * (reader). A StateFlow is used instead of a bound-service Binder because it's simpler to
 * consume from a Compose ViewModel and doesn't require the Activity to bind/unbind across
 * configuration changes.
 */
object SessionStateBus {
    private val _state = MutableStateFlow<SessionState?>(null)
    val state: StateFlow<SessionState?> = _state

    // Set by the service once its AudioEngine exists; null when no session is active.
    // Task 20's beat indicator reads this (not the scheduling loop's intended timestamps)
    // to stay synced with actually-audible playback.
    var audioPlaybackHeadPositionMs: (() -> Long)? = null
        internal set

    // One-shot: the row id of a session that just ended, so the Live Session screen can open its
    // Summary. Published for every ending that has a row; cleared when consumed and at session start.
    private val _endedSessionId = MutableStateFlow<Long?>(null)
    val endedSessionId: StateFlow<Long?> = _endedSessionId
    internal fun publishEndedSessionId(id: Long) { _endedSessionId.value = id }
    fun clearEndedSessionId() { _endedSessionId.value = null }

    // Non-null only while the end-of-session alarm is actually ringing (time-out, non-Silent).
    // Separate from endedSessionId so the Summary outlives the alarm.
    private val _alarm = MutableStateFlow<AlarmType?>(null)
    val alarm: StateFlow<AlarmType?> = _alarm
    internal fun publishAlarm(type: AlarmType) { _alarm.value = type }
    fun clearAlarm() { _alarm.value = null }

    internal fun publish(state: SessionState) { _state.value = state }
    internal fun clear() { _state.value = null; audioPlaybackHeadPositionMs = null }
}
