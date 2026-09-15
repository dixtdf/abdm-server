package dev.abdm.server.engine.api

import kotlinx.serialization.Serializable

/**
 * Storage ports.
 *
 * `server:persistence` implements these with SQLite. The engines and the scheduler
 * only depend on the interfaces, so a different storage backend stays possible.
 */

/** A task as stored on disk, including its checkpointed progress. */
data class PersistedTask(
    val id: String,
    val url: String,
    val fileName: String,
    val folder: String,
    val state: DownloadState,
    val total: Long,
    val downloaded: Long,
    val connections: Int,
    val supportsRange: Boolean,
    val hls: Boolean,
    val speedLimit: Long,
    val checksum: String?,
    val error: EngineError?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val queuePosition: Int?,
    /** Intervals (inclusive-exclusive [start,end)) that are already on disk. */
    val completedRanges: List<LongRange>,
    val headers: Map<String, String> = emptyMap(),
    val cookies: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val proxy: String? = null,
    /** Insertion order, used to keep the list stable across restarts. */
    val sequence: Long = 0,
)

data class HistoryEntry(
    val id: String,
    val url: String,
    val fileName: String,
    val folder: String,
    val size: Long,
    val checksum: String?,
    val outcome: String,
    val finishedAt: Long,
)

/** Per-host connection profile (phase 2 groundwork). */
@Serializable
data class HostProfile(
    val host: String,
    val preferredConnections: Int,
    val maxConnections: Int = Connections.MAX,
)

interface TaskRepository {
    suspend fun loadAll(): List<PersistedTask>

    suspend fun upsert(task: PersistedTask)

    /** Frequent, cheap checkpoint: only the progress columns are written. */
    suspend fun updateProgress(id: String, downloaded: Long, completedRanges: List<LongRange>)

    suspend fun delete(id: String)

    suspend fun nextSequence(): Long

    suspend fun appendHistory(entry: HistoryEntry)

    suspend fun history(limit: Int = 100): List<HistoryEntry>

    suspend fun clearHistory()
}

@Serializable
data class ServerSettings(
    /** Only paths below this root are writable through the API. */
    val downloadRoot: String = "/downloads",
    val defaultFolder: String = "/downloads",
    val defaultConnections: Int = Connections.DEFAULT,
    val maxConcurrentDownloads: Int = 3,
    /** Bytes/second, 0 = unlimited. */
    val globalSpeedLimit: Long = 0,
    val resumeOnStartup: Boolean = true,
    val progressIntervalMs: Long = 500,
    val authMode: String = "none",
)

interface SettingsRepository {
    suspend fun load(): ServerSettings

    suspend fun save(settings: ServerSettings)
}

interface HostProfileRepository {
    suspend fun all(): List<HostProfile>

    suspend fun put(profile: HostProfile)
}
