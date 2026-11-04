package com.austinlinux.pulsemetronome.source

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's chosen pulse source, readable synchronously and observable. */
interface SourceChoiceStore {
    val choice: StateFlow<PulseSourceKind>
    fun set(kind: PulseSourceKind)
}

/** Key-value-backed store; an absent or unrecognised stored value decodes to OXIMETER. */
class KeyValueSourceChoiceStore(
    get: () -> String?,
    private val put: (String) -> Unit,
) : SourceChoiceStore {
    private val state = MutableStateFlow(
        PulseSourceKind.values().firstOrNull { it.name == get() } ?: PulseSourceKind.OXIMETER
    )
    override val choice: StateFlow<PulseSourceKind> = state.asStateFlow()

    override fun set(kind: PulseSourceKind) {
        put(kind.name)
        state.value = kind
    }
}

private const val PREFS_NAME = "ble_device_address_store" // same file as the oximeter address (design 6.2)
private const val KEY_PULSE_SOURCE = "pulse_source"

/** Production [SourceChoiceStore] over the shared SharedPreferences file. */
fun sharedPreferencesSourceChoiceStore(context: Context): SourceChoiceStore {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return KeyValueSourceChoiceStore(
        get = { prefs.getString(KEY_PULSE_SOURCE, null) },
        put = { prefs.edit().putString(KEY_PULSE_SOURCE, it).apply() },
    )
}
