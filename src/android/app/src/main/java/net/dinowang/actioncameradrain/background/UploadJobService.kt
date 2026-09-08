package net.dinowang.actioncameradrain.background

import android.app.job.JobParameters
import android.app.job.JobService
import android.net.ConnectivityManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dinowang.actioncameradrain.data.config.ConfigRepository
import net.dinowang.actioncameradrain.data.config.UploadConfig
import net.dinowang.actioncameradrain.data.storage.AzureBlobClient
import net.dinowang.actioncameradrain.data.storage.TransferStore
import net.dinowang.actioncameradrain.domain.filing.IngestPlanner
import net.dinowang.actioncameradrain.domain.filing.SafMediaSource
import net.dinowang.actioncameradrain.domain.upload.CheckpointStore
import net.dinowang.actioncameradrain.domain.upload.AdaptiveConcurrency
import net.dinowang.actioncameradrain.domain.upload.BandwidthLimiter
import net.dinowang.actioncameradrain.domain.upload.NetworkPolicy
import net.dinowang.actioncameradrain.domain.upload.TransferCommand
import net.dinowang.actioncameradrain.domain.upload.TransferRecord
import net.dinowang.actioncameradrain.domain.upload.TransferState
import net.dinowang.actioncameradrain.domain.upload.UploadEngine
import net.dinowang.actioncameradrain.domain.upload.UploadProgress
import net.dinowang.actioncameradrain.domain.usb.TreeAccessChecker
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.coroutines.coroutineContext

class UploadJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()
    private val runningHttp = ConcurrentHashMap<Job, OkHttpClient>()
    private lateinit var store: TransferStore
    private lateinit var notifications: UploadNotificationFactory

    override fun onCreate() {
        super.onCreate()
        store = TransferStore(this)
        notifications = UploadNotificationFactory(this)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val transferId = params.extras.getString(EXTRA_TRANSFER_ID) ?: return false
        val placeholder = TransferRecord(
            id = transferId,
            jobId = params.jobId,
            executionId = "pending",
            configId = "",
            treeUri = "",
            container = "",
            rootLabel = "memory card",
            startMode = net.dinowang.actioncameradrain.domain.upload.StartMode.RESUME,
            networkPolicy = net.dinowang.actioncameradrain.domain.upload.NetworkPolicy.UNMETERED,
        )
        setJobNotification(params, placeholder)

        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                runTransfer(params, transferId)
                store.get(transferId)?.let { setJobNotification(params, it) }
                jobFinished(params, false)
            } catch (_: CancellationException) {
                // onStopJob owns rescheduling when Android stops the job.
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    val current = store.get(transferId)
                    if (current?.command == TransferCommand.CANCEL ||
                        current?.state == TransferState.CANCELLING
                    ) {
                        store.update(transferId) {
                            it.copy(
                                state = TransferState.CANCELLING,
                                command = TransferCommand.CANCEL,
                                message = "Remote cleanup failed; waiting to retry: " +
                                    (error.message ?: error.javaClass.simpleName),
                            )
                        }
                        jobFinished(params, true)
                    } else {
                        fail(transferId, error.message ?: error.javaClass.simpleName)
                        store.get(transferId)?.let { setJobNotification(params, it) }
                        jobFinished(params, false)
                    }
                }
            } finally {
                coroutineContext[Job]?.let { owner ->
                    runningJobs.remove(params.jobId, owner)
                    runningHttp.remove(owner)?.dispatcher?.cancelAll()
                }
            }
        }
        runningJobs[params.jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val job = runningJobs.remove(params.jobId)
        if (job != null) {
            runningHttp.remove(job)?.dispatcher?.cancelAll()
            job.cancel()
        }
        val transferId = params.extras.getString(EXTRA_TRANSFER_ID)
        if (transferId != null) {
            serviceScope.launch {
                store.update(transferId) { record ->
                    if (record.command == TransferCommand.NONE && record.isActive) {
                        record.copy(
                            state = TransferState.QUEUED,
                            message = "Upload interrupted by Android; waiting to resume.",
                        )
                    } else {
                        record
                    }
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runTransfer(params: JobParameters, transferId: String) {
        var record = store.update(transferId) { latest ->
            val executionId = latest.executionId.ifBlank { UUID.randomUUID().toString() }
            if (latest.state == TransferState.PAUSED ||
                latest.command == TransferCommand.PAUSE
            ) {
                latest.copy(
                    executionId = executionId,
                    state = TransferState.PAUSED,
                    command = TransferCommand.NONE,
                    message = "Upload paused.",
                )
            } else if (latest.command == TransferCommand.CANCEL) {
                latest.copy(
                    executionId = executionId,
                    state = TransferState.CANCELLING,
                    message = "Cleaning remote partial data.",
                )
            } else {
                latest.copy(
                    executionId = executionId,
                    state = TransferState.PLANNING,
                    message = null,
                )
            }
        } ?: return
        if (record.state == TransferState.PAUSED) {
            return
        }

        val configRepo = ConfigRepository(this)
        configRepo.load()
        record = store.get(transferId) ?: return
        val config = configRepo.current.first { it != null } as? UploadConfig.AzureBlob
        if (config == null) {
            if (record.command == TransferCommand.CANCEL) {
                error("Azure Blob configuration is unavailable for cancellation cleanup.")
            }
            return fail(transferId, "Azure Blob configuration is unavailable.")
        }
        if (config.id != record.configId) {
            if (record.command == TransferCommand.CANCEL) {
                error("The selected upload configuration no longer exists.")
            }
            return fail(transferId, "The selected upload configuration no longer exists.")
        }

        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        val ownerJob = coroutineContext[Job] ?: error("Upload job has no coroutine owner.")
        runningHttp[ownerJob] = http
        val checkpointStore = CheckpointStore(this)
        val client = AzureBlobClient(config, http)
        val scopeKey = CheckpointStore.ScopeKey(
            config.id,
            "${record.treeUri}@${record.container}",
        )
        record = store.get(transferId) ?: return
        if (record.command == TransferCommand.CANCEL) {
            setJobNotification(params, record)
            cancelAndClean(
                transferId,
                record.container,
                client,
                checkpointStore,
                scopeKey,
            )
            return
        }

        val treeUri = Uri.parse(record.treeUri)
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null) {
            if (store.get(transferId)?.command == TransferCommand.CANCEL) {
                cancelAndClean(transferId, record.container, client, checkpointStore, scopeKey)
            } else {
                fail(transferId, "The memory card permission is no longer valid.")
            }
            return
        }
        val access = TreeAccessChecker(this)
        if (!access.isAccessible(treeUri)) {
            if (store.get(transferId)?.command == TransferCommand.CANCEL) {
                cancelAndClean(transferId, record.container, client, checkpointStore, scopeKey)
            } else {
                fail(transferId, "The memory card is not connected.")
            }
            return
        }

        val planResult = runCatching {
            IngestPlanner().plan(SafMediaSource(contentResolver, root))
        }
        if (planResult.isFailure) {
            if (store.get(transferId)?.command == TransferCommand.CANCEL) {
                cancelAndClean(transferId, record.container, client, checkpointStore, scopeKey)
            } else {
                fail(
                    transferId,
                    planResult.exceptionOrNull()?.message ?: "Unable to scan the memory card.",
                )
            }
            return
        }
        val plan = planResult.getOrThrow()
        record = store.update(transferId) { latest ->
            when (latest.command) {
                TransferCommand.CANCEL -> latest.copy(
                    state = TransferState.CANCELLING,
                    message = "Cleaning remote partial data.",
                )
                TransferCommand.PAUSE -> latest.copy(
                    state = TransferState.PAUSED,
                    command = TransferCommand.NONE,
                    message = "Upload paused.",
                )
                TransferCommand.NONE -> latest.copy(
                    state = TransferState.RUNNING,
                    totalFiles = plan.fileCount,
                    totalBytes = plan.totalBytes,
                    message = null,
                )
            }
        } ?: return
        if (record.command == TransferCommand.CANCEL) {
            cancelAndClean(transferId, record.container, client, checkpointStore, scopeKey)
            return
        }
        if (record.state == TransferState.PAUSED) return
        setJobNotification(params, record)

        val executionMode = if (record.startMode ==
            net.dinowang.actioncameradrain.domain.upload.StartMode.RESTART
        ) {
            for (item in plan.items) client.deleteBlob(record.container, item.blobName)
            checkpointStore.deleteAllForScope(scopeKey)
            record = store.update(transferId) {
                it.copy(startMode = net.dinowang.actioncameradrain.domain.upload.StartMode.RESUME)
            } ?: return
            net.dinowang.actioncameradrain.domain.upload.StartMode.RESUME
        } else {
            record.startMode
        }
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val meteredAtStart = record.networkPolicy == NetworkPolicy.ANY &&
            connectivity.isActiveNetworkMetered
        val bandwidthLimiter = BandwidthLimiter {
            if (record.networkPolicy == NetworkPolicy.ANY &&
                connectivity.isActiveNetworkMetered
            ) {
                METERED_BYTES_PER_SECOND
            } else {
                null
            }
        }
        val engine = UploadEngine(
            client = client,
            container = record.container,
            checkpoints = checkpointStore,
            scope = scopeKey,
            concurrency = if (meteredAtStart) {
                AdaptiveConcurrency(initial = 1, min = 1, max = METERED_MAX_WORKERS)
            } else {
                AdaptiveConcurrency()
            },
            bandwidthLimiter = bandwidthLimiter,
            ownershipId = record.executionId,
        )
        var cardLost = false

        coroutineScope {
            val execution = async {
                engine.execute(plan, executionMode)
            }
            val progressCollector = launch {
                engine.progress.collectLatest { progress ->
                    val updated = store.update(transferId) { it.withProgress(progress) }
                    if (updated != null) setJobNotification(params, updated)
                }
            }
            val commandWatcher = launch {
                val command = store.records
                    .map { records -> records.firstOrNull { it.id == transferId }?.command }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .first { it != TransferCommand.NONE }
                if (command == TransferCommand.PAUSE || command == TransferCommand.CANCEL) {
                    runningHttp[ownerJob]?.dispatcher?.cancelAll()
                    execution.cancel()
                }
            }
            val cardWatcher = launch {
                while (true) {
                    delay(CARD_POLL_INTERVAL_MS)
                    if (!access.isAccessible(treeUri)) {
                        cardLost = true
                        engine.reportCardLost()
                        runningHttp[ownerJob]?.dispatcher?.cancelAll()
                        execution.cancel()
                        return@launch
                    }
                }
            }

            try {
                val finalState = execution.await()
                val finalProgress = engine.progress.value
                progressCollector.cancelAndJoin()
                commandWatcher.cancelAndJoin()
                cardWatcher.cancelAndJoin()
                var cancellationRequested = false
                val updated = store.update(transferId) { latest ->
                    val latestWithProgress = latest.withProgress(finalProgress)
                    if (latest.command == TransferCommand.CANCEL) {
                        cancellationRequested = true
                        latestWithProgress.copy(
                            state = TransferState.CANCELLING,
                            message = "Cleaning remote partial data.",
                        )
                    } else {
                        latestWithProgress.copy(
                            state = if (finalState == UploadProgress.State.COMPLETED) {
                                TransferState.COMPLETED
                            } else {
                                TransferState.FAILED
                            },
                            command = TransferCommand.NONE,
                            message = if (finalState == UploadProgress.State.COMPLETED) {
                                "Upload completed and verified."
                            } else {
                                "One or more files failed."
                            },
                        )
                    }
                }
                if (cancellationRequested) {
                    cancelAndClean(
                        transferId,
                        record.container,
                        client,
                        checkpointStore,
                        scopeKey,
                    )
                } else if (updated != null) {
                    setJobNotification(params, updated)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    runningHttp[ownerJob]?.dispatcher?.cancelAll()
                    execution.join()
                    val finalProgress = engine.progress.value
                    progressCollector.cancelAndJoin()
                    commandWatcher.cancelAndJoin()
                    cardWatcher.cancelAndJoin()
                    val command = store.get(transferId)?.command
                    when {
                        command == TransferCommand.CANCEL ->
                            cancelAndClean(
                                transferId,
                                record.container,
                                client, checkpointStore, scopeKey,
                            )
                        command == TransferCommand.PAUSE ->
                            store.update(transferId) {
                                it.withProgress(finalProgress).copy(
                                    state = TransferState.PAUSED,
                                    command = TransferCommand.NONE,
                                    message = "Upload paused.",
                                )
                            }
                        cardLost ->
                            store.update(transferId) {
                                it.withProgress(finalProgress).copy(
                                    state = TransferState.FAILED,
                                    command = TransferCommand.NONE,
                                    message = "The memory card was disconnected. Reconnect it to resume.",
                                )
                            }
                        else -> throw e
                    }
                }
            } finally {
                progressCollector.cancel()
                commandWatcher.cancel()
                cardWatcher.cancel()
                runningHttp.remove(ownerJob, http)
            }
        }
    }

    private suspend fun cancelAndClean(
        transferId: String,
        container: String,
        client: AzureBlobClient,
        checkpoints: CheckpointStore,
        scope: CheckpointStore.ScopeKey,
    ) {
        val partial = checkpoints.listForScope(scope)
        val executionId = store.get(transferId)?.executionId
        for (checkpoint in partial) {
            val props = client.headBlob(container, checkpoint.blobName)
            val owned = props?.metadata?.get("upload_id") == executionId
            val complete = props?.contentLength == checkpoint.fileSize &&
                (checkpoint.fileMtime <= 0L ||
                    props.metadata["mtime"]?.toLongOrNull() == checkpoint.fileMtime)
            if (owned && !complete) {
                client.deleteBlob(container, checkpoint.blobName)
            }
        }
        checkpoints.deleteAllForScope(scope)
        store.update(transferId) {
            it.copy(
                state = TransferState.CANCELLED,
                command = TransferCommand.NONE,
                doneFiles = 0,
                failedFiles = 0,
                doneBytes = 0,
                bytesPerSecond = 0.0,
                message = "Upload cancelled. Completed files were retained; partial state was cleared.",
            )
        }
    }

    private suspend fun fail(transferId: String, message: String) {
        store.update(transferId) {
            it.copy(
                state = TransferState.FAILED,
                command = TransferCommand.NONE,
                message = message,
            )
        }
    }

    private fun setJobNotification(params: JobParameters, record: TransferRecord) {
        val terminal = record.state in setOf(
            TransferState.PAUSED,
            TransferState.CANCELLED,
            TransferState.FAILED,
            TransferState.COMPLETED,
        )
        setNotification(
            params,
            params.jobId,
            notifications.build(record),
            if (terminal) {
                JOB_END_NOTIFICATION_POLICY_DETACH
            } else {
                JOB_END_NOTIFICATION_POLICY_REMOVE
            },
        )
    }

    companion object {
        const val EXTRA_TRANSFER_ID = "transfer_id"
        private const val CARD_POLL_INTERVAL_MS = 2_000L
        private const val METERED_MAX_WORKERS = 2
        private const val METERED_BYTES_PER_SECOND = 1_250_000L // 10 Mbps
    }
}
