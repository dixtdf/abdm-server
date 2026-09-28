package dev.abdm.server.engine.abdm

import dev.abdm.server.engine.api.Connections
import dev.abdm.server.engine.api.CreateDownloadRequest
import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineCapability
import dev.abdm.server.engine.api.EngineDescriptor
import dev.abdm.server.engine.api.EngineError
import dev.abdm.server.engine.api.EngineErrorCode
import dev.abdm.server.engine.api.EngineEvent
import dev.abdm.server.engine.api.EngineException
import dev.abdm.server.engine.api.PathGuard
import dev.abdm.server.engine.api.PartProgress
import dev.abdm.server.engine.api.PartState
import dev.abdm.server.engine.api.PersistedTask
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskProgress
import dev.abdm.server.engine.api.TaskRepository
import dev.abdm.server.engine.api.TaskSnapshot
import ir.amirab.downloader.DownloadManager
import ir.amirab.downloader.DownloadSettings
import ir.amirab.downloader.DownloaderRegistry
import ir.amirab.downloader.NewDownloadItemProps
import ir.amirab.downloader.connection.HttpDownloaderClient
import ir.amirab.downloader.connection.OkHttpHttpDownloaderClient
import ir.amirab.downloader.connection.UserAgentProvider
import ir.amirab.downloader.connection.proxy.AutoConfigurableProxyProvider
import ir.amirab.downloader.connection.proxy.NoopSystemProxySelectorProvider
import ir.amirab.downloader.connection.proxy.ProxyStrategy
import ir.amirab.downloader.connection.proxy.ProxyStrategyProvider
import ir.amirab.downloader.connection.response.headers.extractFileNameFromContentDisposition
import ir.amirab.downloader.db.DownloadListFileStorage
import ir.amirab.downloader.db.IDownloadListDb
import ir.amirab.downloader.db.IDownloadPartListDb
import ir.amirab.downloader.db.PartListFileStorage
import ir.amirab.downloader.db.TransactionalFileSaver
import ir.amirab.downloader.downloaditem.DownloadJob
import ir.amirab.downloader.downloaditem.DownloadJobStatus
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.EmptyContext
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.downloaditem.IDownloadItem
import ir.amirab.downloader.downloaditem.contexts.RemovedBy
import ir.amirab.downloader.downloaditem.contexts.ResumedBy
import ir.amirab.downloader.downloaditem.contexts.StoppedBy
import ir.amirab.downloader.downloaditem.contexts.User
import ir.amirab.downloader.downloaditem.hls.HLSDownloadItem
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import ir.amirab.downloader.downloaditem.http.HttpDownloadItem
import ir.amirab.downloader.downloaditem.http.HttpDownloadJob
import ir.amirab.downloader.downloaditem.http.HttpDownloader
import ir.amirab.downloader.part.PartDownloadStatus
import ir.amirab.downloader.part.RangedParts
import ir.amirab.downloader.utils.EmptyFileCreator
import ir.amirab.downloader.utils.IDiskStat
import ir.amirab.downloader.utils.OnDuplicateStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Adapter over the AB Download Manager `downloader:core` engine.
 *
 * This is the **only** place in the project that knows ABDM types. It is a thin
 * translation layer:
 *
 * ```
 * DownloadEngine (ours)          ABDM downloader:core
 *   create()            ->       DownloadManager.addDownload()
 *   start()             ->       DownloadManager.startJob()
 *   pause()             ->       DownloadManager.stopJob()
 *   remove()            ->       DownloadManager.deleteDownload()
 *   setConnections()    ->       DownloadManager.updateDownloadItem { preferredConnectionCount }
 *   events()            <-       polling DownloadJob.status / getDownloadedSize()
 * ```
 *
 * Upstream is consumed as a git submodule and is never modified; a future ABDM
 * 2.x only requires touching this file (and `AbdmCompatibilityTest`).
 */
