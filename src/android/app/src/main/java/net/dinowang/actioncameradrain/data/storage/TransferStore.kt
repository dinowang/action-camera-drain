package net.dinowang.actioncameradrain.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import net.dinowang.actioncameradrain.domain.upload.NetworkPolicy
import net.dinowang.actioncameradrain.domain.upload.TransferRecord

private val Context.transferDataStore by preferencesDataStore("transfers")

class TransferStore(context: Context) {

    private val ctx = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    val records: Flow<List<TransferRecord>> = ctx.transferDataStore.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(TRANSFER_PREFIX)) return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            runCatching { json.decodeFromString(TransferRecord.serializer(), raw) }.getOrNull()
        }.sortedByDescending { it.updatedAtMillis }
    }

    val networkPolicy: Flow<NetworkPolicy> = ctx.transferDataStore.data.map { prefs ->
        prefs[NETWORK_POLICY_KEY]
            ?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() }
            ?: NetworkPolicy.UNMETERED
    }

    suspend fun get(id: String): TransferRecord? =
        records.first().firstOrNull { it.id == id }

    suspend fun upsert(record: TransferRecord) {
        ctx.transferDataStore.edit { prefs ->
            prefs[recordKey(record.id)] = json.encodeToString(TransferRecord.serializer(), record)
        }
    }

    suspend fun update(id: String, transform: (TransferRecord) -> TransferRecord): TransferRecord? {
        var updated: TransferRecord? = null
        ctx.transferDataStore.edit { prefs ->
            val key = recordKey(id)
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString(TransferRecord.serializer(), it) }.getOrNull() }
                ?: return@edit
            updated = transform(current).copy(updatedAtMillis = System.currentTimeMillis())
            prefs[key] = json.encodeToString(TransferRecord.serializer(), updated!!)
        }
        return updated
    }

    suspend fun setNetworkPolicy(policy: NetworkPolicy) {
        ctx.transferDataStore.edit { it[NETWORK_POLICY_KEY] = policy.name }
    }

    private fun recordKey(id: String) = stringPreferencesKey("$TRANSFER_PREFIX$id")

    companion object {
        private const val TRANSFER_PREFIX = "transfer:"
        private val NETWORK_POLICY_KEY = stringPreferencesKey("network_policy")
    }
}
