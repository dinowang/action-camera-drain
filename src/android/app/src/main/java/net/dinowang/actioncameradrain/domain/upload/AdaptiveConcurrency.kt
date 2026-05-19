package net.dinowang.actioncameradrain.domain.upload

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Throughput tracker — exponentially weighted bytes/sec.
 */
class ThroughputTracker {
    private var bytes: Long = 0
    private var startNanos: Long = System.nanoTime()
    @Volatile private var ewmaBytesPerSec: Double = 0.0
    private val alpha = 0.3

    @Synchronized fun add(n: Long) { bytes += n }

    @Synchronized fun sampleAndReset(): Double {
        val now = System.nanoTime()
        val elapsedSec = (now - startNanos) / 1e9
        if (elapsedSec <= 0) return ewmaBytesPerSec
        val current = bytes / elapsedSec
        ewmaBytesPerSec = if (ewmaBytesPerSec == 0.0) current else (alpha * current + (1 - alpha) * ewmaBytesPerSec)
        bytes = 0
        startNanos = now
        return ewmaBytesPerSec
    }

    val bytesPerSec: Double get() = ewmaBytesPerSec
}

/**
 * Resizable permit gate. At most [target] permits may be held simultaneously;
 * extra waiters block until one is released. [setTarget] grows or shrinks the
 * gate; shrinking is "soft" — currently-held permits are not revoked, but new
 * acquires beyond the new target will wait.
 */
class ResizableGate(initial: Int, private val maxCap: Int = 16) {
    @Volatile private var target = initial.coerceAtLeast(1).coerceAtMost(maxCap)
    private val held = AtomicInteger(0)
    private val wakeups = Channel<Unit>(Channel.UNLIMITED)

    val currentTarget: Int get() = target
    val currentHeld: Int get() = held.get()

    suspend fun acquire() {
        while (true) {
            val h = held.get()
            if (h < target) {
                if (held.compareAndSet(h, h + 1)) return
            } else {
                wakeups.receive()
            }
        }
    }

    fun release() {
        held.decrementAndGet()
        wakeups.trySend(Unit)
    }

    fun setTarget(t: Int) {
        val newT = t.coerceAtLeast(1).coerceAtMost(maxCap)
        val diff = newT - target
        target = newT
        if (diff > 0) repeat(diff) { wakeups.trySend(Unit) }
    }
}

/**
 * Adaptive concurrency controller. Call [tick] periodically with the current
 * throughput sample; the gate is resized based on a simple heuristic:
 *  - grow while throughput improves
 *  - shrink one notch when throughput regresses
 *  - cold start: grow each cycle
 */
class AdaptiveConcurrency(
    initial: Int = 2,
    private val min: Int = 1,
    private val max: Int = 8,
) {
    val gate = ResizableGate(initial = initial.coerceIn(min, max), maxCap = max)
    private var lastBps: Double = 0.0
    private var lastAction: Int = +1

    val currentTarget: Int get() = gate.currentTarget

    fun tick(currentBps: Double) {
        val cur = gate.currentTarget
        val improved = lastBps == 0.0 || currentBps > lastBps * 1.05
        val regressed = lastBps > 0 && currentBps < lastBps * 0.85
        val newTarget = when {
            regressed -> (cur - 1).coerceAtLeast(min)
            improved && cur < max -> cur + 1
            else -> cur
        }
        if (newTarget != cur) {
            gate.setTarget(newTarget)
            lastAction = newTarget - cur
        } else lastAction = 0
        lastBps = currentBps
    }
}
