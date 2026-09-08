package net.dinowang.actioncameradrain.domain.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.dinowang.actioncameradrain.domain.filing.IngestPlan
import net.dinowang.actioncameradrain.domain.filing.MediaFile
import net.dinowang.actioncameradrain.domain.filing.PlannedItem
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Drives an [IngestPlan] end-to-end:
 *   - resumes from per-blob checkpoints when [StartMode.RESUME]
 *   - clears checkpoints + deletes remote blobs when [StartMode.RESTART]
 *   - uploads each file as a Block Blob (PutBlock × N + PutBlockList)
 *   - dynamically adjusts file-level parallelism based on throughput
 *   - on per-file failure: discards checkpoint, deletes remote, retries (up to [maxRetries])
 *   - on card-loss (caller invokes [reportCardLost]): cancels in-flight work
 *     and marks affected files FAILED; resume() can pick them up later
 */
class UploadEngine(
    private val client: BlobUploadClient,
    private val container: String,
    private val checkpoints: UploadCheckpointRepository,
    private val scope: CheckpointStore.ScopeKey,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val maxRetries: Int = 2,
    private val concurrency: AdaptiveConcurrency = AdaptiveConcurrency(),
    private val bandwidthLimiter: BandwidthLimiter = BandwidthLimiter.Unlimited,
    private val ownershipId: String? = null,
) {

    private val tracker = ThroughputTracker()

    private val _progress = MutableStateFlow(
        UploadProgress(0, 0, 0, 0L, 0L, concurrency.currentTarget, 0.0, UploadProgress.State.IDLE)
    )
    val progress: StateFlow<UploadProgress> = _progress.asStateFlow()

    private val _fileStatus = MutableStateFlow<Map<String, FileUploadStatus>>(emptyMap())
    val fileStatus: StateFlow<Map<String, FileUploadStatus>> = _fileStatus.asStateFlow()

    @Volatile private var cardLost: Boolean = false

    /** Executes [plan] in the caller's coroutine; cancelling the caller pauses the transfer. */
    suspend fun execute(plan: IngestPlan, mode: StartMode): UploadProgress.State {
        cardLost = false
        return run(plan, mode)
    }

    /** Called by the USB watcher when the card disappears. */
    fun reportCardLost() {
        cardLost = true
    }

    private suspend fun run(plan: IngestPlan, mode: StartMode): UploadProgress.State = coroutineScope {
        val totalBytes = plan.items.sumOf { it.file.sizeBytes }
        val doneBytes = java.util.concurrent.atomic.AtomicLong(0)
        val doneFiles = java.util.concurrent.atomic.AtomicInteger(0)
        val failedFiles = java.util.concurrent.atomic.AtomicInteger(0)

        if (mode == StartMode.RESTART) {
            for (item in plan.items) client.deleteBlob(container, item.blobName)
            checkpoints.deleteAllForScope(scope)
        }

        _fileStatus.value = plan.items.associate { it.blobName to FileUploadStatus.PENDING }
        emit(plan, doneBytes.get(), doneFiles.get(), failedFiles.get(), totalBytes, UploadProgress.State.RUNNING)

        // Throughput sampler — emits an updated progress snapshot every second.
        val sampler = launch {
            while (isActive) {
                delay(PROGRESS_SAMPLE_INTERVAL_MS)
                val bps = tracker.sampleAndReset()
                concurrency.tick(bps)
                emit(plan, doneBytes.get(), doneFiles.get(), failedFiles.get(), totalBytes, UploadProgress.State.RUNNING)
            }
        }

        val workCh = Channel<PlannedItem>(Channel.UNLIMITED)
        for (item in plan.items) workCh.send(item)
        workCh.close()

        try {
            val workers = (1..concurrency.workerCapacity).map {
                async {
                    for (item in workCh) {
                        if (cardLost || !isActive) {
                            _fileStatus.value = _fileStatus.value.toMutableMap().apply {
                                if (this[item.blobName] == FileUploadStatus.UPLOADING) {
                                    this[item.blobName] = FileUploadStatus.FAILED
                                }
                            }
                            continue
                        }
                        concurrency.gate.acquire()
                        try {
                            val ok = uploadOne(item, doneBytes)
                            if (ok) doneFiles.incrementAndGet() else failedFiles.incrementAndGet()
                            emit(
                                plan,
                                doneBytes.get(),
                                doneFiles.get(),
                                failedFiles.get(),
                                totalBytes,
                                UploadProgress.State.RUNNING,
                            )
                        } finally {
                            concurrency.gate.release()
                        }
                    }
                }
            }
            workers.awaitAll()
            val state = when {
                cardLost -> UploadProgress.State.FAILED
                failedFiles.get() > 0 -> UploadProgress.State.FAILED
                else -> UploadProgress.State.COMPLETED
            }
            emit(plan, doneBytes.get(), doneFiles.get(), failedFiles.get(), totalBytes, state)
            state
        } catch (e: CancellationException) {
            emit(
                plan,
                doneBytes.get(),
                doneFiles.get(),
                failedFiles.get(),
                totalBytes,
                UploadProgress.State.CANCELLED,
            )
            throw e
        } finally {
            sampler.cancel()
            workCh.cancel()
        }
    }

    /** Upload a single planned item. Returns true on success, false on permanent failure. */
    private suspend fun uploadOne(
        item: PlannedItem,
        doneBytes: java.util.concurrent.atomic.AtomicLong,
    ): Boolean = withContext(Dispatchers.IO) {
        setStatus(item.blobName, FileUploadStatus.UPLOADING)
        var probeAttempt = 0
        while (true) {
            try {
                val probe = probeExistingBlob(item)
                if (probe.matches) {
                    doneBytes.addAndGet(item.file.sizeBytes)
                    setStatus(item.blobName, FileUploadStatus.SKIPPED)
                    checkpoints.delete(scope, item.blobName)
                    return@withContext true
                }
                break
            } catch (e: Exception) {
                probeAttempt++
                if (probeAttempt > maxRetries) {
                    setStatus(item.blobName, FileUploadStatus.FAILED)
                    return@withContext false
                }
                delay(500L * probeAttempt)
            }
        }

        var attempt = 0
        var creditedBytes = 0L
        while (attempt <= maxRetries) {
            var touchedRemote = false
            try {
                doUpload(
                    item = item,
                    creditBytes = { uploaded ->
                        creditedBytes += uploaded
                        doneBytes.addAndGet(uploaded)
                    },
                    markRemoteTouched = { touchedRemote = true },
                )
                setStatus(item.blobName, FileUploadStatus.DONE)
                return@withContext true
            } catch (e: CardLostException) {
                setStatus(item.blobName, FileUploadStatus.FAILED)
                return@withContext false
            } catch (e: CancellationException) {
                setStatus(item.blobName, FileUploadStatus.PENDING)
                throw e
            } catch (e: PostCommitVerificationException) {
                setStatus(item.blobName, FileUploadStatus.FAILED)
                return@withContext false
            } catch (e: Exception) {
                if (touchedRemote && !deleteOwnedBlob(item.blobName)) {
                    setStatus(item.blobName, FileUploadStatus.FAILED)
                    return@withContext false
                }
                checkpoints.delete(scope, item.blobName)
                doneBytes.addAndGet(-creditedBytes)
                creditedBytes = 0L
                attempt++
                if (attempt > maxRetries) {
                    setStatus(item.blobName, FileUploadStatus.FAILED)
                    return@withContext false
                }
                delay(500L * attempt)
            }
        }
        false
    }

    /**
     * True when the remote blob exists with identical size and identical
     * source mtime metadata — meaning a prior committed upload is byte-equivalent
     * to the current local file and we can safely skip.
     */
    private fun probeExistingBlob(item: PlannedItem): ExistingBlobProbe {
        val props = client.headBlob(container, item.blobName)
            ?: return ExistingBlobProbe(matches = false)
        if (props.contentLength != item.file.sizeBytes) {
            return ExistingBlobProbe(matches = false)
        }
        if (item.file.lastModifiedMillis <= 0L) {
            return ExistingBlobProbe(matches = true)
        }
        val remoteMtime = props.metadata["mtime"]?.toLongOrNull()
            ?: return ExistingBlobProbe(matches = false)
        return ExistingBlobProbe(
            matches = remoteMtime == item.file.lastModifiedMillis,
        )
    }

    private suspend fun doUpload(
        item: PlannedItem,
        creditBytes: (Long) -> Unit,
        markRemoteTouched: () -> Unit,
    ) {
        val file: MediaFile = item.file
        val size = file.sizeBytes
        val mtime = file.lastModifiedMillis
        val existing = checkpoints.load(scope, item.blobName)
        val (skipBytes, alreadyBlocks) = if (existing != null &&
            existing.fileSize == size &&
            existing.fileMtime == mtime &&
            existing.blockSize == blockSize
        ) {
            markRemoteTouched()
            (existing.uploadedBlocks.size.toLong() * blockSize).coerceAtMost(size) to existing.uploadedBlocks
        } else {
            if (existing != null) {
                checkpoints.delete(scope, item.blobName)
            }
            0L to emptyList()
        }

        creditBytes(skipBytes)

        val uploaded = mutableListOf<String>().apply { addAll(alreadyBlocks) }
        val input = file.openInputStream()
        try {
            BufferedInputStream(input).use { stream ->
                if (skipBytes > 0) skipFully(stream, skipBytes)
                val buf = ByteArray(blockSize)
                var blockIndex = uploaded.size
                while (true) {
                    val n = readFully(stream, buf, blockSize)
                    if (n <= 0) break
                    val blockId = java.util.Base64.getEncoder()
                        .encodeToString(formatBlockId(blockIndex).toByteArray(Charsets.UTF_8))
                    bandwidthLimiter.awaitPermit(n)
                    client.putBlock(container, item.blobName, blockId, buf, 0, n)
                    markRemoteTouched()
                    uploaded += blockId
                    blockIndex++
                    creditBytes(n.toLong())
                    tracker.add(n.toLong())
                    if (blockIndex == 1 ||
                        blockIndex % CHECKPOINT_INTERVAL_BLOCKS == 0 ||
                        n < blockSize
                    ) {
                        checkpoints.save(
                            scope,
                            UploadCheckpoint(
                                blobName = item.blobName,
                                fileSize = size,
                                fileMtime = mtime,
                                blockSize = blockSize,
                                uploadedBlocks = uploaded.toList(),
                            ),
                        )
                    }
                    if (n < blockSize) break
                }
            }
        } catch (e: IOException) {
            if (cardLost) throw CardLostException(e)
            throw e
        }

        val contentType = guessContentType(file.name)
        val metadata = buildMap {
            if (mtime > 0L) {
                put("mtime", mtime.toString())
                put("mtime_iso", java.time.Instant.ofEpochMilli(mtime).toString())
            }
            put("size", size.toString())
            put("source_name", sanitizeMetaValue(file.name))
            ownershipId?.let { put("upload_id", sanitizeMetaValue(it)) }
        }
        markRemoteTouched()
        client.putBlockList(container, item.blobName, uploaded, contentType, metadata)
        verifyCommittedBlob(item)
        checkpoints.delete(scope, item.blobName)
    }

    private suspend fun verifyCommittedBlob(item: PlannedItem) {
        var attempt = 0
        while (true) {
            try {
                val props = client.headBlob(container, item.blobName)
                    ?: throw PostCommitVerificationException(
                        "Committed blob is missing: ${item.blobName}",
                    )
                val remoteMtime = props.metadata["mtime"]?.toLongOrNull()
                val mtimeMatches = item.file.lastModifiedMillis <= 0L ||
                    remoteMtime == item.file.lastModifiedMillis
                val ownerMatches = ownershipId == null ||
                    props.metadata["upload_id"] == ownershipId
                if (props.contentLength == item.file.sizeBytes && mtimeMatches && ownerMatches) {
                    return
                }
                throw PostCommitVerificationException(
                    "Committed blob verification failed: ${item.blobName}",
                )
            } catch (e: PostCommitVerificationException) {
                if (attempt >= maxRetries) throw e
            } catch (e: Exception) {
                if (attempt >= maxRetries) {
                    throw PostCommitVerificationException(
                        "Unable to verify committed blob: ${item.blobName}",
                        e,
                    )
                }
            }
            attempt++
            delay(500L * attempt)
        }
    }

    private data class ExistingBlobProbe(
        val matches: Boolean,
    )

    private fun deleteOwnedBlob(blobName: String): Boolean = try {
        val owned = ownershipId != null &&
            client.headBlob(container, blobName)?.metadata?.get("upload_id") == ownershipId
        if (owned) client.deleteBlob(container, blobName)
        true
    } catch (_: Exception) {
        false
    }

    /** Azure metadata values must be ASCII; strip anything that isn't printable. */
    private fun sanitizeMetaValue(s: String): String =
        s.map { c -> if (c.code in 0x20..0x7E) c else '_' }.joinToString("")

    private fun setStatus(blob: String, status: FileUploadStatus) {
        _fileStatus.value = _fileStatus.value.toMutableMap().apply { this[blob] = status }
    }

    private fun emit(
        plan: IngestPlan,
        done: Long, doneFiles: Int, failedFiles: Int, total: Long,
        state: UploadProgress.State,
    ) {
        _progress.value = UploadProgress(
            totalFiles = plan.items.size,
            doneFiles = doneFiles,
            failedFiles = failedFiles,
            totalBytes = total,
            doneBytes = done,
            currentParallelism = concurrency.currentTarget,
            bytesPerSecond = tracker.bytesPerSec,
            state = state,
        )
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 4 * 1024 * 1024 // 4 MiB
        private const val CHECKPOINT_INTERVAL_BLOCKS = 8
        private const val PROGRESS_SAMPLE_INTERVAL_MS = 2_000L

        private fun formatBlockId(index: Int): String = "block-%010d".format(index)

        private fun guessContentType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "insv", "insp" -> "video/mp4"
            "lrv" -> "video/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            "thm" -> "image/jpeg"
            else -> null
        }

        private fun readFully(s: InputStream, buf: ByteArray, n: Int): Int {
            var read = 0
            while (read < n) {
                val r = s.read(buf, read, n - read)
                if (r < 0) return read
                read += r
            }
            return read
        }

        private fun skipFully(s: InputStream, n: Long) {
            var remaining = n
            while (remaining > 0) {
                val skipped = s.skip(remaining)
                if (skipped > 0) { remaining -= skipped; continue }
                if (s.read() < 0) return
                remaining -= 1
            }
        }
    }
}

class CardLostException(cause: Throwable? = null) : RuntimeException(cause)

private class PostCommitVerificationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
