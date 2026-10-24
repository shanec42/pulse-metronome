package com.austinlinux.pulsemetronome.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.austinlinux.pulsemetronome.alarm.AlarmType
import com.austinlinux.pulsemetronome.debug.parseDebugCapture
import com.austinlinux.pulsemetronome.engine.DescentPattern
import com.austinlinux.pulsemetronome.engine.MonitorSignalLoss
import com.austinlinux.pulsemetronome.engine.PacedSignalLoss
import com.austinlinux.pulsemetronome.engine.parsePacedSignalLoss
import com.austinlinux.pulsemetronome.engine.parseDescentPattern
import com.austinlinux.pulsemetronome.engine.parseMonitorSignalLoss
import com.austinlinux.pulsemetronome.ui.history.withPendingId
import com.austinlinux.pulsemetronome.ui.history.withoutPendingId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class SettingsValidationResult(val isValid: Boolean, val errors: List<String>)

/**
 * Pure validation, no Android deps - default max offset is now required (>= 1), per the
 * human's decision that it caps steady-state pacing rather than acting only as an edge-case
 * emergency limiter (see RampCalculator's Design History, Task 6).
 */
fun validateSettings(maxOffset: Int?, staticFallbackStartingBpm: Int?): SettingsValidationResult {
    val errors = mutableListOf<String>()
    if (maxOffset == null || maxOffset < 1) errors += "Max offset is required and must be at least 1 BPM"
    if (staticFallbackStartingBpm == null || staticFallbackStartingBpm !in 30..200) {
        errors += "Fallback starting BPM must be 30-200"
    }
    return SettingsValidationResult(isValid = errors.isEmpty(), errors = errors)
}

data class AppSettings(
    val maxOffset: Int,
    val staticFallbackStartingBpm: Int,
    val alarmType: AlarmType,
    val historyEnabled: Boolean = true,
    val descentPattern: DescentPattern = DescentPattern.BELL_CURVE,
    val monitorSignalLoss: MonitorSignalLoss = MonitorSignalLoss.HOLD,
    val pacedSignalLoss: PacedSignalLoss = PacedSignalLoss.HOLD,
    val debugCapture: Boolean = true
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** DataStore-backed persistence for Settings screen values, consumed by Task 22's navigation
 *  layer when building a SessionConfig/AlarmType for SessionForegroundService.newIntent(...). */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val MAX_OFFSET = intPreferencesKey("max_offset")
        val STATIC_FALLBACK_STARTING_BPM = intPreferencesKey("static_fallback_starting_bpm")
        val ALARM_TYPE = stringPreferencesKey("alarm_type")
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val DESCENT_PATTERN = stringPreferencesKey("descent_pattern")
        val MONITOR_SIGNAL_LOSS = stringPreferencesKey("monitor_signal_loss")
        val PACED_SIGNAL_LOSS = stringPreferencesKey("paced_signal_loss")
        val DEBUG_CAPTURE = booleanPreferencesKey("debug_capture")
        val PENDING_DELETES = stringSetPreferencesKey("pending_delete_session_ids")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            maxOffset = prefs[Keys.MAX_OFFSET] ?: 2,
            staticFallbackStartingBpm = prefs[Keys.STATIC_FALLBACK_STARTING_BPM] ?: 70,
            alarmType = prefs[Keys.ALARM_TYPE]?.let { AlarmType.valueOf(it) } ?: AlarmType.SOUND,
            historyEnabled = prefs[Keys.HISTORY_ENABLED] ?: true,
            descentPattern = parseDescentPattern(prefs[Keys.DESCENT_PATTERN]),
            monitorSignalLoss = parseMonitorSignalLoss(prefs[Keys.MONITOR_SIGNAL_LOSS]),
            pacedSignalLoss = parsePacedSignalLoss(prefs[Keys.PACED_SIGNAL_LOSS]),
            debugCapture = parseDebugCapture(prefs[Keys.DEBUG_CAPTURE])
        )
    }

    suspend fun update(maxOffset: Int, staticFallbackStartingBpm: Int, alarmType: AlarmType) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MAX_OFFSET] = maxOffset
            prefs[Keys.STATIC_FALLBACK_STARTING_BPM] = staticFallbackStartingBpm
            prefs[Keys.ALARM_TYPE] = alarmType.name
        }
    }

    suspend fun setHistoryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HISTORY_ENABLED] = enabled }
    }

    suspend fun setDebugCapture(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DEBUG_CAPTURE] = enabled }
    }

    suspend fun setDescentPattern(pattern: DescentPattern) {
        context.settingsDataStore.edit { it[Keys.DESCENT_PATTERN] = pattern.name }
    }

    suspend fun setMonitorSignalLoss(b: MonitorSignalLoss) {
        context.settingsDataStore.edit { it[Keys.MONITOR_SIGNAL_LOSS] = b.name }
    }

    suspend fun setPacedSignalLoss(b: PacedSignalLoss) {
        context.settingsDataStore.edit { it[Keys.PACED_SIGNAL_LOSS] = b.name }
    }

    /** Ids of sessions recorded with History off, deleted once their Summary is left (or at next launch). */
    val pendingDeleteIds: Flow<Set<Long>> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.PENDING_DELETES].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun pendingDeleteIdsNow(): Set<Long> = pendingDeleteIds.first()

    suspend fun addPendingDelete(id: Long) {
        context.settingsDataStore.edit { it[Keys.PENDING_DELETES] = withPendingId(it[Keys.PENDING_DELETES].orEmpty(), id) }
    }

    suspend fun removePendingDelete(id: Long) {
        context.settingsDataStore.edit { it[Keys.PENDING_DELETES] = withoutPendingId(it[Keys.PENDING_DELETES].orEmpty(), id) }
    }

    suspend fun clearPendingDeletes() {
        context.settingsDataStore.edit { it.remove(Keys.PENDING_DELETES) }
    }
}
