package net.dinowang.actioncameradrain.domain.upload

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BandwidthLimiterTest {

    @Test
    fun sharedLimiterSpacesConsecutiveBlocks() = runTest {
        val limiter = BandwidthLimiter { 1_000L }

        limiter.awaitPermit(1_000)
        limiter.awaitPermit(1_000)

        assertTrue(testScheduler.currentTime >= 900L)
    }
}
