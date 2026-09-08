package net.dinowang.actioncameradrain.domain.upload

import net.dinowang.actioncameradrain.background.UploadJobScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferModelsTest {

    @Test
    fun transferIdentityIsStableAndDestinationSpecific() {
        val first = UploadJobScheduler.transferId("azure", "content://card", "trip-a")
        val repeated = UploadJobScheduler.transferId("azure", "content://card", "trip-a")
        val otherContainer = UploadJobScheduler.transferId("azure", "content://card", "trip-b")

        assertEquals(first, repeated)
        assertNotEquals(first, otherContainer)
        assertTrue(UploadJobScheduler.jobId(first) > 0)
    }

    @Test
    fun persistedTransferMapsBackToUiProgress() {
        val record = TransferRecord(
            id = "id",
            jobId = 1,
            executionId = "execution",
            configId = "azure",
            treeUri = "content://card",
            container = "trip",
            rootLabel = "CARD",
            startMode = StartMode.RESUME,
            networkPolicy = NetworkPolicy.UNMETERED,
            state = TransferState.RUNNING,
            totalFiles = 18,
            doneFiles = 16,
            totalBytes = 1_000,
            doneBytes = 750,
            currentParallelism = 3,
            bytesPerSecond = 42.0,
        )

        val progress = record.toUploadProgress()!!

        assertEquals(UploadProgress.State.RUNNING, progress.state)
        assertEquals(16, progress.doneFiles)
        assertEquals(750L, progress.doneBytes)
        assertEquals(3, progress.currentParallelism)
    }
}
