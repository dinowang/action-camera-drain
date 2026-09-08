package net.dinowang.actioncameradrain.domain.upload

import kotlinx.coroutines.test.runTest
import net.dinowang.actioncameradrain.data.storage.BlobProperties
import net.dinowang.actioncameradrain.domain.filing.FakeMediaFile
import net.dinowang.actioncameradrain.domain.filing.IngestPlan
import net.dinowang.actioncameradrain.domain.filing.PlannedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadEngineTest {

    private val scope = CheckpointStore.ScopeKey("config", "source")

    @Test
    fun uploadCommitsAndVerifiesBlob() = runTest {
        val client = FakeBlobClient()
        val checkpoints = FakeCheckpointStore()
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = checkpoints,
            scope = scope,
            blockSize = 4,
            maxRetries = 0,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
        )

        val state = engine.execute(plan("clip.mp4", "abcdefgh"), StartMode.RESUME)

        assertEquals(UploadProgress.State.COMPLETED, state)
        assertEquals(2, client.putBlockCount)
        assertEquals(8L, engine.progress.value.doneBytes)
        assertEquals(1, engine.progress.value.doneFiles)
        assertTrue(checkpoints.values.isEmpty())
    }

    @Test
    fun restartDeletesEveryPlannedBlobBeforeUploading() = runTest {
        val client = FakeBlobClient()
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = FakeCheckpointStore(),
            scope = scope,
            blockSize = 4,
            maxRetries = 0,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
        )
        val plan = IngestPlan(
            items = listOf(
                item("a.mp4", "aaaa"),
                item("b.mp4", "bbbb"),
            ),
            buckets = emptyMap(),
            conflicts = emptyList(),
            oversized = emptyList(),
        )

        engine.execute(plan, StartMode.RESTART)

        assertTrue(client.deleted.take(2).containsAll(listOf("a.mp4", "b.mp4")))
    }

    @Test
    fun failedAttemptDoesNotInflateProgress() = runTest {
        val client = FakeBlobClient(failOnBlockCall = 2)
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = FakeCheckpointStore(),
            scope = scope,
            blockSize = 4,
            maxRetries = 1,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
            ownershipId = "execution",
        )

        val state = engine.execute(plan("clip.mp4", "abcdefgh"), StartMode.RESUME)

        assertEquals(UploadProgress.State.COMPLETED, state)
        assertEquals(8L, engine.progress.value.doneBytes)
        assertTrue(client.deleted.isEmpty())
    }

    @Test
    fun transientHeadFailureDoesNotDeleteExistingBlob() = runTest {
        val client = FakeBlobClient(failHeadCalls = 1)
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = FakeCheckpointStore(),
            scope = scope,
            blockSize = 4,
            maxRetries = 1,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
            ownershipId = "execution",
        )

        val state = engine.execute(plan("clip.mp4", "abcdefgh"), StartMode.RESUME)

        assertEquals(UploadProgress.State.COMPLETED, state)
        assertTrue(client.deleted.isEmpty())
    }

    @Test
    fun transientPostCommitHeadFailureDoesNotDeleteCommittedBlob() = runTest {
        val client = FakeBlobClient(failHeadOnCalls = setOf(2))
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = FakeCheckpointStore(),
            scope = scope,
            blockSize = 4,
            maxRetries = 1,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
            ownershipId = "execution",
        )

        val state = engine.execute(plan("clip.mp4", "abcdefgh"), StartMode.RESUME)

        assertEquals(UploadProgress.State.COMPLETED, state)
        assertTrue(client.deleted.isEmpty())
    }

    @Test
    fun retryDoesNotDeletePreExistingCommittedBlob() = runTest {
        val client = FakeBlobClient(failOnBlockCall = 2).apply {
            seed("clip.mp4", BlobProperties(contentLength = 3, metadata = mapOf("mtime" to "1")))
        }
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = FakeCheckpointStore(),
            scope = scope,
            blockSize = 4,
            maxRetries = 1,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
            ownershipId = "execution",
        )

        val state = engine.execute(plan("clip.mp4", "abcdefgh"), StartMode.RESUME)

        assertEquals(UploadProgress.State.COMPLETED, state)
        assertTrue(client.deleted.isEmpty())
    }

    @Test
    fun resumeUsesMatchingCheckpoint() = runTest {
        val client = FakeBlobClient()
        val checkpoints = FakeCheckpointStore().apply {
            save(
                scope,
                UploadCheckpoint(
                    blobName = "clip.mp4",
                    fileSize = 8,
                    fileMtime = 1234,
                    blockSize = 4,
                    uploadedBlocks = listOf("existing-block"),
                ),
            )
        }
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = checkpoints,
            scope = scope,
            blockSize = 4,
            maxRetries = 0,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
        )

        engine.execute(plan("clip.mp4", "abcdefgh"), StartMode.RESUME)

        assertEquals(1, client.putBlockCount)
        assertEquals(2, client.committedBlockIds.single().size)
        assertEquals(8L, engine.progress.value.doneBytes)
    }

    @Test
    fun unknownTimestampVerifiesBySizeOnly() = runTest {
        val client = FakeBlobClient()
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = FakeCheckpointStore(),
            scope = scope,
            blockSize = 4,
            maxRetries = 0,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
        )
        val file = FakeMediaFile(
            pathSegments = listOf("clip.mp4"),
            bytes = "abcd".toByteArray(),
            lastModifiedMillis = 0,
        )
        val plan = IngestPlan(
            items = listOf(PlannedItem(file, "clip.mp4", null, "")),
            buckets = emptyMap(),
            conflicts = emptyList(),
            oversized = emptyList(),
        )

        val state = engine.execute(plan, StartMode.RESUME)

        assertEquals(UploadProgress.State.COMPLETED, state)
        assertEquals(4L, engine.progress.value.doneBytes)
    }

    @Test
    fun existingBlobWithUnknownTimestampSkipsBySizeOnly() = runTest {
        val client = FakeBlobClient().apply {
            seed("clip.mp4", BlobProperties(contentLength = 4, metadata = emptyMap()))
        }
        val engine = UploadEngine(
            client = client,
            container = "target",
            checkpoints = FakeCheckpointStore(),
            scope = scope,
            blockSize = 4,
            maxRetries = 0,
            concurrency = AdaptiveConcurrency(initial = 1, min = 1, max = 1),
        )
        val file = FakeMediaFile(
            pathSegments = listOf("clip.mp4"),
            bytes = "abcd".toByteArray(),
            lastModifiedMillis = 0,
        )
        val plan = IngestPlan(
            items = listOf(PlannedItem(file, "clip.mp4", null, "")),
            buckets = emptyMap(),
            conflicts = emptyList(),
            oversized = emptyList(),
        )

        val state = engine.execute(plan, StartMode.RESUME)

        assertEquals(UploadProgress.State.COMPLETED, state)
        assertEquals(0, client.putBlockCount)
        assertEquals(4L, engine.progress.value.doneBytes)
    }

    private fun plan(name: String, content: String): IngestPlan = IngestPlan(
        items = listOf(item(name, content)),
        buckets = emptyMap(),
        conflicts = emptyList(),
        oversized = emptyList(),
    )

    private fun item(name: String, content: String) = PlannedItem(
        file = FakeMediaFile(
            pathSegments = listOf(name),
            bytes = content.toByteArray(),
            lastModifiedMillis = 1234,
        ),
        blobName = name,
        device = null,
        subdir = "",
    )
}

