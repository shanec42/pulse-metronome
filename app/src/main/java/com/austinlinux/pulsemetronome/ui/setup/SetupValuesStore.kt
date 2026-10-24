package com.austinlinux.pulsemetronome.ui.setup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** The Setup form values remembered between launches. */
data class SavedSetupValues(
    val targetBpm: Int,
    val totalDurationMin: Int,
    val rampDurationMin: Int,
    val rampEditedByUser: Boolean,
    val waitForPulse: Boolean,
    val monitor: Boolean = false
)

/** Applies remembered values to the form, leaving non-form state (scan status etc.) alone. */
fun SetupUiState.withRestored(saved: SavedSetupValues?): SetupUiState = if (saved == null) this else copy(
    targetBpm = saved.targetBpm,
    totalDurationMin = saved.totalDurationMin,
    rampDurationMin = saved.rampDurationMin,
    rampEditedByUser = saved.rampEditedByUser,
    waitForPulse = saved.waitForPulse,
    monitor = saved.monitor
)

/** The values to remember, or null while a field is empty mid-edit (keep the last complete set). */
fun SetupUiState.toSavedValues(): SavedSetupValues? {
    val target = targetBpm ?: return null
    val total = totalDurationMin ?: return null
    val ramp = rampDurationMin ?: return null
    return SavedSetupValues(target, total, ramp, rampEditedByUser, waitForPulse, monitor)
}

interface SetupValuesStore {
    suspend fun load(): SavedSetupValues?
    suspend fun save(values: SavedSetupValues)
}

private val Context.setupDataStore by preferencesDataStore(name = "setup")

/** DataStore-backed SetupValuesStore, alongside the Settings store. */
class DataStoreSetupValuesStore(private val context: Context) : SetupValuesStore {
    private object Keys {
        val TARGET_BPM = intPreferencesKey("target_bpm")
        val TOTAL_DURATION_MIN = intPreferencesKey("total_duration_min")
        val RAMP_DURATION_MIN = intPreferencesKey("ramp_duration_min")
        val RAMP_EDITED_BY_USER = booleanPreferencesKey("ramp_edited_by_user")
        val WAIT_FOR_PULSE = booleanPreferencesKey("wait_for_pulse")
        val MONITOR = booleanPreferencesKey("monitor")
    }

    override suspend fun load(): SavedSetupValues? {
        val prefs = context.setupDataStore.data.first()
        return SavedSetupValues(
            targetBpm = prefs[Keys.TARGET_BPM] ?: return null,
            totalDurationMin = prefs[Keys.TOTAL_DURATION_MIN] ?: return null,
            rampDurationMin = prefs[Keys.RAMP_DURATION_MIN] ?: return null,
            rampEditedByUser = prefs[Keys.RAMP_EDITED_BY_USER] ?: false,
            waitForPulse = prefs[Keys.WAIT_FOR_PULSE] ?: false,
            monitor = prefs[Keys.MONITOR] ?: false
        )
    }

    override suspend fun save(values: SavedSetupValues) {
        context.setupDataStore.edit { prefs ->
            prefs[Keys.TARGET_BPM] = values.targetBpm
            prefs[Keys.TOTAL_DURATION_MIN] = values.totalDurationMin
            prefs[Keys.RAMP_DURATION_MIN] = values.rampDurationMin
            prefs[Keys.RAMP_EDITED_BY_USER] = values.rampEditedByUser
            prefs[Keys.WAIT_FOR_PULSE] = values.waitForPulse
            prefs[Keys.MONITOR] = values.monitor
        }
    }
}
