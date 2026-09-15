package dev.abdm.server.webapi

import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineError
import dev.abdm.server.engine.api.HistoryEntry
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskProgress
import dev.abdm.server.engine.api.TaskSnapshot
import kotlinx.serialization.Serializable

/**
 * Wire format. Mirrors `docs/api.md` one to one — keep them in sync.
 */

@Serializable
data class ErrorDto(
    val code: String,
    val params: Map<String, String> = emptyMap(),
    val detail: String? = null,
)

@Serializable
data class ErrorEnvelope(val error: ErrorDto)

@Serializable
data class PartDto(
    val index: Int,
    val state: String,
    val downloaded: Long,
    val total: Long,
    val progress: Double = 0.0,
    val speed: Long = 0,
    val rangeStart: Long = 0,
)

@Serializable
data class TaskDto(
    val id: String,
    val url: String,
    val fileName: String,
    val folder: String,
    val path: String,
    val state: String,
    val downloaded: Long,
    val total: Long,
    val progress: Double,
    val speed: Long,
    val averageSpeed: Long,
    val connections: Int,
    val activeConnections: Int,
    /** One row per connection, in the order of the current partition. */
    val parts: List<PartDto> = emptyList(),
    val etaSeconds: Long,
    val supportsRange: Boolean,
    val hls: Boolean,
    val speedLimit: Long,
    val checksum: String? = null,
    val error: ErrorDto? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val queuePosition: Int? = null,
)

@Serializable
data class ProgressDto(
    val downloaded: Long,
    val total: Long,
    val speed: Long,
    val averageSpeed: Long,
    val connections: Int,
    val activeConnections: Int,
    val etaSeconds: Long,
    val state: String,
)

@Serializable
data class CreateDownloadDto(
    val url: String,
    val fileName: String? = null,
    val folder: String? = null,
    val connections: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val cookies: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val proxy: String? = null,
    val speedLimit: Long = 0,
    val checksum: String? = null,
    val hls: Boolean = false,
    val overwrite: Boolean = false,
    val startImmediately: Boolean = true,
)

@Serializable
data class ConnectionsDto(val connections: Int)

@Serializable
data class SettingsDto(
    val downloadRoot: String,
    val defaultFolder: String,
    val defaultConnections: Int,
    val maxConcurrentDownloads: Int,
    val globalSpeedLimit: Long,
    val resumeOnStartup: Boolean,
    val progressIntervalMs: Long,
    val authMode: String,
    val maxConnections: Int,
)

@Serializable
data class SettingsPatchDto(
    val downloadRoot: String? = null,
    val defaultFolder: String? = null,
    val defaultConnections: Int? = null,
    val maxConcurrentDownloads: Int? = null,
    val globalSpeedLimit: Long? = null,
    val resumeOnStartup: Boolean? = null,
    val progressIntervalMs: Long? = null,
)

@Serializable
data class DirectoryEntryDto(val name: String, val path: String)

@Serializable
data class DirectoryListingDto(
    val path: String,
    val parent: String? = null,
    val directories: List<DirectoryEntryDto> = emptyList(),
)

@Serializable
data class HistoryDto(
    val id: String,
    val url: String,
    val fileName: String,
    val folder: String,
    val size: Long,
    val checksum: String? = null,
    val outcome: String,
    val finishedAt: Long,
)

@Serializable
data class HealthDto(
    val status: String,
    val uptimeSeconds: Long,
    val version: String,
    val engine: String,
)

@Serializable
data class VersionDto(
    val version: String,
    val apiVersion: String,
    val engine: String,
    val engineVersion: String,
    val engineVendor: String,
    val upstreamName: String? = null,
    val upstreamVersion: String? = null,
    val upstreamCommit: String? = null,
    val capabilities: List<String> = emptyList(),
    val java: String,
    val os: String,
    val startedAt: Long,
)

@Serializable
data class ServerHelloDto(
    val version: String,
    val engine: String,
    val apiVersion: String,
)

/**
 * One envelope shape for every WebSocket frame: `type` decides which fields are set.
 * `null` fields are omitted from the JSON (`explicitNulls = false`).
 */
@Serializable
data class EventEnvelope(
    val type: String,
    val taskId: String? = null,
    val task: TaskDto? = null,
    val progress: ProgressDto? = null,
    val previous: String? = null,
    val state: String? = null,
    val error: ErrorDto? = null,
    val requested: Int? = null,
    val active: Int? = null,
    val parts: List<PartDto>? = null,
    val deletedFile: Boolean? = null,
    val server: ServerHelloDto? = null,
    val tasks: List<TaskDto>? = null,
)

// --------------------------------------------------------------------------- mappers

fun EngineError.toDto(): ErrorDto = ErrorDto(code = code, params = params, detail = detail)

fun TaskSnapshot.toDto(queuePosition: Int? = this.queuePosition): TaskDto = TaskDto(
    id = id,
    url = url,
    fileName = fileName,
    folder = folder,
    path = path,
    state = state.name,
    downloaded = downloaded,
    total = total,
    progress = progress,
    speed = speed,
    averageSpeed = averageSpeed,
    connections = connections,
    activeConnections = activeConnections,
    parts = parts.map { it.toDto() },
    etaSeconds = etaSeconds,
    supportsRange = supportsRange,
    hls = hls,
    speedLimit = speedLimit,
    checksum = checksum,
    error = error?.toDto(),
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
    queuePosition = queuePosition,
)

fun dev.abdm.server.engine.api.PartProgress.toDto(): PartDto = PartDto(
    index = index,
    state = state.name,
    downloaded = downloaded,
    total = total,
    progress = progress,
    speed = speed,
    rangeStart = rangeStart,
)

fun TaskProgress.toDto(): ProgressDto = ProgressDto(
    downloaded = downloaded,
    total = total,
    speed = speed,
    averageSpeed = averageSpeed,
    connections = connections,
    activeConnections = activeConnections,
    etaSeconds = etaSeconds,
    state = state.name,
)

fun HistoryEntry.toDto(): HistoryDto = HistoryDto(
    id = id,
    url = url,
    fileName = fileName,
    folder = folder,
    size = size,
    checksum = checksum,
    outcome = outcome,
    finishedAt = finishedAt,
)

fun ServerSettings.toDto(): SettingsDto = SettingsDto(
    downloadRoot = downloadRoot,
    defaultFolder = defaultFolder,
    defaultConnections = defaultConnections,
    maxConcurrentDownloads = maxConcurrentDownloads,
    globalSpeedLimit = globalSpeedLimit,
    resumeOnStartup = resumeOnStartup,
    progressIntervalMs = progressIntervalMs,
    authMode = authMode,
    maxConnections = dev.abdm.server.engine.api.Connections.MAX,
)

fun CreateDownloadDto.toRequest(): dev.abdm.server.engine.api.CreateDownloadRequest =
    dev.abdm.server.engine.api.CreateDownloadRequest(
        url = url,
        fileName = fileName,
        folder = folder,
        connections = connections,
        headers = headers,
        cookies = cookies,
        referer = referer,
        userAgent = userAgent,
        proxy = proxy,
        speedLimit = speedLimit,
        checksum = checksum,
        hls = hls,
        overwrite = overwrite,
        startImmediately = startImmediately,
    )

@Suppress("unused")
private val states = DownloadState.entries
