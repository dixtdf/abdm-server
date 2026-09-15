package dev.abdm.server.engine.native

import java.util.concurrent.atomic.AtomicLong

/**
 * Windowed throughput meter.
 *
 * `current()` is computed over a sliding window (default 1s, sampled every 250ms)
 * so that the number stays readable instead of jumping on every chunk boundary.
 */
class SpeedMeter(
    private val windowMillis: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Sample(val at: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private val origin = AtomicLong(clock())
    private val totalBytes = AtomicLong(0)
    private var lastBytes = 0L

    fun onBytes(delta: Long) {
        totalBytes.addAndGet(delta)
    }

    fun total(): Long = totalBytes.get()

    fun elapsedMillis(): Long = (clock() - origin.get()).coerceAtLeast(1)

    /** Bytes per second over the sliding window. */
    @Synchronized
    fun current(): Long {
        val now = clock()
        val total = totalBytes.get()
        samples.addLast(Sample(now, total))
        while (samples.size > 2 && now - samples.first().at > windowMillis) {
            samples.removeFirst()
        }
        val first = samples.first()
        val span = now - first.at
        if (span < 50) {
            // Not enough resolution in the window yet: report the rate since start,
            // otherwise the very first sample would always read 0 B/s.
            val elapsed = (now - origin.get()).coerceAtLeast(1)
            lastBytes = if (total == 0L) 0 else total * 1000 / elapsed
            return lastBytes
        }
        val rate = ((total - first.bytes) * 1000L / span)
        lastBytes = if (total == first.bytes) 0 else rate.coerceAtLeast(0)
        return lastBytes
    }

    /** Average bytes per second since the meter was created (or last reset). */
    fun average(): Long {
        val total = totalBytes.get()
        if (total == 0L) return 0
        return total * 1000L / elapsedMillis()
    }

    @Synchronized
    fun reset() {
        samples.clear()
        lastBytes = 0
        origin.set(clock())
    }
}
