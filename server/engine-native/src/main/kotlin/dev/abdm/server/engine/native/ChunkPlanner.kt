package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.Connections
import kotlin.math.max
import kotlin.math.min

/**
 * Splits the missing byte ranges of a file into fixed size work units.
 *
 * Fixed chunks (instead of one contiguous range per connection) are what makes
 * `setConnections` instantaneous: changing the worker count never invalidates the
 * ranges that are already in flight.
 */
class ChunkPlanner(
    val contentLength: Long,
    val rangeSupport: Boolean,
) {
    val chunkSize: Long = if (!rangeSupport || contentLength <= 0) {
        contentLength.coerceAtLeast(0)
    } else {
        // Aim for ~1024 chunks, bounded to [256 KiB, 8 MiB].
        val raw = contentLength / 1024
        min(max(raw, 256L * 1024), 8L * 1024 * 1024)
            .coerceAtMost(max(contentLength, 1L))
    }

    /** @return the queue of [start,end) chunks that still have to be fetched. */
    fun plan(missing: List<LongRange>): ArrayDeque<LongRange> {
        val queue = ArrayDeque<LongRange>()
        for (gap in missing) {
            var cursor = gap.first
            val end = gap.last + 1
            while (cursor < end) {
                val next = min(cursor + chunkSize, end)
                queue.addLast(cursor until next)
                cursor = next
            }
        }
        return queue
    }

    companion object {
        fun recommendedConnections(total: Long, requested: Int, rangeSupport: Boolean): Int {
            if (!rangeSupport) return 1
            if (total <= 0) return Connections.coerce(requested)
            // No point in opening more connections than we have 1 MiB chunks for.
            val bySize = max(1L, min(total / (1024L * 1024L), Connections.MAX.toLong())).toInt()
            return Connections.coerce(min(requested, bySize))
        }
    }
}
