package net.dinowang.actioncameradrain.background

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle
import kotlinx.coroutines.flow.first
import net.dinowang.actioncameradrain.data.storage.TransferStore
import net.dinowang.actioncameradrain.domain.upload.NetworkPolicy
import net.dinowang.actioncameradrain.domain.upload.StartMode
import net.dinowang.actioncameradrain.domain.upload.TransferCommand
import net.dinowang.actioncameradrain.domain.upload.TransferRecord
import net.dinowang.actioncameradrain.domain.upload.TransferState
import java.security.MessageDigest
import java.util.UUID

class UploadJobScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val jobs = appContext.getSystemService(JobScheduler::class.java)
    private val store = TransferStore(appContext)

    suspend fun schedule(
        configId: String,
        treeUri: String,
        container: String,
        rootLabel: String,
        deviceId: Int?,
        mode: StartMode,
        networkPolicy: NetworkPolicy,
        totalFiles: Int,
        totalBytes: Long,
    ): Result<TransferRecord> {
        val transferId = transferId(configId, treeUri, container)
        val existing = store.get(transferId)
        if (existing?.isActive == true) {
            return Result.failure(IllegalStateException("This card already has an active upload."))
        }
        val resumeSameExecution = mode == StartMode.RESUME &&
            existing?.state in setOf(
                TransferState.PAUSED,
                TransferState.FAILED,
                TransferState.QUEUED,
                TransferState.CANCELLING,
            )

        val record = TransferRecord(
            id = transferId,
            jobId = jobId(transferId),
            executionId = if (resumeSameExecution) {
                existing?.executionId ?: UUID.randomUUID().toString()
            } else {
                UUID.randomUUID().toString()
            },
            configId = configId,
            treeUri = treeUri,
            container = container,
            rootLabel = rootLabel,
            deviceId = deviceId,
            startMode = mode,
            networkPolicy = networkPolicy,
            totalFiles = totalFiles,
            totalBytes = totalBytes,
        )
        store.upsert(record)

        return runCatching {
            check(jobs.schedule(buildJobInfo(record, totalBytes)) == JobScheduler.RESULT_SUCCESS) {
                "Android rejected the upload job."
            }
            record
        }.onFailure { error ->
            store.update(transferId) {
                it.copy(state = TransferState.FAILED, message = error.message)
            }
        }
    }

    suspend fun reconcileOrphanedTransfers() {
        for (record in store.records.first()) {
            if (!record.isActive || jobs.getPendingJob(record.jobId) != null) continue
            store.update(record.id) {
                if (it.state == TransferState.CANCELLING) {
                    it.copy(
                        state = TransferState.FAILED,
                        command = TransferCommand.NONE,
                        message = "Cancellation was interrupted. Tap Cancel to retry cleanup.",
                    )
                } else {
                    it.copy(
                        state = TransferState.PAUSED,
                        command = TransferCommand.NONE,
                        message = "Android stopped the previous upload. Tap Resume to continue.",
                    )
                }
            }
        }
    }

    suspend fun pause(transferId: String) {
        var cancelPendingJob = false
        val updated = store.update(transferId) { current ->
            when (current.state) {
                TransferState.RUNNING,
                TransferState.PLANNING,
                -> current.copy(command = TransferCommand.PAUSE)
                TransferState.QUEUED -> {
                    cancelPendingJob = true
                    current.copy(
                        state = TransferState.PAUSED,
                        command = TransferCommand.NONE,
                        message = "Upload paused.",
                    )
                }
                TransferState.CANCELLING,
                TransferState.PAUSED,
                TransferState.CANCELLED,
                TransferState.FAILED,
                TransferState.COMPLETED,
                -> current
            }
        } ?: return
        if (cancelPendingJob) {
            jobs.cancel(updated.jobId)
        }
    }

    suspend fun cancel(transferId: String) {
        var scheduleCleanup = false
        val updated = store.update(transferId) { current ->
            when (current.state) {
                TransferState.QUEUED,
                TransferState.PLANNING,
                TransferState.RUNNING,
                -> current.copy(
                    state = TransferState.CANCELLING,
                    command = TransferCommand.CANCEL,
                    message = "Cancelling upload and cleaning remote data.",
                )
                TransferState.CANCELLING -> current
                TransferState.PAUSED,
                TransferState.FAILED,
                -> {
                    scheduleCleanup = true
                    current.copy(
                        state = TransferState.CANCELLING,
                        command = TransferCommand.CANCEL,
                        message = "Waiting to clean remote partial data.",
                    )
                }
                TransferState.CANCELLED,
                TransferState.COMPLETED,
                -> current
            }
        } ?: return
        if (updated.state == TransferState.CANCELLING &&
            (scheduleCleanup || jobs.getPendingJob(updated.jobId) == null)
        ) {
            val scheduled = runCatching {
                jobs.schedule(buildJobInfo(updated, updated.totalBytes)) ==
                    JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
            if (!scheduled) {
                store.update(transferId) {
                    it.copy(
                        state = TransferState.FAILED,
                        command = TransferCommand.NONE,
                        message = "Cancellation cleanup could not be scheduled. Tap Cancel to retry.",
                    )
                }
            }
        }
    }

    private fun buildJobInfo(record: TransferRecord, totalBytes: Long): JobInfo {
        val requiredNetwork = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply {
                if (record.networkPolicy == NetworkPolicy.UNMETERED) {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }
            .build()
        val extras = PersistableBundle().apply {
            putString(UploadJobService.EXTRA_TRANSFER_ID, record.id)
        }
        return JobInfo.Builder(
            record.jobId,
            ComponentName(appContext, UploadJobService::class.java),
        )
            .setUserInitiated(true)
            .setRequiredNetwork(requiredNetwork)
            .setEstimatedNetworkBytes(0L, totalBytes)
            .setExtras(extras)
            .build()
    }

    companion object {
        fun transferId(configId: String, treeUri: String, container: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$configId\u0000$treeUri\u0000$container".toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        fun jobId(transferId: String): Int {
            val raw = transferId.take(8).toLong(16).toInt() and Int.MAX_VALUE
            return raw.coerceAtLeast(1)
        }
    }
}
