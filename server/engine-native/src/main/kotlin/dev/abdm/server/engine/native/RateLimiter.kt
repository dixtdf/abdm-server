package dev.abdm.server.engine.native

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Token bucket for one stream of bytes.
 *
 * `limit == 0` means unlimited. Tokens are capped at one second worth of traffic so
 * an idle period cannot release a burst.
 */
class RateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var limit: Long = 0
        private set

    private val tokens = AtomicLong(0)
    private val lastRefill = AtomicLong(clock())

    fun setLimit(bytesPerSecond: Long) {
        limit = bytesPerSecond.coerceAtLeast(0)
    }

    val isUnlimited: Boolean get() = limit <= 0

    /** Suspends until [bytes] may be transferred at this bucket's rate. */
    suspend fun acquire(bytes: Long) {
        val limit = limit
        if (limit <= 0 || bytes <= 0) return
        var granted = 0L
        while (granted < bytes) {
            val available = refill(limit)
            if (available > 0) {
                val take = minOf(available, bytes - granted)
                tokens.addAndGet(-take)
                granted += take
                continue
            }
            val waitMs = ((bytes - granted) * 1000L / limit).coerceIn(1, 250)
            delay(waitMs)
        }
    }

    @Synchronized
    private fun refill(limit: Long): Long {
        val now = clock()
        val previous = lastRefill.getAndSet(now)
        val elapsed = (now - previous).coerceAtLeast(0)
        if (elapsed == 0L) return minOf(tokens.get(), limit)
        val added = elapsed * limit / 1000
        return tokens.updateAndGet { current -> minOf(current + added, limit) }
    }
}

/**
 * Speed limiting for the whole engine.
 *
 * One bucket for the global limit plus one bucket per task: "global speed limit" and
 * "per task speed limit" are independent settings, and a task must not be slowed
 * down by an unrelated task's budget. When both are configured the slower one wins,
 * which is what a user expects from two limits.
 */
class ThrottleController {
    private val global = RateLimiter()
    private val perTask = ConcurrentHashMap<String, RateLimiter>()

    fun setGlobalLimit(bytesPerSecond: Long) = global.setLimit(bytesPerSecond)

    fun globalLimit(): Long = global.limit

    fun forget(taskId: String) {
        perTask.remove(taskId)
    }

    suspend fun acquire(taskId: String, taskLimit: Long, bytes: Long) {
        if (bytes <= 0) return
        if (global.limit > 0) global.acquire(bytes)
        if (taskLimit > 0) {
            perTask.computeIfAbsent(taskId) { RateLimiter() }.apply { setLimit(taskLimit) }.acquire(bytes)
        }
    }
}
