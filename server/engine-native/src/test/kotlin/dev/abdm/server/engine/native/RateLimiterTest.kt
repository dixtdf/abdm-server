package dev.abdm.server.engine.native

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rate limiter has to hold *while* bytes move.
 *
 * Regression tests for two real bugs: the engine used to consume its budget only
 * after a whole chunk had been written (so limited downloads looked stalled), and a
 * single shared bucket made every task slow down unrelated tasks and never refilled
 * at all when no global limit was set.
 */
class RateLimiterTest {

    @Test
    fun `zero means unlimited and never suspends`() = runBlocking {
        val limiter = RateLimiter()
        assertEquals(0, limiter.limit)
        assertTrue(limiter.isUnlimited)
        val started = System.currentTimeMillis()
        repeat(1000) { limiter.acquire(64 * 1024) }
        assertTrue(System.currentTimeMillis() - started < 500, "unlimited must not sleep")
    }

    @Test
    fun `a positive limit throttles to roughly that rate`() = runBlocking {
        val limiter = RateLimiter().apply { setLimit(512L * 1024) }
        val started = System.currentTimeMillis()
        repeat(4) { limiter.acquire(128L * 1024) }
        val elapsed = System.currentTimeMillis() - started
        // 4 * 128KiB at 512KiB/s = 1s; generous slack for CI scheduling
        assertTrue(elapsed >= 700, "expected throttling, took ${elapsed}ms")
        assertTrue(elapsed < 4_000, "throttling is too aggressive: ${elapsed}ms")
    }

    @Test
    fun `tasks do not share each other's budget`() = runBlocking {
        val controller = ThrottleController()
        controller.setGlobalLimit(0)
        val started = System.currentTimeMillis()
        // two tasks, each with its own 1 MiB/s budget: 512KiB each is ~0.5s in total
        val scope = CoroutineScope(coroutineContext)
        val jobs = listOf("a", "b").map { id ->
            scope.launch { repeat(4) { controller.acquire(id, 1024L * 1024, 128L * 1024) } }
        }
        jobs.forEach { it.join() }
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 1_500, "per task budgets must be independent: ${elapsed}ms")
    }

    @Test
    fun `a global limit applies on top of the per task limit`() = runBlocking {
        val controller = ThrottleController()
        controller.setGlobalLimit(256L * 1024)
        assertEquals(256L * 1024, controller.globalLimit())
        val started = System.currentTimeMillis()
        // 512KiB at the global 256KiB/s floor
        repeat(4) { controller.acquire("a", 10L * 1024 * 1024, 128L * 1024) }
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed >= 1_500, "the global limit must win: ${elapsed}ms")
    }

    @Test
    fun `cancellation while waiting propagates`() = runBlocking {
        val limiter = RateLimiter().apply { setLimit(1024) }
        val job = CoroutineScope(coroutineContext).launch {
            limiter.acquire(50L * 1024 * 1024)
        }
        delay(200)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }
}
