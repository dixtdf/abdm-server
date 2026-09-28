package dev.abdm.server.engine.api

/**
 * Text codec for a list of half open byte ranges: `0-1024,2048-4096`.
 *
 * Kept for reading and writing the existing SQLite task schema. ABDM itself
 * owns transfer checkpoints in its upstream data directory.
 */
object RangeCodec {
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
