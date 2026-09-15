package dev.abdm.server.engine.abdm

import dev.abdm.server.engine.api.EngineCapability
import java.nio.file.Path

/**
 * Facts about the pinned upstream, written down once and reused by the adapter,
 * the `/version` endpoint, the About page and `docs/upstream.md`.
 *
 * Upstream: https://github.com/amir1376/ab-download-manager (Apache-2.0)
 */
object AbdmEngineInfo {
    const val UPSTREAM_NAME = "AB Download Manager"
    const val UPSTREAM_VERSION = "1.10.4"
    const val UPSTREAM_COMMIT = "afc57634b3c121c6415213242b2b600cccc6fd6e"
    const val UPSTREAM_SHORT_COMMIT = "afc5763"
    const val ENGINE_NAME = "abdm"
    const val ENGINE_VERSION = UPSTREAM_VERSION

    /** What the bridge can actually do; reported through the API and the UI. */
    val CAPABILITIES: Set<EngineCapability> = setOf(
        EngineCapability.HTTP_RANGE,
        EngineCapability.HLS,
        EngineCapability.RESUME,
        EngineCapability.DYNAMIC_CONNECTIONS,
        EngineCapability.SPEED_LIMIT,
        EngineCapability.CHECKSUM,
        EngineCapability.CUSTOM_HEADERS,
    )
}

data class AbdmEngineConfig(
    /** All downloads must stay below this directory. */
    val downloadRoot: Path,
    /** ABDM keeps its own part/download state here. */
    val dataFolder: Path,
    val defaultConnections: Int = 8,
    val useSparseFileAllocation: Boolean = true,
    val progressIntervalMs: Long = 500,
    val userAgent: String = "ABDM-Server/0.1",
)

/**
 * The extra operations an ABDM backed engine exposes beyond [dev.abdm.server.engine.api.DownloadEngine].
 *
 * Declared here (and not in `engine-abdm/src/abdm`) so the rest of the application
 * can refer to it without ever naming an upstream class: the default build has no
 * ABDM classes at all.
 */
interface AbdmEngineBridge {
    /** Loads upstream state from disk; returns the ids that should be scheduled again. */
    suspend fun boot(): List<String>

    /** Pushes server settings (thread count, speed limit) into upstream. */
    fun applySettings(settings: dev.abdm.server.engine.api.ServerSettings)
}
