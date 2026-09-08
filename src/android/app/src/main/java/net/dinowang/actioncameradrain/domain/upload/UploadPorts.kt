package net.dinowang.actioncameradrain.domain.upload

import net.dinowang.actioncameradrain.data.storage.BlobProperties

interface BlobUploadClient {
    fun putBlock(
        container: String,
        blobName: String,
        blockId: String,
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    )

    fun putBlockList(
        container: String,
        blobName: String,
        blockIds: List<String>,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
    )

    fun headBlob(container: String, blobName: String): BlobProperties?
    fun deleteBlob(container: String, blobName: String)
}

interface UploadCheckpointRepository {
    suspend fun load(scope: CheckpointStore.ScopeKey, blobName: String): UploadCheckpoint?
    suspend fun save(scope: CheckpointStore.ScopeKey, cp: UploadCheckpoint)
    suspend fun delete(scope: CheckpointStore.ScopeKey, blobName: String)
    suspend fun deleteAllForScope(scope: CheckpointStore.ScopeKey)
    suspend fun listForScope(scope: CheckpointStore.ScopeKey): List<UploadCheckpoint>
}
