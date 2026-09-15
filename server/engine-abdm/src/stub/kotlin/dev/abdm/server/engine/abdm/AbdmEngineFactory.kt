package dev.abdm.server.engine.abdm

import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.EngineUnavailableException
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Compiled when `-Pabdm.enabled=false` (the default).
 *
 * The build stays completely free of AB Download Manager / Android SDK
 * requirements; asking for the ABDM engine fails with a clear, actionable error
 * instead of a `NoClassDefFoundError` at runtime.
 */
object AbdmEngineFactory {

    private const val REASON =
        "This build does not include the AB Download Manager bridge. Build it with " +
            "`scripts/build-abdm-bridge.sh` and start the server with -Pabdm.enabled=true " +
            "(or run the published abdm-enabled image)."

    fun isAvailable(): Boolean = false

    fun unavailableReason(): String = REASON

    fun descriptor() = dev.abdm.server.engine.api.EngineDescriptor(
        name = AbdmEngineInfo.ENGINE_NAME,
        version = "unavailable",
        upstreamName = AbdmEngineInfo.UPSTREAM_NAME,
        upstreamVersion = AbdmEngineInfo.UPSTREAM_VERSION,
        upstreamCommit = AbdmEngineInfo.UPSTREAM_SHORT_COMMIT,
        capabilities = emptySet(),
    )

    fun create(
        config: AbdmEngineConfig,
        scope: CoroutineScope,
        repository: TaskRepository? = null,
        settingsProvider: () -> ServerSettings = { ServerSettings() },
    ): DownloadEngine = throw EngineUnavailableException(AbdmEngineInfo.ENGINE_NAME, REASON)
}
