package net.dinowang.actioncameradrain.domain.upload

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.uploadCheckpointDataStore by preferencesDataStore("upload_checkpoints")

/**
 * Persists per-blob upload checkpoints using Jetpack DataStore.
 *
 * Key format: "checkpoint:<configId>:<sourceId>:<blobName>" → JSON of [UploadCheckpoint].
 */
class CheckpointStore(context: Context) : UploadCheckpointRepository {

    private val ctx = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(scope: ScopeKey, blobName: String): UploadCheckpoint? {
        val prefs = ctx.uploadCheckpointDataStore.data.first()
        val raw = prefs[keyFor(scope, blobName)] ?: return null
        return runCatching { json.decodeFromString(SerializableCheckpoint.serializer(), raw).toDomain() }.getOrNull()
    }

    override suspend fun save(scope: ScopeKey, cp: UploadCheckpoint) {
        ctx.uploadCheckpointDataStore.edit { it[keyFor(scope, cp.blobName)] = json.encodeToString(SerializableCheckpoint.serializer(), SerializableCheckpoint.fromDomain(cp)) }
    }

    override suspend fun delete(scope: ScopeKey, blobName: String) {
        ctx.uploadCheckpointDataStore.edit { it.remove(keyFor(scope, blobName)) }
    }

    override suspend fun deleteAllForScope(scope: ScopeKey) {
        val prefix = "checkpoint:${scope.configId}:${scope.sourceId}:"
        ctx.uploadCheckpointDataStore.edit { prefs ->
            val toRemove = prefs.asMap().keys.filter { it.name.startsWith(prefix) }
            for (k in toRemove) prefs.remove(k as Preferences.Key<*>)
        }
    }

    override suspend fun listForScope(scope: ScopeKey): List<UploadCheckpoint> {
        val prefix = "checkpoint:${scope.configId}:${scope.sourceId}:"
        val prefs = ctx.uploadCheckpointDataStore.data.first()
        return prefs.asMap().entries.mapNotNull { (k, v) ->
            if (!k.name.startsWith(prefix)) return@mapNotNull null
            runCatching { json.decodeFromString(SerializableCheckpoint.serializer(), v as String).toDomain() }.getOrNull()
        }
    }

    private fun keyFor(scope: ScopeKey, blobName: String) =
        stringPreferencesKey("checkpoint:${scope.configId}:${scope.sourceId}:$blobName")

    data class ScopeKey(val configId: String, val sourceId: String)

    companion object {
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
    fun toDomain() = UploadCheckpoint(
        blobName,
        fileSize,
        fileMtime,
        blockSize,
        uploadedBlocks,
    )

    companion object {
        fun fromDomain(c: UploadCheckpoint) = SerializableCheckpoint(
            c.blobName,
            c.fileSize,
            c.fileMtime,
            c.blockSize,
            c.uploadedBlocks,
        )
    }
}
