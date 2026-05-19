package net.dinowang.actioncameradrain.domain.upload

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists per-blob upload checkpoints using Jetpack DataStore.
 *
 * Key format: "checkpoint:<configId>:<sourceId>:<blobName>" → JSON of [UploadCheckpoint].
 */
class CheckpointStore(context: Context) {

    private val ctx = context.applicationContext
    private val Context.dataStore by preferencesDataStore(DATASTORE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(scope: ScopeKey, blobName: String): UploadCheckpoint? {
        val prefs = ctx.dataStore.data.first()
        val raw = prefs[keyFor(scope, blobName)] ?: return null
        return runCatching { json.decodeFromString(SerializableCheckpoint.serializer(), raw).toDomain() }.getOrNull()
    }

    suspend fun save(scope: ScopeKey, cp: UploadCheckpoint) {
        ctx.dataStore.edit { it[keyFor(scope, cp.blobName)] = json.encodeToString(SerializableCheckpoint.serializer(), SerializableCheckpoint.fromDomain(cp)) }
    }

    suspend fun delete(scope: ScopeKey, blobName: String) {
        ctx.dataStore.edit { it.remove(keyFor(scope, blobName)) }
    }

    suspend fun deleteAllForScope(scope: ScopeKey) {
        val prefix = "checkpoint:${scope.configId}:${scope.sourceId}:"
        ctx.dataStore.edit { prefs ->
            val toRemove = prefs.asMap().keys.filter { it.name.startsWith(prefix) }
            for (k in toRemove) prefs.remove(k as Preferences.Key<*>)
        }
    }

    suspend fun listForScope(scope: ScopeKey): List<UploadCheckpoint> {
        val prefix = "checkpoint:${scope.configId}:${scope.sourceId}:"
        val prefs = ctx.dataStore.data.first()
        return prefs.asMap().entries.mapNotNull { (k, v) ->
            if (!k.name.startsWith(prefix)) return@mapNotNull null
            runCatching { json.decodeFromString(SerializableCheckpoint.serializer(), v as String).toDomain() }.getOrNull()
        }
    }

    private fun keyFor(scope: ScopeKey, blobName: String) =
        stringPreferencesKey("checkpoint:${scope.configId}:${scope.sourceId}:$blobName")

    data class ScopeKey(val configId: String, val sourceId: String)

    companion object {
        private const val DATASTORE_NAME = "upload_checkpoints"
    }
}

@Serializable
private data class SerializableCheckpoint(
    val blobName: String,
    val fileSize: Long,
    val fileMtime: Long,
    val blockSize: Int,
    val uploadedBlocks: List<String>,
) {
    fun toDomain() = UploadCheckpoint(blobName, fileSize, fileMtime, blockSize, uploadedBlocks)

    companion object {
        fun fromDomain(c: UploadCheckpoint) = SerializableCheckpoint(
            c.blobName, c.fileSize, c.fileMtime, c.blockSize, c.uploadedBlocks,
        )
    }
}
