package dev.abdm.server.engine.api

/**
 * State of a single connection / part.
 *
 * Mirrors what the AB Download Manager desktop client shows per row of its
 * "parts" table, so the web UI can present the same information.
 */
enum class PartState {
    /** Request sent, no byte received yet. */
    CONNECTING,

    /** Bytes are flowing. */
    DOWNLOADING,

    /** Owned by a connection that currently has nothing to fetch. */
    WAITING,

    /** Not started (queue empty, e.g. after a re-partition). */
    IDLE,

    /** Finished its assigned range. */
    DONE,

    /** Gave up after retries. */
    FAILED,
}

/**
 * Progress of one download connection.
 *
 * `total` is the size of the byte range this connection owns, so the UI can show a
 * meaningful "downloaded / total" pair per row exactly like the desktop client.
 * Re-partitioning (changing the connection count while downloading) rebuilds the
 * rows, which is why `index` is only stable for the current partition.
 */
data class PartProgress(
    val index: Int,
    val state: PartState,
    val downloaded: Long,
    val total: Long,
    /** Bytes per second for this connection. */
    val speed: Long = 0,
    /** First byte of the range this connection is working on. */
    val rangeStart: Long = 0,
) {
    val progress: Double
        get() = if (total > 0) (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else 0.0
}
