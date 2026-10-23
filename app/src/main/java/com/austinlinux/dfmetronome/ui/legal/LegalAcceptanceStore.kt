package com.austinlinux.dfmetronome.ui.legal

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

// File-level so the app holds exactly one DataStore instance for this file.
private val Context.legalDataStore by preferencesDataStore(
    name = "legal",
    // A corrupt file reads as "nothing accepted" so the notice is simply shown again.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

private val ACCEPTED_NOTICE_KEY = stringPreferencesKey("accepted_notice_sha256")

/** Remembers which exact notice wording the user accepted. */
interface LegalAcceptanceStore {
    suspend fun acceptedHash(): String?
    suspend fun accept(hash: String)
}

class DataStoreLegalAcceptanceStore(private val context: Context) : LegalAcceptanceStore {
    override suspend fun acceptedHash(): String? = context.legalDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .first()[ACCEPTED_NOTICE_KEY]
    override suspend fun accept(hash: String) {
        context.legalDataStore.edit { it[ACCEPTED_NOTICE_KEY] = hash }
    }
}
