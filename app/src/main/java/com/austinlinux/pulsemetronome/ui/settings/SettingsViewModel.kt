package com.austinlinux.pulsemetronome.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.austinlinux.pulsemetronome.alarm.AlarmType
import com.austinlinux.pulsemetronome.data.PulseSampleDao
import com.austinlinux.pulsemetronome.data.SessionDao
import com.austinlinux.pulsemetronome.ui.history.historyCsv
import com.austinlinux.pulsemetronome.engine.DescentPattern
import com.austinlinux.pulsemetronome.engine.MonitorSignalLoss
import com.austinlinux.pulsemetronome.engine.PacedSignalLoss
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val maxOffset: Int? = 2,
    val staticFallbackStartingBpm: Int? = 70,
    val alarmType: AlarmType = AlarmType.SOUND,
    val historyEnabled: Boolean = true,
    val descentPattern: DescentPattern = DescentPattern.BELL_CURVE,
    val monitorSignalLoss: MonitorSignalLoss = MonitorSignalLoss.HOLD,
    val pacedSignalLoss: PacedSignalLoss = PacedSignalLoss.HOLD,
    val debugCapture: Boolean = true,
    val loaded: Boolean = false
) {
    val validation: SettingsValidationResult get() = validateSettings(maxOffset, staticFallbackStartingBpm)
}

/**
 * Wraps SettingsRepository as a StateFlow<SettingsUiState> for the Compose screen,
 * re-running validateSettings on every change - same pattern as Task 18's SetupViewModel.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val sessionDao: SessionDao,
    private val pulseSampleDao: PulseSampleDao
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    maxOffset = settings.maxOffset,
                    staticFallbackStartingBpm = settings.staticFallbackStartingBpm,
                    alarmType = settings.alarmType,
                    historyEnabled = settings.historyEnabled,
                    descentPattern = settings.descentPattern,
                    monitorSignalLoss = settings.monitorSignalLoss,
                    pacedSignalLoss = settings.pacedSignalLoss,
                    debugCapture = settings.debugCapture,
                    loaded = true
                )
            }
        }
    }

    fun onMaxOffsetChanged(value: Int?) {
        _uiState.value = _uiState.value.copy(maxOffset = value)
        persistIfValid()
    }

    fun onStaticFallbackStartingBpmChanged(value: Int?) {
        _uiState.value = _uiState.value.copy(staticFallbackStartingBpm = value)
        persistIfValid()
    }

    fun onAlarmTypeChanged(value: AlarmType) {
        _uiState.value = _uiState.value.copy(alarmType = value)
        persistIfValid()
    }

    fun onHistoryEnabledChanged(value: Boolean) {
        viewModelScope.launch { repository.setHistoryEnabled(value) }
    }

    fun onDebugCaptureChanged(value: Boolean) {
        _uiState.value = _uiState.value.copy(debugCapture = value)
        viewModelScope.launch { repository.setDebugCapture(value) }
    }

    fun onDescentPatternChanged(value: DescentPattern) {
        _uiState.value = _uiState.value.copy(descentPattern = value)
        viewModelScope.launch { repository.setDescentPattern(value) }
    }

    fun onPacedSignalLossChanged(v: PacedSignalLoss) {
        _uiState.value = _uiState.value.copy(pacedSignalLoss = v)
        viewModelScope.launch { repository.setPacedSignalLoss(v) }
    }

    fun onMonitorSignalLossChanged(v: MonitorSignalLoss) {
        _uiState.value = _uiState.value.copy(monitorSignalLoss = v)
        viewModelScope.launch { repository.setMonitorSignalLoss(v) }
    }

    /** The whole History as CSV text, for the Export history action. */
    suspend fun historyCsvText(): String =
        historyCsv(sessionDao.getAll(), pulseSampleDao.getAll(), repository.pendingDeleteIdsNow())

    /** Deletes every stored session and forgets any pending-delete ids. */
    fun deleteAllHistory() {
        viewModelScope.launch {
            sessionDao.deleteAll()
            repository.clearPendingDeletes()
        }
    }

    private fun persistIfValid() {
        val state = _uiState.value
        if (!state.validation.isValid) return
        val maxOffset = state.maxOffset ?: return
        val staticFallbackStartingBpm = state.staticFallbackStartingBpm ?: return
        viewModelScope.launch {
            repository.update(maxOffset, staticFallbackStartingBpm, state.alarmType)
        }
    }
}
