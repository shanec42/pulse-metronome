package com.austinlinux.pulsemetronome.ui.history

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.austinlinux.pulsemetronome.data.PulseSampleDao
import com.austinlinux.pulsemetronome.data.PulseSampleEntity
import com.austinlinux.pulsemetronome.data.SessionDao
import com.austinlinux.pulsemetronome.data.SessionEntity
import com.austinlinux.pulsemetronome.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// entity is kept so the card can redo the stat ranges once its samples load.
data class HistoryItem(val id: Long, val display: SessionSummaryDisplay, val startTimestamp: Long, val pulseLostAtElapsedMs: Long?, val entity: SessionEntity)

/** The swipe-deleted session whose Undo countdown began at [startedAtMs] (SystemClock.elapsedRealtime). */
data class PendingSwipe(val id: Long, val startedAtMs: Long)

private const val UNDO_WINDOW_MS = 4_000L

/** Binds SessionDao.observeAll() for the list, serves each card's samples, and runs swipe-delete with a 4 s Undo window. */
class HistoryViewModel(
    private val sessionDao: SessionDao,
    private val pulseSampleDao: PulseSampleDao,
    settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) : ViewModel() {
    private val hiddenIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _pendingSwipe = MutableStateFlow<PendingSwipe?>(null)
    val pendingSwipe: StateFlow<PendingSwipe?> = _pendingSwipe
    private var commitJob: Job? = null

    val sessions: StateFlow<List<HistoryItem>> = combine(
        sessionDao.observeAll().map { entities -> entities.map { HistoryItem(it.id, toDisplay(it, dateFormatter = ::historyDateLabel), it.startTimestamp, it.pulseLostAtElapsedMs, it) } },
        settingsRepository.pendingDeleteIds,
        hiddenIds
    ) { all, pending, hidden -> visibleSessions(all, { it.id }, pending, hidden) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyEnabled: StateFlow<Boolean> = settingsRepository.settings.map { it.historyEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun samplesFor(id: Long): Flow<List<PulseSampleEntity>> = pulseSampleDao.observeForSession(id)

    /** Hides the card and starts the Undo countdown; returns false (card stays) if another swipe is pending. */
    fun onSwiped(id: Long): Boolean {
        if (!canSwipe(_pendingSwipe.value?.id)) return false
        hiddenIds.value = hiddenIds.value + id
        _pendingSwipe.value = PendingSwipe(id, SystemClock.elapsedRealtime())
        commitJob = viewModelScope.launch { delay(UNDO_WINDOW_MS); commit() }
        return true
    }

    /** Cancels the countdown and brings the card back. */
    fun undo() {
        val id = _pendingSwipe.value?.id ?: return
        commitJob?.cancel()
        hiddenIds.value = hiddenIds.value - id
        _pendingSwipe.value = null
    }

    // Committed ids stay in hiddenIds: Room re-emits asynchronously and removing it would flash the card back.
    private fun commit() {
        val id = _pendingSwipe.value?.id ?: return
        _pendingSwipe.value = null
        appScope.launch { sessionDao.deleteById(id) }
    }

    override fun onCleared() {
        // Leaving History mid-countdown still deletes.
        _pendingSwipe.value?.let { p -> appScope.launch { sessionDao.deleteById(p.id) } }
    }
}
