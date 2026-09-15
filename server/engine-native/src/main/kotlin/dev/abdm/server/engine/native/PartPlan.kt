package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.PartProgress
import dev.abdm.server.engine.api.PartState
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/**
 * The partition of a download: one [Part] per connection, in the AB Download
 * Manager sense ("每个线程/分片").
 *
 * Every part owns a contiguous slice of the file and reports its own
 * `downloaded / total`, which is exactly what the desktop client shows in its part
 * table. Two invariants hold for the lifetime of a plan, and both are asserted by
 * the tests:
 *
 *  * `sum(part.total)` never changes: the parts always describe one whole file.
 *  * `sum(part.downloaded)` only grows by the bytes actually written to disk.
 *
 * Changing the connection count while downloading *re-partitions*: parts are split
 * (increase) or merged (decrease) instead of being rebuilt, so the numbers a user is
 * watching never jump back to zero.
 */
internal class PartPlan(
    val parts: List<Part>,
    val chunkSize: Long,
) {
    internal class Part(
        var index: Int,
        val queue: WorkQueue,
        /** Bytes this connection is responsible for. */
        var total: Long,
    ) {
        @Volatile
        var state: PartState = PartState.IDLE

        @Volatile
        var rangeStart: Long = 0

        val downloaded = AtomicLong(0)
        val meter = SpeedMeter()

        val isFinished: Boolean get() = queue.isDone

        fun remainingBytes(): Long = queue.remaining().sumOf { it.last - it.first + 1 }

        fun markDone() {
            state = PartState.DONE
        }
    }

    val isDone: Boolean get() = parts.all { it.isFinished }

    fun activeCount(): Int =
        parts.count { it.state == PartState.CONNECTING || it.state == PartState.DOWNLOADING }

    /** Everything that is still missing, in file order. */
    fun remainingRanges(): List<LongRange> = parts.flatMap { it.queue.remaining() }.sortedBy { it.first }

    fun snapshots(): List<PartProgress> = parts.map { part ->
        PartProgress(
            index = part.index,
            state = part.state,
            downloaded = part.downloaded.get(),
            total = part.total,
            speed = if (part.state == PartState.DOWNLOADING || part.state == PartState.CONNECTING) {
                part.meter.current()
            } else {
                0
            },
            rangeStart = part.rangeStart,
        )
    }

    companion object {
        /**
         * Splits [missing] into at most [partCount] parts of roughly equal size.
         *
         * Ranges are chunked first (so pause/checkpoint granularity stays bounded by
         * [chunkSize]) and then handed out in contiguous runs: every connection works on
         * one region of the file, which is friendly to servers and matches upstream.
         */
        fun plan(missing: List<LongRange>, partCount: Int, chunkSize: Long): PartPlan {
            val chunks = ArrayList<LongRange>()
            for (gap in missing) {
                var cursor = gap.first
                val end = gap.last + 1
                while (cursor < end) {
                    val next = min(cursor + chunkSize.coerceAtLeast(1), end)
                    chunks.add(cursor until next)
                    cursor = next
                }
            }
            if (chunks.isEmpty()) return PartPlan(emptyList(), chunkSize)

            val wanted = partCount.coerceIn(1, chunks.size)
            val totalBytes = chunks.sumOf { it.last - it.first + 1 }
            val targetPerPart = ceil(totalBytes.toDouble() / wanted.toDouble()).toLong()

            val buckets = ArrayList<MutableList<LongRange>>(wanted)
            var bucket = ArrayList<LongRange>()
            var bucketBytes = 0L
            chunks.forEachIndexed { position, chunk ->
                bucket.add(chunk)
                bucketBytes += chunk.last - chunk.first + 1
                val chunksLeft = chunks.size - position - 1
                val partsLeftAfterThis = wanted - buckets.size - 1
                val mustSplit = chunksLeft <= partsLeftAfterThis
                if (bucketBytes >= targetPerPart || mustSplit) {
                    buckets.add(bucket)
                    bucket = ArrayList()
                    bucketBytes = 0
                }
            }
            if (bucket.isNotEmpty()) buckets.add(bucket)

            val parts = buckets.mapIndexed { index, ranges ->
                Part(
                    index = index + 1,
                    queue = WorkQueue(ranges),
                    total = ranges.sumOf { it.last - it.first + 1 },
                ).apply { rangeStart = ranges.firstOrNull()?.first ?: 0 }
            }
            return PartPlan(parts, chunkSize)
        }

        /**
         * Re-partitions after a connection count change.
         *
         * Must be called while no worker is running (in-flight chunks are handed back
         * first), so the queues are stable here.
         */
        fun repartition(previous: PartPlan, partCount: Int): PartPlan {
            val parts = previous.parts.toMutableList()
            val target = partCount.coerceAtLeast(1)

            if (target > parts.size) {
                var nextIndex = (parts.maxOfOrNull { it.index } ?: 0) + 1
                repeat(target - parts.size) {
                    val victim = parts
                        .filter { !it.isFinished }
                        .maxByOrNull { it.remainingBytes() }
                        ?: return@repeat
                    val ranges = victim.queue.drainRemaining()
                    if (ranges.size < 2) {
                        victim.queue.absorb(ranges)
                        return@repeat
                    }
                    val half = ranges.size / 2
                    val kept = ranges.subList(0, half).toList()
                    val moved = ranges.subList(half, ranges.size).toList()
                    victim.queue.absorb(kept)
                    // The victim is no longer responsible for the moved part, so its
                    // total shrinks by exactly what the new part takes over: the sum
                    // over all rows stays equal to the size of the file.
                    victim.total -= moved.sumOf { it.last - it.first + 1 }
                    parts.add(
                        Part(
                            index = nextIndex++,
                            queue = WorkQueue(moved),
                            total = moved.sumOf { it.last - it.first + 1 },
                        ).apply { rangeStart = moved.firstOrNull()?.first ?: 0 },
                    )
                }
            } else if (target < parts.size) {
                var toRemove = parts.size - target
                while (toRemove > 0) {
                    val victim = parts.filter { !it.isFinished }.minByOrNull { it.remainingBytes() }
                        ?: parts.maxByOrNull { it.index }
                        ?: break
                    val host = parts
                        .filter { it !== victim }
                        .maxByOrNull { it.remainingBytes() }
                        ?: break
                    host.queue.absorb(victim.queue.drainRemaining())
                    host.total += victim.total
                    host.downloaded.addAndGet(victim.downloaded.get())
                    parts.remove(victim)
                    toRemove--
                }
            }

            val numbered = parts.mapIndexed { position, part ->
                part.index = position + 1
                part
            }
            return PartPlan(numbered, previous.chunkSize)
        }
    }
}
