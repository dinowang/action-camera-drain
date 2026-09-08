package net.dinowang.actioncameradrain.domain.upload

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkPolicy {
    ANY,
    UNMETERED,
}

@Serializable
enum class TransferState {
    QUEUED,
    PLANNING,
    RUNNING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    FAILED,
    COMPLETED,
}

@Serializable
enum class TransferCommand {
    NONE,
    PAUSE,
    CANCEL,
}

@Serializable
data class TransferRecord(
    val id: String,
    val jobId: Int,
    val executionId: String = "",
    val configId: String,
    val treeUri: String,
    val container: String,
    val rootLabel: String,
    val deviceId: Int? = null,
    val startMode: StartMode,
    val networkPolicy: NetworkPolicy,
    val state: TransferState = TransferState.QUEUED,
    val command: TransferCommand = TransferCommand.NONE,
    val totalFiles: Int = 0,
    val doneFiles: Int = 0,
    val failedFiles: Int = 0,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val currentParallelism: Int = 0,
    val bytesPerSecond: Double = 0.0,
    val message: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    val isActive: Boolean
        get() = state in setOf(
            TransferState.QUEUED,
            TransferState.PLANNING,
            TransferState.RUNNING,
            TransferState.CANCELLING,
        )

    fun withProgress(progress: UploadProgress): TransferRecord = copy(
        state = when (progress.state) {
            UploadProgress.State.IDLE -> state
            UploadProgress.State.RUNNING -> TransferState.RUNNING
            UploadProgress.State.COMPLETED -> TransferState.COMPLETED
            UploadProgress.State.FAILED -> TransferState.FAILED
            UploadProgress.State.CANCELLED -> state
        },
        totalFiles = progress.totalFiles,
        doneFiles = progress.doneFiles,
        failedFiles = progress.failedFiles,
        totalBytes = progress.totalBytes,
        doneBytes = progress.doneBytes,
        currentParallelism = progress.currentParallelism,
        bytesPerSecond = progress.bytesPerSecond,
        updatedAtMillis = System.currentTimeMillis(),
    )

    fun toUploadProgress(): UploadProgress? {
        if (totalFiles == 0 && totalBytes == 0L && state == TransferState.QUEUED) return null
        return UploadProgress(
            totalFiles = totalFiles,
            doneFiles = doneFiles,
            failedFiles = failedFiles,
            totalBytes = totalBytes,
            doneBytes = doneBytes,
            currentParallelism = currentParallelism,
            bytesPerSecond = bytesPerSecond,
            state = when (state) {
                TransferState.QUEUED,
                TransferState.PLANNING,
                TransferState.RUNNING,
                TransferState.CANCELLING,
                -> UploadProgress.State.RUNNING
                TransferState.PAUSED,
                TransferState.CANCELLED,
                -> UploadProgress.State.CANCELLED
                TransferState.FAILED -> UploadProgress.State.FAILED
                TransferState.COMPLETED -> UploadProgress.State.COMPLETED
            },
        )
    }
}