class AbdmDownloadEngine(
    private val config: AbdmEngineConfig,
    private val scope: CoroutineScope,
    private val repository: TaskRepository? = null,
    private val settingsProvider: () -> ServerSettings = { ServerSettings() },
) : DownloadEngine, AbdmEngineBridge {

    override val name: String = AbdmEngineInfo.ENGINE_NAME

    override val capabilities: Set<EngineCapability> = AbdmEngineInfo.CAPABILITIES

    override val descriptor: EngineDescriptor = EngineDescriptor(
        name = AbdmEngineInfo.ENGINE_NAME,
        version = AbdmEngineInfo.ENGINE_VERSION,
        upstreamName = AbdmEngineInfo.UPSTREAM_NAME,
        upstreamVersion = AbdmEngineInfo.UPSTREAM_VERSION,
        upstreamCommit = AbdmEngineInfo.UPSTREAM_COMMIT,
        capabilities = capabilities,
    )

    private val log = LoggerFactory.getLogger(AbdmDownloadEngine::class.java)
    private val _events = MutableSharedFlow<EngineEvent>(
        replay = 0,
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // ---------------------------------------------------------------- upstream

    private val storageJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            polymorphic(IDownloadItem::class) {
                subclass(HttpDownloadItem::class, HttpDownloadItem.serializer())
                defaultDeserializer { HttpDownloadItem.serializer() }
            }
            polymorphic(IDownloadCredentials::class) {
                subclass(HttpDownloadCredentials::class, HttpDownloadCredentials.serializer())
                defaultDeserializer { HttpDownloadCredentials.serializer() }
            }
        }
    }

    private val fileSaver = TransactionalFileSaver(storageJson)

    // Upstream expects its storage folders to exist (its own desktop app creates them
    // through a registry). Creating them here keeps the submodule untouched.
    private val downloadsFolder: File =
        File(config.dataFolder.toFile(), "downloads").apply { mkdirs() }
    private val partsFolder: File =
        File(config.dataFolder.toFile(), "parts").apply { mkdirs() }

    private val listDb: IDownloadListDb = DownloadListFileStorage(downloadsFolder, fileSaver)
    private val partDb: IDownloadPartListDb = PartListFileStorage(partsFolder, fileSaver)

    private val dlSettings = DownloadSettings(
        defaultThreadCount = Connections.coerce(config.defaultConnections),
        dynamicPartCreationMode = true,
        useSparseFileAllocation = config.useSparseFileAllocation,
        maxDownloadRetryCount = 3,
    )

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloaderClient: HttpDownloaderClient = OkHttpHttpDownloaderClient(
        okHttpClient = httpClient,
        customUserAgentProvider = object : UserAgentProvider {
            override fun getUserAgent(): String = config.userAgent
        },
        proxyStrategyProvider = object : ProxyStrategyProvider {
            override fun getProxyStrategyFor(url: String): ProxyStrategy = ProxyStrategy.Direct
        },
        systemProxySelectorProvider = NoopSystemProxySelectorProvider(),
        autoConfigurableProxyProvider = AutoConfigurableProxyProvider.NoOp(),
    )

    private val registry = DownloaderRegistry().apply {
        add(HttpDownloader(lazy { downloaderClient }))
    }

    private val diskStat: IDiskStat = object : IDiskStat {
        override fun getRemainingSpace(path: File): Long = path.usableSpace
    }

    private val manager = DownloadManager(
        dlListDb = listDb,
        partListDb = partDb,
        settings = dlSettings,
        emptyFileCreator = EmptyFileCreator(diskStat) { config.useSparseFileAllocation },
        downloaderRegistry = registry,
        downloadDataFolder = config.dataFolder.toFile(),
    )

    private val trackers = ConcurrentHashMap<Long, RateTracker>()
    private val partTrackers = ConcurrentHashMap<String, RateTracker>()
    private val published = ConcurrentHashMap<Long, DownloadState>()
    private var ticker: Job? = null

    override fun events(): SharedFlow<EngineEvent> = _events.asSharedFlow()

    // ------------------------------------------------------------------ engine

    /** Loads upstream state from disk. @return ids that should be scheduled again. */
    override suspend fun boot(): List<String> {
        Files.createDirectories(config.dataFolder)
        manager.boot()
        manager.awaitBoot()
        applySettings(settingsProvider())
        startTicker()
        val items = manager.getDownloadList()
        items.forEach { syncRepository(it) }
        val resumable = items
            .filter { it.status != DownloadStatus.Completed }
            .map { it.id.toString() }
        log.info("ABDM engine ready: {} task(s), {} resumable", items.size, resumable.size)
        return resumable
    }

    override suspend fun create(request: CreateDownloadRequest): String {
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
        val uri = validateUri(request.url)
        val credentials = HttpDownloadCredentials(
            link = uri.toString(),
            headers = request.headers.takeIf { it.isNotEmpty() },
            downloadPage = request.referer,
            userAgent = request.userAgent,
        )

        var name = request.fileName?.takeIf { it.isNotBlank() }
        var contentLength = -1L
        if (name == null) {
            val probe = runCatching { downloaderClient.head(credentials, null, null) }.getOrNull()
            contentLength = probe?.totalLength ?: -1L
            name = probe
                ?.responseHeaders
                ?.get("content-disposition")
                ?.let { extractFileNameFromContentDisposition(it) }
                ?: uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "download.bin"
        }

        val connections = Connections.coerce(request.connections ?: settings.defaultConnections)
        val item = HttpDownloadItem.createWithCredentials(
            credentials = credentials,
            id = 0,
            folder = folder.toString(),
            name = name,
            contentLength = contentLength,
            preferredConnectionCount = connections,
            speedLimit = request.speedLimit,
            fileChecksum = request.checksum,
            status = DownloadStatus.Added,
        )

        val id = try {
            manager.addDownload(
                NewDownloadItemProps(
                    downloadItem = item,
                    extraConfig = null,
                    onDuplicateStrategy = if (request.overwrite) {
                        OnDuplicateStrategy.OverrideDownload
                    } else {
                        OnDuplicateStrategy.AddNumbered
                    },
                    context = EmptyContext,
                ),
            )
        } catch (e: Exception) {
            throw EngineException(
                EngineErrorCode.INTERNAL,
                emptyMap(),
                "${e::class.simpleName}: ${e.message}",
                e,
            )
        }

        val stored = manager.getDownloadList().find { it.id == id }
            ?: throw EngineException(EngineErrorCode.TASK_NOT_FOUND, mapOf("id" to id.toString()))
        trackers[id] = RateTracker()
        syncRepository(stored)
        emit(EngineEvent.Added(snapshotOf(stored)))
        log.info("ABDM task {} created ({} -> {})", id, stored.link, stored.name)
        return id.toString()
    }

    override suspend fun start(id: String) {
        val numeric = requireId(id)
        if (manager.getDownloadList().find { it.id == numeric }?.status == DownloadStatus.Completed) return
        manager.startJob(numeric, ResumedBy(User))
    }

    override suspend fun pause(id: String) {
        manager.stopJob(requireId(id), StoppedBy(User))
        publishState(requireId(id))
    }

    override suspend fun resume(id: String) {
        start(id)
    }

    override suspend fun remove(id: String, deleteFile: Boolean) {
        val numeric = requireId(id)
        manager.deleteDownload(numeric, { deleteFile }, RemovedBy(User))
        trackers.remove(numeric)
        published.remove(numeric)
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
        val numeric = requireId(id)
        val target = Connections.coerce(connections)
        manager.updateDownloadItem(numeric, null) { item ->
            item.preferredConnectionCount = target
        }
        val item = manager.getDownloadList().find { it.id == numeric }
        if (item != null) {
            syncRepository(item)
            val snapshot = snapshotOf(item)
            emit(EngineEvent.ConnectionsChanged(snapshot, target, snapshot.activeConnections))
        }
    }

    override suspend fun get(id: String): TaskSnapshot? {
        val numeric = id.toLongOrNull() ?: return null
        val item = manager.getDownloadList().find { it.id == numeric } ?: return null
        return snapshotOf(item)
    }

    override suspend fun list(): List<TaskSnapshot> =
        manager.getDownloadList().sortedBy { it.dateAdded }.map { snapshotOf(it) }

    override suspend fun shutdown() {
        ticker?.cancel()
        manager.downloadJobs.forEach { job ->
            runCatching { manager.stopJob(job.id, StoppedBy(User)) }
        }
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
    }

    /** Applies server settings to upstream. Called at boot and after a settings change. */
    override fun applySettings(settings: ServerSettings) {
        dlSettings.defaultThreadCount = Connections.coerce(settings.defaultConnections)
        manager.limitGlobalSpeed(settings.globalSpeedLimit)
        manager.reloadSetting()
    }

    // ------------------------------------------------------------------ internal

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(config.progressIntervalMs.coerceIn(100, 5_000))
                runCatching { publish() }
                    .onFailure { log.debug("progress publish failed: {}", it.message) }
            }
        }
    }

    private suspend fun publish() {
        for (item in manager.getDownloadList()) {
            val snapshot = snapshotOf(item)
            val previous = published[item.id]
            if (previous != snapshot.state) {
                published[item.id] = snapshot.state
                emit(EngineEvent.StateChanged(snapshot, previous ?: DownloadState.QUEUED, snapshot.error))
                syncRepository(item)
            } else if (snapshot.state.isActive) {
                emit(EngineEvent.Progress(snapshot.toProgress()))
            }
        }
    }

    private suspend fun publishState(id: Long) {
        val item = manager.getDownloadList().find { it.id == id } ?: return
        val snapshot = snapshotOf(item)
        val previous = published[id]
        published[id] = snapshot.state
        emit(EngineEvent.StateChanged(snapshot, previous ?: DownloadState.QUEUED, snapshot.error))
        syncRepository(item)
    }

    private suspend fun snapshotOf(item: IDownloadItem): TaskSnapshot {
        val job = manager.downloadJobs.find { it.id == item.id }
        val state = mapState(item, job)
        var downloaded = job?.getDownloadedSize() ?: 0L
        if (downloaded <= 0 && state == DownloadState.COMPLETED) {
            // Upstream drops the job once the file is finished, so fall back to the item.
            downloaded = item.contentLength.takeIf { it > 0 } ?: 0L
        }
        val parts = upstreamParts(item, state)
        val tracker = trackers.computeIfAbsent(item.id) { RateTracker() }
        tracker.update(downloaded)
        val speed = if (state.isActive) tracker.rate() else 0L
        val connections = item.preferredConnectionCount ?: dlSettings.defaultThreadCount
        val total = item.contentLength
        val eta = if (total > 0 && speed > 0) (total - downloaded).coerceAtLeast(0) / speed else -1L
        val started = item.startTime
        val average = if (downloaded > 0 && started != null) {
            val now = item.completeTime ?: System.currentTimeMillis()
            downloaded * 1000 / (now - started).coerceAtLeast(1)
        } else {
            0L
        }
        return TaskSnapshot(
            id = item.id.toString(),
            url = item.link,
            fileName = item.name,
            folder = item.folder,
            path = runCatching { manager.calculateOutputFile(item).absolutePath }.getOrDefault(""),
            state = state,
            downloaded = downloaded,
            total = total,
            speed = speed,
            averageSpeed = average,
            connections = connections,
            // Upstream does not expose the number of live part connections; report the
            // requested count while the transfer is running and 0 otherwise.
            activeConnections = if (state == DownloadState.DOWNLOADING) connections else 0,
            parts = parts,
            etaSeconds = eta,
            supportsRange = (job as? HttpDownloadJob)?.supportsConcurrent ?: false,
            hls = item is HLSDownloadItem,
            speedLimit = item.speedLimit,
            checksum = item.fileChecksum,
            error = job?.status?.value?.let { status ->
                (status as? DownloadJobStatus.Canceled)?.e?.let { throwable ->
                    EngineError(EngineErrorCode.NETWORK, emptyMap(), throwable.message)
                }
            },
            createdAt = item.dateAdded,
            startedAt = item.startTime,
            completedAt = item.completeTime,
            queuePosition = null,
        )
    }

    /**
     * Reads the *real* upstream part table for a task.
     *
     * AB Download Manager persists its parts (`RangedParts`) through `IDownloadPartListDb`,
     * so the adapter can report exactly the rows the desktop client shows - same ranges,
     * same status, same downloaded/total pair - without touching upstream internals.
     */
    private suspend fun upstreamParts(item: IDownloadItem, state: DownloadState): List<PartProgress> {
        val stored = runCatching { manager.partListDb.getParts(item.id) }.getOrNull()
        val ranged = stored as? RangedParts ?: return emptyList()
        return ranged.list.mapIndexed { position, part ->
            val downloaded = part.howMuchProceed()
            val total = part.partLength ?: 0L
            val partState = when {
                part.isCompleted -> PartState.DONE
                state == DownloadState.PAUSED -> PartState.IDLE
                state == DownloadState.FAILED -> PartState.FAILED
                part.statusFlow.value is PartDownloadStatus.ReceivingData -> PartState.DOWNLOADING
                part.statusFlow.value is PartDownloadStatus.Connecting -> PartState.CONNECTING
                part.statusFlow.value is PartDownloadStatus.Canceled -> PartState.FAILED
                else -> PartState.IDLE
            }
            val tracker = partTrackers.computeIfAbsent("${item.id}:${position}") { RateTracker() }
            tracker.update(downloaded)
            PartProgress(
                index = position + 1,
                state = partState,
                downloaded = downloaded,
                total = total,
                speed = if (partState == PartState.DOWNLOADING) tracker.rate() else 0,
                rangeStart = part.from,
            )
        }
    }

    private fun TaskSnapshot.toProgress(): TaskProgress = TaskProgress(
        id = id,
        downloaded = downloaded,
        total = total,
        speed = speed,
        averageSpeed = averageSpeed,
        connections = connections,
        activeConnections = activeConnections,
        etaSeconds = etaSeconds,
        state = state,
    )

    private fun mapState(item: IDownloadItem, job: DownloadJob?): DownloadState {
        val status = job?.status?.value
        return when {
            item.status == DownloadStatus.Completed || status is DownloadJobStatus.Finished -> DownloadState.COMPLETED
            item.status == DownloadStatus.Error -> DownloadState.FAILED
            status is DownloadJobStatus.Downloading -> DownloadState.DOWNLOADING
            status is DownloadJobStatus.Resuming -> DownloadState.CONNECTING
            status is DownloadJobStatus.Retrying -> DownloadState.CONNECTING
            status is DownloadJobStatus.PreparingFile -> DownloadState.COMPLETING
            status is DownloadJobStatus.Canceled ->
                if (item.status == DownloadStatus.Paused) DownloadState.PAUSED else DownloadState.FAILED
            item.status == DownloadStatus.Paused -> DownloadState.PAUSED
            item.status == DownloadStatus.Added -> DownloadState.QUEUED
            else -> DownloadState.QUEUED
        }
    }

    private suspend fun syncRepository(item: IDownloadItem) {
        val repository = repository ?: return
        val snapshot = snapshotOf(item)
        repository.upsert(
            PersistedTask(
                id = snapshot.id,
                url = snapshot.url,
                fileName = snapshot.fileName,
                folder = snapshot.folder,
                state = snapshot.state,
                total = snapshot.total,
                downloaded = snapshot.downloaded,
                connections = snapshot.connections,
                supportsRange = snapshot.supportsRange,
                hls = snapshot.hls,
                speedLimit = snapshot.speedLimit,
                checksum = snapshot.checksum,
                error = snapshot.error,
                createdAt = snapshot.createdAt,
                startedAt = snapshot.startedAt,
                completedAt = snapshot.completedAt,
                queuePosition = null,
                completedRanges = emptyList(),
                sequence = item.id,
            ),
        )
    }

    private suspend fun requireId(id: String): Long {
        val numeric = id.toLongOrNull()
        if (numeric == null || manager.getDownloadList().none { it.id == numeric }) {
            throw EngineException(EngineErrorCode.TASK_NOT_FOUND, mapOf("id" to id))
        }
        return numeric
    }

    private fun emit(event: EngineEvent) {
        _events.tryEmit(event)
    }

    private fun validateUri(url: String): URI {
        val uri = runCatching { URI(url.trim()) }.getOrElse {
            throw EngineException(EngineErrorCode.INVALID_URL, mapOf("url" to url), it.message, it)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || uri.host == null) {
            throw EngineException(EngineErrorCode.INVALID_URL, mapOf("url" to url))
        }
        if (scheme != "http" && scheme != "https") {
            throw EngineException(EngineErrorCode.UNSUPPORTED_SCHEME, mapOf("scheme" to scheme, "url" to url))
        }
        return uri
    }
}

/** Rolling throughput estimator used for the adapter's speed/ETA fields. */
internal class RateTracker(private val windowMillis: Long = 1_500) {
    private class Sample(val at: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private val startedAt = System.currentTimeMillis()
    private var lastRate = 0L
    private var lastTotal = 0L

    @Synchronized
    fun update(downloaded: Long) {
        lastTotal = downloaded
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, downloaded))
        while (samples.size > 2 && now - samples.first().at > windowMillis) {
            samples.removeFirst()
        }
        val first = samples.first()
        val span = now - first.at
        if (span < 100) return
        val delta = downloaded - first.bytes
        lastRate = if (delta <= 0) 0 else delta * 1000 / span
    }

    @Synchronized
    fun rate(): Long = lastRate

    /** Bytes/second since this tracker was created. */
    @Synchronized
    fun average(): Long {
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
        return if (lastTotal <= 0) 0 else lastTotal * 1000 / elapsed
    }
}
