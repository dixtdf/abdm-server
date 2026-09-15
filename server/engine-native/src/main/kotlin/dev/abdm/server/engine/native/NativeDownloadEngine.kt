package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.Connections
import dev.abdm.server.engine.api.CreateDownloadRequest
import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineCapability
import dev.abdm.server.engine.api.EngineError
import dev.abdm.server.engine.api.EngineErrorCode
import dev.abdm.server.engine.api.EngineEvent
import dev.abdm.server.engine.api.EngineException
import dev.abdm.server.engine.api.PathGuard
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskRepository
import dev.abdm.server.engine.api.TaskSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in download engine.
 *
 * It is the default engine and the reference implementation of [DownloadEngine]:
 * segmented HTTP/HTTPS with Range requests, byte exact resume, live connection
 * changes between 1 and 256, HLS, checksum verification and speed limits.
 */
class NativeDownloadEngine(
    val config: NativeEngineConfig,
    val scope: CoroutineScope,
    val repository: TaskRepository? = null,
    private val settingsProvider: () -> ServerSettings = { ServerSettings() },
) : DownloadEngine {

    private val log = LoggerFactory.getLogger(NativeDownloadEngine::class.java)

    override val name: String = "native"

    override val capabilities: Set<EngineCapability> = setOf(
        EngineCapability.HTTP_RANGE,
        EngineCapability.HLS,
        EngineCapability.RESUME,
        EngineCapability.DYNAMIC_CONNECTIONS,
        EngineCapability.SPEED_LIMIT,
        EngineCapability.CHECKSUM,
        EngineCapability.PROXY,
        EngineCapability.CUSTOM_HEADERS,
    )

    val http = HttpSupport(config.userAgent)
    val throttle = ThrottleController()
    internal val hlsDownloader = HlsDownloader(this)

    private val _events = MutableSharedFlow<EngineEvent>(
        replay = 0,
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val tasks = ConcurrentHashMap<String, DownloadTaskRunner>()
    private val order = ArrayList<String>()
    private val orderLock = Mutex()
    private val clients = ConcurrentHashMap<String, java.net.http.HttpClient>()

    init {
        throttle.setGlobalLimit(settingsProvider().globalSpeedLimit)
    }

    override fun events(): SharedFlow<EngineEvent> = _events.asSharedFlow()

    internal fun emit(event: EngineEvent) {
        _events.tryEmit(event)
    }

    internal fun progressIntervalMs(): Long = settingsProvider().progressIntervalMs.coerceIn(100, 5_000)

    internal fun client(request: CreateDownloadRequest): java.net.http.HttpClient =
        clients.computeIfAbsent(request.proxy.orEmpty()) { http.client(request) }

    internal fun uri(url: String): URI = validateUri(url)

    internal suspend fun awaitProbe(url: String, request: CreateDownloadRequest): ProbeResult =
        http.probe(validateUri(url), request)

    // ------------------------------------------------------------------ engine

    override suspend fun create(request: CreateDownloadRequest): String {
        val uri = validateUri(request.url)
        val settings = settingsProvider()
        val root = PathGuard.resolveRoot(settings.downloadRoot)
        val folderInput = request.folder?.takeIf { it.isNotBlank() } ?: settings.defaultFolder
        val folder = PathGuard.resolveWithin(root, folderInput)
        if (!Files.isDirectory(folder)) {
            throw EngineException(
                EngineErrorCode.DOWNLOAD_DIRECTORY_NOT_FOUND,
                mapOf("path" to folder.toString()),
            )
        }

        val probe = try {
            http.probe(uri, request)
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            throw EngineException(EngineErrorCode.INTERNAL, emptyMap(), e.message, e)
        }

        val fileName = (request.fileName?.takeIf { it.isNotBlank() }?.let { FileNameResolver.sanitize(it) }
            ?: probe.fileName
            ?: FileNameResolver.fromUrl(request.url))

        val target = PathGuard.resolveFileWithin(root, folder.toString(), fileName)
        if (Files.exists(target) && !request.overwrite) {
            val clash = tasks.values.any {
                it.fileName == fileName && it.folder == folder.toString() && !it.state.isTerminal
            }
            if (clash) {
                throw EngineException(
                    EngineErrorCode.TASK_ALREADY_EXISTS,
                    mapOf("fileName" to fileName, "folder" to folder.toString()),
                )
            }
        }
        if (!probe.supportsRange && !probe.isHls) {
            log.info("{} does not advertise Range support, falling back to a single connection", uri)
        }

        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val effectiveRequest = request.copy(
            url = uri.toString(),
            fileName = fileName,
            folder = folder.toString(),
            connections = Connections.coerce(request.connections ?: settings.defaultConnections),
            hls = request.hls || probe.isHls,
        )
        val runner = DownloadTaskRunner(
            id = id,
            engine = this,
            request = effectiveRequest,
            fileName = fileName,
            folder = folder.toString(),
        )
        runner.total = probe.contentLength
        runner.supportsRange = probe.supportsRange
        runner.hls = effectiveRequest.hls
        runner.sequence = repository?.nextSequence() ?: (order.size + 1L)
        tasks[id] = runner
        orderLock.withLock { order.add(id) }
        persist(runner)
        emit(EngineEvent.Added(runner.snapshot()))
        log.info(
            "created task {} ({} bytes, range={}, hls={})",
            id, probe.contentLength, probe.supportsRange, effectiveRequest.hls,
        )
        return id
    }

    override suspend fun start(id: String) {
        val runner = require(id)
        if (runner.state == DownloadState.COMPLETED) return
        runner.start()
    }

    override suspend fun pause(id: String) {
        require(id).pause()
    }

    override suspend fun resume(id: String) {
        val runner = require(id)
        runner.start()
    }

    override suspend fun remove(id: String, deleteFile: Boolean) {
        val runner = tasks.remove(id) ?: throw EngineException(
            EngineErrorCode.TASK_NOT_FOUND,
            mapOf("id" to id),
        )
        orderLock.withLock { order.remove(id) }
        runner.remove(deleteFile)
        throttle.forget(id)
        repository?.delete(id)
        emit(EngineEvent.Removed(id, deleteFile))
    }

    override suspend fun setConnections(id: String, connections: Int) {
        if (connections < Connections.MIN || connections > Connections.MAX) {
            throw EngineException(
                EngineErrorCode.INVALID_CONNECTION_COUNT,
                mapOf("min" to Connections.MIN.toString(), "max" to Connections.MAX.toString()),
            )
        }
        require(id).changeConnections(connections)
        persist(require(id))
    }

    override suspend fun get(id: String): TaskSnapshot? = tasks[id]?.snapshot()

    override suspend fun list(): List<TaskSnapshot> {
        val ids = orderLock.withLock { order.toList() }
        return ids.mapNotNull { tasks[it]?.snapshot() }
    }

    override suspend fun shutdown() {
        tasks.values.forEach { runner ->
            runner.pause()
        }
        repository?.let { repo ->
            tasks.values.forEach { runCatching { repo.upsert(it.toPersisted()) } }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Rebuilds in-memory tasks from the SQLite checkpoint. @return ids to schedule. */
    suspend fun restore(): List<String> {
        val repository = repository ?: return emptyList()
        val persisted = repository.loadAll()
        val resumable = ArrayList<String>()
        persisted.sortedBy { it.sequence }.forEach { stored ->
            val runner = DownloadTaskRunner(
                id = stored.id,
                engine = this,
                request = CreateDownloadRequest(url = stored.url, fileName = stored.fileName, folder = stored.folder),
                fileName = stored.fileName,
                folder = stored.folder,
            )
            runner.restoreFrom(stored)
            tasks[stored.id] = runner
            orderLock.withLock { order.add(stored.id) }
            if (stored.state == DownloadState.COMPLETED) {
                runner.total = stored.total
                runner.recomputeTotals()
            } else if (stored.state != DownloadState.CANCELED) {
                resumable.add(stored.id)
            }
        }
        log.info("restored {} task(s) from checkpoint ({} resumable)", persisted.size, resumable.size)
        return resumable
    }

    fun setGlobalSpeedLimit(bytesPerSecond: Long) = throttle.setGlobalLimit(bytesPerSecond)

    /** Marks tasks of a folder that no longer exists as failed instead of silently retrying. */
    fun verifyFolders() {
        tasks.values.forEach { runner ->
            val folder = Path.of(runner.folder)
            if (!Files.isDirectory(folder) && !runner.state.isTerminal) {
                runner.failWith(
                    EngineError(
                        EngineErrorCode.DOWNLOAD_DIRECTORY_NOT_FOUND,
                        mapOf("path" to folder.toString()),
                    ),
                )
            }
        }
    }

    /** Debug view of one task; see `DiagnoseTest`. */
    fun debug(id: String): String = tasks[id]?.diagnose() ?: "task $id not found"

    private suspend fun persist(runner: DownloadTaskRunner) {
        repository?.upsert(runner.toPersisted())
    }

    private fun require(id: String): DownloadTaskRunner = tasks[id] ?: throw EngineException(
        EngineErrorCode.TASK_NOT_FOUND,
        mapOf("id" to id),
    )

    private fun validateUri(url: String): URI {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw EngineException(EngineErrorCode.INVALID_URL, mapOf("url" to url), e.message, e)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || uri.host == null) {
            throw EngineException(EngineErrorCode.INVALID_URL, mapOf("url" to url))
        }
        if (scheme != "http" && scheme != "https") {
            throw EngineException(
                EngineErrorCode.UNSUPPORTED_SCHEME,
                mapOf("scheme" to scheme, "url" to url),
            )
        }
        return uri
    }

    /** Exposed for tests and for the API layer when it needs to resolve a folder. */
    fun resolveFolder(folder: String?): Path {
        val root = PathGuard.resolveRoot(settingsProvider().downloadRoot)
        return PathGuard.resolveWithin(root, folder?.takeIf { it.isNotBlank() } ?: settingsProvider().defaultFolder)
    }

    @Suppress("unused")
    private fun launchCleanup() {
        scope.launch { /* reserved for maintenance jobs */ }
    }
}