private class FakeBlobClient(
    private val failOnBlockCall: Int? = null,
    private var failHeadCalls: Int = 0,
    private val failHeadOnCalls: Set<Int> = emptySet(),
) : BlobUploadClient {
    val deleted = mutableListOf<String>()
    val committedBlockIds = mutableListOf<List<String>>()
    var putBlockCount = 0
    private var headCallCount = 0
    private val committed = mutableMapOf<String, BlobProperties>()

    fun seed(blobName: String, properties: BlobProperties) {
        committed[blobName] = properties
    }

    override fun putBlock(
        container: String,
        blobName: String,
        blockId: String,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        putBlockCount++
        if (putBlockCount == failOnBlockCall) {
            error("simulated upload failure")
        }
    }

    override fun putBlockList(
        container: String,
        blobName: String,
        blockIds: List<String>,
        contentType: String?,
        metadata: Map<String, String>,
    ) {
        committedBlockIds += blockIds
        committed[blobName] = BlobProperties(
            contentLength = metadata.getValue("size").toLong(),
            metadata = metadata,
        )
    }

    override fun headBlob(container: String, blobName: String): BlobProperties? {
        headCallCount++
        if (failHeadCalls > 0) {
            failHeadCalls--
            error("simulated HEAD failure")
        }
        if (headCallCount in failHeadOnCalls) {
            error("simulated HEAD failure")
        }
        return committed[blobName]
    }

    override fun deleteBlob(container: String, blobName: String) {
        deleted += blobName
        committed.remove(blobName)
    }
}

private class FakeCheckpointStore : UploadCheckpointRepository {
    val values = mutableMapOf<String, UploadCheckpoint>()

    override suspend fun load(
        scope: CheckpointStore.ScopeKey,
        blobName: String,
    ): UploadCheckpoint? = values[blobName]

    override suspend fun save(scope: CheckpointStore.ScopeKey, cp: UploadCheckpoint) {
        values[cp.blobName] = cp
    }

    override suspend fun delete(scope: CheckpointStore.ScopeKey, blobName: String) {
        values.remove(blobName)
    }

    override suspend fun deleteAllForScope(scope: CheckpointStore.ScopeKey) {
        values.clear()
    }

    override suspend fun listForScope(
        scope: CheckpointStore.ScopeKey,
    ): List<UploadCheckpoint> = values.values.toList()
}
