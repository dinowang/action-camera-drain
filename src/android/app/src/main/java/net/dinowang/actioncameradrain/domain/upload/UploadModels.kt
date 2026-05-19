package net.dinowang.actioncameradrain.domain.upload

/** Per-file upload status as seen by the UI. */
enum class FileUploadStatus { PENDING, UPLOADING, DONE, FAILED, SKIPPED }

/** Per-blob persisted checkpoint state. */
data class UploadCheckpoint(
    val blobName: String,
    val fileSize: Long,
    val fileMtime: Long,
    val blockSize: Int,
    /** block ids (Azure-base64 encoded) for blocks already uploaded, in order. */
    val uploadedBlocks: List<String>,
)

/** Snapshot of overall progress for the UI. */
data class UploadProgress(
    val totalFiles: Int,
    val doneFiles: Int,
    val failedFiles: Int,
    val totalBytes: Long,
    val doneBytes: Long,
    val currentParallelism: Int,
    val bytesPerSecond: Double,
    val state: State,
) {
    enum class State { IDLE, RUNNING, COMPLETED, FAILED, CANCELLED }
}

enum class StartMode {
    /** Resume from existing checkpoints; skip already-uploaded blocks. */
    RESUME,

    /** Discard checkpoints and remote blobs for this plan; re-upload from scratch. */
    RESTART,
}
