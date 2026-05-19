package net.dinowang.actioncameradrain.domain.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dinowang.actioncameradrain.data.storage.AzureBlobClient
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
    private val client: AzureBlobClient,
    private val container: String,
    private val checkpoints: CheckpointStore,
    private val scope: CheckpointStore.ScopeKey,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val maxRetries: Int = 2,
    private val concurrency: AdaptiveConcurrency = AdaptiveConcurrency(),
) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = ThroughputTracker()

    private val _progress = MutableStateFlow(
        UploadProgress(0, 0, 0, 0L, 0L, concurrency.currentTarget, 0.0, UploadProgress.State.IDLE)
    )
    val progress: StateFlow<UploadProgress> = _progress.asStateFlow()

    private val _fileStatus = MutableStateFlow<Map<String, FileUploadStatus>>(emptyMap())
    val fileStatus: StateFlow<Map<String, FileUploadStatus>> = _fileStatus.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var cardLost: Boolean = false

    /** Start (or resume) uploading [plan]. Returns immediately; observe [progress]. */
    fun start(plan: IngestPlan, mode: StartMode) {
        if (job?.isActive == true) return
        cardLost = false
        job = coroutineScope.launch { run(plan, mode) }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Called by the USB watcher when the card disappears. */
    fun reportCardLost() {
        cardLost = true
        cancel()
    }

    private suspend fun run(plan: IngestPlan, mode: StartMode) {
        val totalBytes = plan.items.sumOf { it.file.sizeBytes }
        val doneBytes = java.util.concurrent.atomic.AtomicLong(0)
        val doneFiles = java.util.concurrent.atomic.AtomicInteger(0)
        val failedFiles = java.util.concurrent.atomic.AtomicInteger(0)

        if (mode == StartMode.RESTART) {
            val existing = checkpoints.listForScope(scope)
            for (cp in existing) runCatching { client.deleteBlob(container, cp.blobName) }
            checkpoints.deleteAllForScope(scope)
        }

        _fileStatus.value = plan.items.associate { it.blobName to FileUploadStatus.PENDING }
        emit(plan, doneBytes.get(), doneFiles.get(), failedFiles.get(), totalBytes, UploadProgress.State.RUNNING)

        // Throughput sampler — emits an updated progress snapshot every second.
        val sampler = coroutineScope.launch {
            while (isActive) {
                delay(1_000)
                val bps = tracker.sampleAndReset()
                concurrency.tick(bps)
                emit(plan, doneBytes.get(), doneFiles.get(), failedFiles.get(), totalBytes, UploadProgress.State.RUNNING)
            }
        }

        val workCh = Channel<PlannedItem>(Channel.UNLIMITED)
        for (item in plan.items) workCh.send(item)
        workCh.close()

        try {
            kotlinx.coroutines.coroutineScope {
                val workers = (1..concurrency.gate.currentTarget.coerceAtLeast(1) * 2)
                    .map {
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
                                    val before = doneBytes.get()
                                    val ok = uploadOne(item, doneBytes)
                                    if (ok) doneFiles.incrementAndGet() else failedFiles.incrementAndGet()
                                    emit(plan, doneBytes.get(), doneFiles.get(), failedFiles.get(), totalBytes, UploadProgress.State.RUNNING)
                                    tracker.add(doneBytes.get() - before)
                                } finally {
                                    concurrency.gate.release()
                                }
                            }
                        }
                    }
                workers.awaitAll()
            }
            val state = when {
                cardLost -> UploadProgress.State.FAILED
                failedFiles.get() > 0 -> UploadProgress.State.FAILED
                else -> UploadProgress.State.COMPLETED
            }
            emit(plan, doneBytes.get(), doneFiles.get(), failedFiles.get(), totalBytes, state)
        } catch (e: CancellationException) {
            emit(plan, doneBytes.get(), doneFiles.get(), failedFiles.get(), totalBytes, UploadProgress.State.CANCELLED)
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
        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                doUpload(item, doneBytes)
                setStatus(item.blobName, FileUploadStatus.DONE)
                return@withContext true
            } catch (e: CardLostException) {
                setStatus(item.blobName, FileUploadStatus.FAILED)
                return@withContext false
            } catch (e: CancellationException) {
                setStatus(item.blobName, FileUploadStatus.FAILED)
                throw e
            } catch (e: Throwable) {
                // Per spec: a failed in-flight file must be re-uploaded from scratch,
                // and any remote remnants must be erased first.
                runCatching { client.deleteBlob(container, item.blobName) }
                checkpoints.delete(scope, item.blobName)
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

    private suspend fun doUpload(
        item: PlannedItem,
        doneBytes: java.util.concurrent.atomic.AtomicLong,
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
            (existing.uploadedBlocks.size.toLong() * blockSize).coerceAtMost(size) to existing.uploadedBlocks
        } else {
            if (existing != null) {
                // checkpoint mismatch → start over remotely as well
                runCatching { client.deleteBlob(container, item.blobName) }
                checkpoints.delete(scope, item.blobName)
            }
            0L to emptyList()
        }

        // Pre-add bytes already on remote to the progress counter once.
        doneBytes.addAndGet(skipBytes)

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
                    val blockId = AzureBlobClient.encodeBlockId(formatBlockId(blockIndex))
                    client.putBlock(container, item.blobName, blockId, buf, 0, n)
                    uploaded += blockId
                    blockIndex++
                    doneBytes.addAndGet(n.toLong())
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
                    if (n < blockSize) break
                }
            }
        } catch (e: IOException) {
            // Could be card eject; surface as CardLost.
            if (cardLost) throw CardLostException(e)
            throw e
        }

        // Commit the block list.
        val contentType = guessContentType(file.name)
        val metadata = buildMap {
            if (mtime > 0L) {
                // Azure rejects header values containing colons in some metadata
                // tools; use epoch millis (always safe) + ISO-8601 for humans.
                put("mtime", mtime.toString())
                put("mtime_iso", java.time.Instant.ofEpochMilli(mtime).toString())
            }
            put("size", size.toString())
            put("source_name", sanitizeMetaValue(file.name))
        }
        client.putBlockList(container, item.blobName, uploaded, contentType, metadata)
        // After successful commit the checkpoint is no longer needed.
        checkpoints.delete(scope, item.blobName)
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
