package dev.abdm.server.engine.api

import kotlinx.coroutines.flow.SharedFlow

/** Optional behaviours an engine implementation may or may not support. */
enum class EngineCapability {
    HTTP_RANGE,
    HLS,
    RESUME,
    DYNAMIC_CONNECTIONS,
    SPEED_LIMIT,
    CHECKSUM,
    PROXY,
    CUSTOM_HEADERS,
}

/**
 * The contract the web server talks to.
 *
 * Nothing in this file mentions AB Download Manager: swapping the engine
 * (native -> ABDM, or ABDM 1.x -> 2.x) never touches web-api, persistence,
 * scheduler or the frontend.
 */
interface DownloadEngine {
    /** Human readable engine id, e.g. `native` or `abdm`. */
    val name: String

    val capabilities: Set<EngineCapability>

    /** Who performs the download, surfaced by `GET /api/v1/version`. */
    val descriptor: EngineDescriptor
        get() = EngineDescriptor(name = name, version = "0.1.0", capabilities = capabilities)

    /** @return the new task id. */
    suspend fun create(request: CreateDownloadRequest): String

    suspend fun start(id: String)

    suspend fun pause(id: String)

    suspend fun resume(id: String)

    suspend fun remove(id: String, deleteFile: Boolean = false)

    /** Live reconfiguration; must not interrupt an in flight download. */
    suspend fun setConnections(id: String, connections: Int)

    suspend fun get(id: String): TaskSnapshot?

    suspend fun list(): List<TaskSnapshot>

    /** Hot stream of state/progress events for all tasks. */
    fun events(): SharedFlow<EngineEvent>

    /** Flush state and release resources. */
    suspend fun shutdown()
}

/** Raised when the configured engine cannot be constructed at all. */
class EngineUnavailableException(
    val engineName: String,
    detail: String,
) : EngineException(
    EngineError(
        EngineErrorCode.ENGINE_UNAVAILABLE,
        mapOf("engine" to engineName),
        detail,
    ),
)
