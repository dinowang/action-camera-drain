package net.dinowang.actioncameradrain.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Persists the user's most-recently selected Azure Storage container, keyed by
 * upload-config id. Also keeps a per-config MRU history list as a local fallback
 * for the picker dropdown when the SAS doesn't grant `ListContainers`.
 *
 * One container = one (potentially multi-day) trip.
 */
class ContainerSelectionRepository(context: Context) {

    private val ctx = context.applicationContext
    private val Context.dataStore by preferencesDataStore(DATASTORE_NAME)

    suspend fun getLast(configId: String): String? =
        ctx.dataStore.data.first()[lastKey(configId)]

    suspend fun setLast(configId: String, container: String) {
        if (container.isBlank()) return
        ctx.dataStore.edit { prefs ->
            prefs[lastKey(configId)] = container
            val history = readHistory(prefs[historyKey(configId)])
            val updated = (listOf(container) + history.filter { it != container })
                .take(HISTORY_LIMIT)
            prefs[historyKey(configId)] = updated.joinToString(SEP)
        }
    }

    suspend fun history(configId: String): List<String> {
        val raw = ctx.dataStore.data.first()[historyKey(configId)]
        return readHistory(raw)
    }

    private fun readHistory(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEP).filter { it.isNotBlank() }
    }

    private fun lastKey(configId: String) = stringPreferencesKey("last:$configId")
    private fun historyKey(configId: String) = stringPreferencesKey("history:$configId")

    companion object {
        private const val DATASTORE_NAME = "container_selection"
        private const val HISTORY_LIMIT = 20
        private const val SEP = "\u0001"
    }
}
