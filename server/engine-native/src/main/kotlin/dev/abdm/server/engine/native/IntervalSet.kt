package dev.abdm.server.engine.native

/**
 * A set of half open intervals `[start, end)` describing the bytes of a file that
 * are already present on disk.
 *
 * The whole progress of a segmented download is just this set, which makes
 * "change the number of connections while downloading" and "resume after restart"
 * the same operation: recompute the missing intervals and hand them to N workers.
 */
class IntervalSet private constructor(
    private var intervals: MutableList<LongRange>,
) {
    constructor() : this(mutableListOf())

    @Synchronized
    fun add(start: Long, end: Long) {
        if (end <= start) return
        var s = start
        var e = end
        val result = ArrayList<LongRange>(intervals.size + 1)
        var inserted = false
        for (range in intervals) {
            when {
                range.last + 1 < s -> result.add(range)
                e < range.first -> {
                    if (!inserted) {
                        result.add(s until e)
                        inserted = true
                    }
                    result.add(range)
                }
                else -> {
                    s = minOf(s, range.first)
                    e = maxOf(e, range.last + 1)
                }
            }
        }
        if (!inserted) result.add(s until e)
        intervals = result
    }

    @Synchronized
    fun total(): Long = intervals.sumOf { it.last - it.first + 1 }

    @Synchronized
    fun snapshot(): List<LongRange> = intervals.toList()

    /** Intervals that are still missing for a file of [length] bytes. */
    @Synchronized
    fun missing(length: Long): List<LongRange> {
        if (length <= 0) return emptyList()
        val gaps = ArrayList<LongRange>()
        var cursor = 0L
        for (range in intervals) {
            val start = range.first
            if (start > cursor) gaps.add(cursor until start)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < length) gaps.add(cursor until length)
        return gaps
    }

    @Synchronized
    fun clear() {
        intervals = mutableListOf()
    }

    companion object {
        fun of(ranges: List<LongRange>): IntervalSet {
            val set = IntervalSet()
            ranges.forEach { set.add(it.first, it.last + 1) }
            return set
        }

        fun encode(ranges: List<LongRange>): String =
            ranges.joinToString(",") { "${it.first}-${it.last + 1}" }

        fun decode(value: String?): List<LongRange> {
            if (value.isNullOrBlank()) return emptyList()
            return value.split(',').mapNotNull { part ->
                val sep = part.indexOf('-')
                if (sep <= 0) return@mapNotNull null
                val start = part.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
                val end = part.substring(sep + 1).toLongOrNull() ?: return@mapNotNull null
                if (end <= start) null else start until end
            }
        }
    }
}
