package com.austinlinux.dfmetronome.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.austinlinux.dfmetronome.data.PulseSampleDao
import com.austinlinux.dfmetronome.data.SessionDao
import com.austinlinux.dfmetronome.alarm.AlarmType
import com.austinlinux.dfmetronome.service.SessionStateBus
import com.austinlinux.dfmetronome.ui.graph.SessionGraphModel
import com.austinlinux.dfmetronome.ui.graph.plannedDurationMsIfTimedOut
import com.austinlinux.dfmetronome.ui.history.SessionSummaryDisplay
import com.austinlinux.dfmetronome.ui.history.historyDateLabel
import com.austinlinux.dfmetronome.ui.history.shortDateLabel
import com.austinlinux.dfmetronome.ui.history.toDisplay
import com.austinlinux.dfmetronome.ui.history.shouldDeleteOnLeave
import com.austinlinux.dfmetronome.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface SummaryUi {
    data object Loading : SummaryUi
    data object Missing : SummaryUi
    data class Ready(val display: SessionSummaryDisplay, val shortDate: String, val graph: SessionGraphModel) : SummaryUi
}

/** Watches one session row and its samples so late sample inserts still reach the graph. */
class SessionSummaryViewModel(
    private val sessionId: Long,
    private val sessionDao: SessionDao,
    pulseSampleDao: PulseSampleDao,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) : ViewModel() {
    /** True while this session is recorded as pending-delete (History was off when it ran). */
    val isPending: StateFlow<Boolean> = settingsRepository.pendingDeleteIds.map { sessionId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Deletes this session; navigation is left to the Missing-row auto-leave so it pops exactly once. */
    fun deleteThisSession() {
        appScope.launch {
            sessionDao.deleteById(sessionId)
            settingsRepository.removePendingDelete(sessionId)
        }
    }

    /** Deletes a just-finished session recorded with History off when the user leaves its Summary. */
    fun deleteIfUnsaved(fromSession: Boolean) {
        appScope.launch {
            if (shouldDeleteOnLeave(fromSession, sessionId, settingsRepository.pendingDeleteIdsNow())) {
                sessionDao.deleteById(sessionId)
                settingsRepository.removePendingDelete(sessionId)
            }
        }
    }

    val ui: StateFlow<SummaryUi> = combine(sessionDao.observeById(sessionId), pulseSampleDao.observeForSession(sessionId)) { s, samples ->
        if (s == null) SummaryUi.Missing
        else SummaryUi.Ready(
            display = toDisplay(s, samples, ::historyDateLabel),
            shortDate = shortDateLabel(s.startTimestamp),
            graph = SessionGraphModel.from(samples, s.startTimestamp, s.pulseLostAtElapsedMs, s.endTimestamp, s.plannedDurationMsIfTimedOut())
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SummaryUi.Loading)

    val alarm: StateFlow<AlarmType?> = SessionStateBus.alarm
    fun clearAlarm() = SessionStateBus.clearAlarm()
}
