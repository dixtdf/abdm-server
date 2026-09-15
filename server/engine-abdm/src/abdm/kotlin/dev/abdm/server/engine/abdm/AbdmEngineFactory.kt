package dev.abdm.server.engine.abdm

import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.EngineDescriptor
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Compiled when `-Pabdm.enabled=true`: the adapter runs against the real
 * AB Download Manager classes exported to `third_party/abdm-dist`.
 */
object AbdmEngineFactory {

    fun isAvailable(): Boolean = true

    fun unavailableReason(): String = ""

    fun descriptor(): EngineDescriptor = EngineDescriptor(
        name = AbdmEngineInfo.ENGINE_NAME,
        version = AbdmEngineInfo.ENGINE_VERSION,
        upstreamName = AbdmEngineInfo.UPSTREAM_NAME,
        upstreamVersion = AbdmEngineInfo.UPSTREAM_VERSION,
        upstreamCommit = AbdmEngineInfo.UPSTREAM_SHORT_COMMIT,
        capabilities = AbdmEngineInfo.CAPABILITIES,
    )

    fun create(
        config: AbdmEngineConfig,
        scope: CoroutineScope,
        repository: TaskRepository? = null,
        settingsProvider: () -> ServerSettings = { ServerSettings() },
    ): DownloadEngine = AbdmDownloadEngine(
        config = config,
        scope = scope,
        repository = repository,
        settingsProvider = settingsProvider,
    )
}
