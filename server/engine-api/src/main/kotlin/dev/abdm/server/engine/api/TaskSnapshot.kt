package dev.abdm.server.engine.api

/**
 * Immutable view of a download task.
 *
 * This is what the REST API returns and what the WebSocket pushes on change.
 * `total == -1` means the server did not announce a content length.
 */
data class TaskSnapshot(
    val id: String,
    val url: String,
    val fileName: String,
    /** Absolute directory the file is written to. */
    val folder: String,
    /** Absolute path of the target file. */
    val path: String,
    val state: DownloadState,
    val downloaded: Long,
    val total: Long,
    /** Current throughput in bytes/second. */
    val speed: Long = 0,
    val averageSpeed: Long = 0,
    /** Requested connection count (1..256). */
    val connections: Int = Connections.DEFAULT,
    /** Connections currently transferring bytes. */
    val activeConnections: Int = 0,
    /** Seconds left, `-1` when unknown. */
    val etaSeconds: Long = -1,
    val supportsRange: Boolean = false,
    val hls: Boolean = false,
    val speedLimit: Long = 0,
    val checksum: String? = null,
    val error: EngineError? = null,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    /** Position in the waiting queue, `null` when not queued. */
    val queuePosition: Int? = null,
) {
    val progress: Double
        get() = if (total > 0) (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else 0.0
}

/** Lightweight progress push, sent at most every `progressIntervalMs`. */
data class TaskProgress(
    val id: String,
    val downloaded: Long,
    val total: Long,
    val speed: Long,
    val averageSpeed: Long,
    val connections: Int,
    val activeConnections: Int,
    val etaSeconds: Long,
    val state: DownloadState,
)
