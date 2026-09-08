package net.dinowang.actioncameradrain.domain.upload

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * Shared rate limiter for all upload workers. A null rate means unlimited.
 * The provider is evaluated for every block so switching to a metered network
 * during an upload starts throttling without restarting the job.
 */
class BandwidthLimiter(
    private val bytesPerSecond: () -> Long?,
) {
    private val mutex = Mutex()
    private var nextSlotNanos = 0L

    suspend fun awaitPermit(bytes: Int) {
        val rate = bytesPerSecond()?.takeIf { it > 0L } ?: return
        val waitNanos = mutex.withLock {
            val now = System.nanoTime()
            val start = max(now, nextSlotNanos)
            val duration = (bytes.toDouble() * NANOS_PER_SECOND / rate)
                .toLong()
                .coerceAtLeast(1L)
            nextSlotNanos = start + duration
            start - now
        }
        if (waitNanos > 0L) {
            delay((waitNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
        }
    }

    companion object {
        val Unlimited = BandwidthLimiter { null }

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
