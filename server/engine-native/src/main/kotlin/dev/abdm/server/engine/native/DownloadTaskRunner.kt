package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.Connections
import dev.abdm.server.engine.api.CreateDownloadRequest
import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineError
import dev.abdm.server.engine.api.EngineErrorCode
import dev.abdm.server.engine.api.EngineEvent
import dev.abdm.server.engine.api.EngineException
import dev.abdm.server.engine.api.HistoryEntry
import dev.abdm.server.engine.api.PathGuard
import dev.abdm.server.engine.api.PersistedTask
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskProgress
import dev.abdm.server.engine.api.TaskRepository
import dev.abdm.server.engine.api.TaskSnapshot
import dev.abdm.server.engine.api.toEngineError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * One download task.
 *
 * Progress is modelled as [IntervalSet] (the byte ranges already written to disk),
 * workers pull fixed size chunks from a channel. Consequences:
 *
 *  * `setConnections` only adds/removes workers - the download is never interrupted.
 *  * `pause` just cancels the workers; the next `resume` recomputes the missing
 *    intervals from disk, which is byte exact.
 *  * a restart rebuilds the same state from the SQLite checkpoint.
 */
internal class DownloadTaskRunner(
    val id: String,
    internal val engine: NativeDownloadEngine,
    var request: CreateDownloadRequest,
    var fileName: String,
    var folder: String,
) {
    private val log = LoggerFactory.getLogger(DownloadTaskRunner::class.java)

    val intervals = IntervalSet()
    val meter = SpeedMeter()

    @Volatile
    var state: DownloadState = DownloadState.QUEUED
        private set

    @Volatile
    var total: Long = -1

    @Volatile
    var supportsRange: Boolean = false

    @Volatile
    var hls: Boolean = false

    @Volatile
    var connections: Int = request.normalizedConnections()

    @Volatile
    var error: EngineError? = null

    @Volatile
    var sequence: Long = 0

    val createdAt: Long = System.currentTimeMillis()

    @Volatile
    var startedAt: Long? = null

    @Volatile
    var completedAt: Long? = null

    @Volatile
    var computedChecksum: String? = null

    private val activeConnections = AtomicInteger(0)
    private val lock = Any()

    private var supervisor: Job? = null
    private var progressJob: Job? = null
    private var queue: WorkQueue? = null
    private val workers = mutableListOf<Job>()
    private var fatalError: EngineError? = null

    val path: Path get() = Path.of(folder, fileName)

    val isRunning: Boolean get() = supervisor?.isActive == true

    /** Number of chunks that are queued or in flight but not written yet. */
    val pendingChunks: Int get() = queue?.pendingCount ?: 0

    /** Human readable internals, used by `DiagnoseTest` and bug reports. */
    fun diagnose(): String {
        val live = synchronized(lock) { workers.count { !it.isCompleted } }
        return "state=$state downloaded=${intervals.total()}/$total connections=$connections " +
            "active=${activeConnections.get()} liveWorkers=$live queue=[${queue?.snapshot()}] " +
            "running=$isRunning ticker=${progressJob?.isActive} error=$error"
    }

    // ------------------------------------------------------------------ views

    fun snapshot(): TaskSnapshot {
        val downloaded = intervals.total()
        val running = state.isActive
        val speed = if (running) meter.current() else 0
        val average = if (running) meter.average() else averageOfFinished()
        val eta = if (running && total > 0 && average > 0) {
            ((total - downloaded).coerceAtLeast(0)) / average
        } else {
            -1
        }
        return TaskSnapshot(
            id = id,
            url = request.url,
            fileName = fileName,
            folder = folder,
            path = path.toAbsolutePath().toString(),
            state = state,
            downloaded = downloaded,
            total = total,
            speed = speed,
            averageSpeed = average,
            connections = connections,
            activeConnections = activeConnections.get(),
            etaSeconds = eta,
            supportsRange = supportsRange,
            hls = hls,
            speedLimit = request.speedLimit,
            checksum = request.checksum,
            error = error,
            createdAt = createdAt,
            startedAt = startedAt,
            completedAt = completedAt,
            queuePosition = null,
        )
    }

    fun progress(): TaskProgress {
        val snapshot = snapshot()
        return TaskProgress(
            id = id,
            downloaded = snapshot.downloaded,
            total = snapshot.total,
            speed = snapshot.speed,
            averageSpeed = snapshot.averageSpeed,
            connections = snapshot.connections,
            activeConnections = snapshot.activeConnections,
            etaSeconds = snapshot.etaSeconds,
            state = snapshot.state,
        )
    }

    fun toPersisted(): PersistedTask = PersistedTask(
        id = id,
        url = request.url,
        fileName = fileName,
        folder = folder,
        state = state,
        total = total,
        downloaded = intervals.total(),
        connections = connections,
        supportsRange = supportsRange,
        hls = hls,
        speedLimit = request.speedLimit,
        checksum = request.checksum,
        error = error,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
        queuePosition = null,
        completedRanges = intervals.snapshot(),
        headers = request.headers,
        cookies = request.cookies,
        referer = request.referer,
        userAgent = request.userAgent,
        proxy = request.proxy,
        sequence = sequence,
    )

    fun restoreFrom(persisted: PersistedTask) {
        total = persisted.total
        supportsRange = persisted.supportsRange
        hls = persisted.hls
        connections = Connections.coerce(persisted.connections)
        request = request.copy(
            fileName = persisted.fileName,
            folder = persisted.folder,
            connections = persisted.connections,
            headers = persisted.headers,
            cookies = persisted.cookies,
            referer = persisted.referer,
            userAgent = persisted.userAgent,
            proxy = persisted.proxy,
            speedLimit = persisted.speedLimit,
            checksum = persisted.checksum,
        )
        intervals.clear()
        persisted.completedRanges.forEach { intervals.add(it.first, it.last + 1) }
        meter.onBytes(intervals.total())
        error = persisted.error
        sequence = persisted.sequence
        startedAt = persisted.startedAt
        completedAt = persisted.completedAt
        state = when {
            persisted.state == DownloadState.COMPLETED -> DownloadState.COMPLETED
            persisted.state == DownloadState.FAILED -> DownloadState.FAILED
            intervals.total() > 0 -> DownloadState.PAUSED
            else -> DownloadState.QUEUED
        }
    }

    private fun averageOfFinished(): Long {
        val started = startedAt ?: return 0
        val ended = completedAt ?: return 0
        val span = (ended - started).coerceAtLeast(1)
        val downloaded = intervals.total()
        if (downloaded <= 0) return 0
        return downloaded * 1000 / span
    }

    // ------------------------------------------------------------------ control

    fun start() {
        synchronized(lock) {
            if (state == DownloadState.COMPLETED || isRunning) return
            fatalError = null
            error = null
            changeState(if (intervals.total() > 0 && supportsRange) DownloadState.RECOVERING else DownloadState.CONNECTING)
            supervisor = engine.scope.launch { runTask() }
        }
    }

    suspend fun pause() {
        val job = synchronized(lock) {
            if (!isRunning) return
            changeState(DownloadState.PAUSING)
            supervisor
        }
        job?.cancelAndJoin()
        stopProgressTicker()
        if (state == DownloadState.PAUSING || state.isActive) {
            changeState(DownloadState.PAUSED)
        }
        checkpoint()
    }

    suspend fun remove(deleteFile: Boolean) {
        val job = synchronized(lock) {
            supervisor.also { supervisor = null }
        }
        job?.cancelAndJoin()
        stopProgressTicker()
        synchronized(lock) {
            workers.clear()
            queue = null
        }
        if (deleteFile) {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    /** Live reconfiguration: never interrupts the transfer. */
    fun changeConnections(value: Int) {
        val target = Connections.coerce(value)
        val previous = connections
        connections = target
        request = request.copy(connections = target)
        if (!isRunning) {
            engine.emit(EngineEvent.ConnectionsChanged(snapshot(), target, 0))
            checkpoint()
            return
        }
        if (!supportsRange || hls) {
            engine.emit(EngineEvent.ConnectionsChanged(snapshot(), target, activeConnections.get()))
            return
        }
        var current = 0
        synchronized(lock) {
            val current = workers.count { !it.isCompleted }
            when {
                target > current -> repeat(target - current) { addWorker() }
                target < current -> {
                    val removable = workers.filter { !it.isCompleted }.take(current - target)
                    removable.forEach { it.cancel() }
                    workers.removeAll(removable.toSet())
                }
            }
        }
        log.info("task {} connections {} -> {} (previous requested {})", id, current, target, previous)
        engine.emit(EngineEvent.ConnectionsChanged(snapshot(), target, activeConnections.get()))
    }

    // ------------------------------------------------------------------ internals

    private fun changeState(next: DownloadState) {
        val previous = state
        if (previous == next) return
        state = next
        engine.emit(EngineEvent.StateChanged(snapshot(), previous, error))
        checkpoint()
    }

    private suspend fun runTask() {
        startedAt = startedAt ?: System.currentTimeMillis()
        completedAt = null
        val job = coroutineContext[Job]
        try {
            if (hls) {
                runHls()
            } else {
                probeIfNeeded()
                prepareDestination()
                if (supportsRange && total > 0) runSegmented() else runSequential()
            }
            changeState(DownloadState.COMPLETING)
            verifyChecksum()
            completedAt = System.currentTimeMillis()
            intervals.clear()
            total = if (total > 0) total else Files.size(path)
            intervals.add(0, total)
            state = DownloadState.COMPLETED
            engine.emit(EngineEvent.StateChanged(snapshot(), DownloadState.COMPLETING, null))
            engine.repository?.appendHistory(
                HistoryEntry(
                    id = id,
                    url = request.url,
                    fileName = fileName,
                    folder = folder,
                    size = total,
                    checksum = computedChecksum ?: request.checksum,
                    outcome = "COMPLETED",
                    finishedAt = completedAt ?: System.currentTimeMillis(),
                ),
            )
            checkpoint()
        } catch (cancelled: CancellationException) {
            // pause()/remove() requested, or a fatal worker error cancelled us
            val failure = fatalError
            if (failure != null) {
                error = failure
                state = DownloadState.FAILED
                engine.emit(EngineEvent.StateChanged(snapshot(), DownloadState.DOWNLOADING, failure))
            } else if (state.isActive) {
                state = DownloadState.PAUSED
                engine.emit(EngineEvent.StateChanged(snapshot(), DownloadState.PAUSING, null))
            } else if (state == DownloadState.PAUSING) {
                state = DownloadState.PAUSED
                engine.emit(EngineEvent.StateChanged(snapshot(), DownloadState.PAUSING, null))
            }
            checkpoint()
            throw cancelled
        } catch (e: Throwable) {
            val engineError = if (e is EngineException) e.error else e.toEngineError()
            error = engineError
            state = DownloadState.FAILED
            engine.emit(EngineEvent.StateChanged(snapshot(), DownloadState.DOWNLOADING, engineError))
            checkpoint()
            log.warn("download {} failed: {}", id, engineError)
        } finally {
            try {
                job?.ensureActive()
            } catch (_: CancellationException) {
                closeChannel()
            }
            if (state.isTerminal || state == DownloadState.PAUSED) {
                stopProgressTicker()
                closeChannel()
            }
        }
    }

    private fun closeChannel() {
        synchronized(lock) {
            queue = null
            workers.clear()
        }
    }

    private suspend fun probeIfNeeded() {
        if (total > 0) return
        val probe = engine.awaitProbe(request.url, request)
        total = probe.contentLength
        supportsRange = probe.supportsRange
        probe.fileName?.let { suggested ->
            if (request.fileName.isNullOrBlank() && Files.notExists(path)) {
                fileName = suggested
            }
        }
    }

    private fun prepareDestination() {
        val root = engine.config.downloadRoot
        val target = PathGuard.resolveWithin(root, Path.of(folder, fileName).toString())
        folder = target.parent.toAbsolutePath().toString()
        fileName = target.fileName.toString()
        Files.createDirectories(target.parent)
        if (Files.notExists(target)) {
            Files.newByteChannel(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { }
        }
        if (!supportsRange && Files.exists(target)) {
            // Without range support a resume is impossible: start over.
            Files.newByteChannel(target, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { }
        }
    }

    /** Parallel chunk download. */
    private suspend fun runSegmented() {
        val planner = ChunkPlanner(total, rangeSupport = true)
        val chunks = planner.plan(intervals.missing(total))
        val workQueue = WorkQueue(chunks)
        synchronized(lock) {
            queue = workQueue
            workers.clear()
        }
        startProgressTicker()
        changeState(if (intervals.total() > 0) DownloadState.RECOVERING else DownloadState.CONNECTING)
        val effective = ChunkPlanner.recommendedConnections(total, connections, true)
        if (effective != connections) {
            connections = effective
        }
        synchronized(lock) { repeat(connections) { addWorker() } }
        // The ranged requests are on the wire now: CONNECTING/RECOVERING -> DOWNLOADING.
        changeState(DownloadState.DOWNLOADING)
        awaitWorkers()
        if (fatalError != null) throw CancellationException("worker failure")
    }

    /** Single connection, required when the server ignores `Range`. */
    private suspend fun runSequential() {
        if (intervals.total() > 0) {
            intervals.clear()
        }
        val end = if (total > 0) total else Long.MAX_VALUE
        val workQueue = WorkQueue(listOf(0L until end))
        synchronized(lock) {
            queue = workQueue
            workers.clear()
        }
        startProgressTicker()
        changeState(DownloadState.DOWNLOADING)
        synchronized(lock) { addWorker(sequential = true) }
        awaitWorkers()
        if (fatalError != null) throw CancellationException("worker failure")
    }

    private fun addWorker(sequential: Boolean = false) {
        val scope = engine.scope
        val parent = supervisor ?: return
        val job = scope.launch(parent) {
            worker(sequential)
        }
        workers.add(job)
    }

    private suspend fun awaitWorkers() {
        while (true) {
            val live = synchronized(lock) { workers.filter { !it.isCompleted } }
            if (live.isEmpty()) return
            live.joinAll()
        }
    }

    private suspend fun worker(sequential: Boolean) {
        val workQueue = queue ?: return
        while (coroutineContext.isActive) {
            when (val take = workQueue.take()) {
                is WorkQueue.Take.Chunk -> {
                    var handedBack = false
                    var attempt = 0
                    try {
                        while (true) {
                            try {
                                activeConnections.incrementAndGet()
                                try {
                                    downloadChunk(take.range, sequential)
                                } finally {
                                    activeConnections.decrementAndGet()
                                }
                                intervals.add(take.range.first, take.range.last + 1)
                                checkpointProgress()
                                break
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                attempt++
                                if (attempt > engine.config.maxRetriesPerChunk) {
                                    fatalError = if (e is EngineException) e.error else e.toEngineError()
                                    log.warn("chunk {} failed permanently: {}", take.range, fatalError)
                                    return
                                }
                                delay(200L * attempt)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        // Pause or fewer connections: hand the chunk back so the bytes
                        // are fetched later instead of being silently lost.
                        workQueue.requeue(take.range)
                        handedBack = true
                        throw cancelled
                    } finally {
                        if (!handedBack) workQueue.complete()
                    }
                }

                WorkQueue.Take.Wait -> delay(20)
                WorkQueue.Take.Done -> return
            }
        }
    }

    private suspend fun downloadChunk(chunk: LongRange, sequential: Boolean) {
        val client = engine.client(request)
        val requestRange = if (sequential) null else chunk
        val response = engine.http.openRange(client, engine.uri(request.url), request, requestRange)
        if (!sequential && response.statusCode() == 200) {
            // Server silently dropped range support mid transfer.
            supportsRange = false
            throw EngineException(
                EngineErrorCode.SERVER_NO_RANGE_SUPPORT,
                mapOf("url" to request.url),
                "server answered 200 to a ranged request",
            )
        }
        if (!sequential && response.statusCode() != 206) {
            throw EngineException(
                EngineErrorCode.NETWORK,
                mapOf("url" to request.url, "status" to response.statusCode().toString()),
                "unexpected status ${response.statusCode()}",
            )
        }
        val body = response.body()
        RandomAccessFile(path.toFile(), "rw").use { file ->
            file.seek(if (sequential) intervals.total() else chunk.first)
            val buffer = ByteArray(engine.config.chunkBufferSize)
            var written = 0L
            body.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    // Throttle the stream itself, not the chunk: the limit has to hold
                    // while bytes move, otherwise N connections each buffer a whole
                    // chunk before respecting it.
                    engine.throttle.acquire(id, request.speedLimit, read.toLong())
                    file.write(buffer, 0, read)
                    written += read
                    // Report throughput as bytes move, not per chunk: a throttled
                    // download must still show a live speed and ETA.
                    meter.onBytes(read.toLong())
                    if (sequential) {
                        intervals.add(0, file.filePointer)
                    }
                }
            }
            if (!sequential && written != chunk.len()) {
                throw EngineException(
                    EngineErrorCode.NETWORK,
                    mapOf("url" to request.url),
                    "short read: expected ${chunk.len()} got $written",
                )
            }
            if (sequential && total <= 0) {
                total = file.filePointer
            }
        }
    }

    private suspend fun runHls() {
        startProgressTicker()
        changeState(DownloadState.CONNECTING)
        engine.hlsDownloader.download(this)
    }

    private fun verifyChecksum() {
        val expected = request.checksum?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        computedChecksum = actual
        val normalized = expected.substringAfter("sha256:", expected).lowercase()
        if (normalized != actual) {
            throw EngineException(
                EngineErrorCode.CHECKSUM_MISMATCH,
                mapOf("expected" to normalized, "actual" to actual),
                "checksum mismatch",
            )
        }
    }

    // ------------------------------------------------------------------ tickers

    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return
        progressJob = engine.scope.launch {
            while (isActive) {
                delay(engine.progressIntervalMs())
                if (!state.isActive) continue
                engine.emit(EngineEvent.Progress(progress()))
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private var lastCheckpoint = 0L
    private var lastCheckpointBytes = 0L

    private fun checkpointProgress() {
        val now = System.currentTimeMillis()
        val downloaded = intervals.total()
        if (now - lastCheckpoint < engine.config.checkpointIntervalMs) return
        if (downloaded == lastCheckpointBytes) return
        lastCheckpoint = now
        lastCheckpointBytes = downloaded
        checkpoint()
    }

    private fun checkpoint() {
        val repository: TaskRepository = engine.repository ?: return
        val task = toPersisted()
        engine.scope.launch {
            runCatching { repository.upsert(task) }
                .onFailure { log.debug("checkpoint failed for {}: {}", id, it.message) }
        }
    }

    fun recomputeTotals() {
        if (total > 0) {
            intervals.add(0, minOf(intervals.total(), total))
        }
    }

    /** Used by the HLS downloader to publish a provisional total size. */
    fun applyTotal(value: Long) {
        total = value
    }

    fun addBytes(offset: Long, length: Long, byteCount: Long) {
        intervals.add(offset, offset + length)
        meter.onBytes(byteCount)
    }

    fun beginSegments(count: Int) {
        hls = true
        engine.emit(EngineEvent.StateChanged(snapshot(), DownloadState.CONNECTING, null))
    }

    fun markActiveSegments(delta: Int) {
        activeConnections.addAndGet(delta)
    }

    fun failWith(error: EngineError) {
        fatalError = error
        this.error = error
        state = DownloadState.FAILED
        engine.emit(EngineEvent.StateChanged(snapshot(), DownloadState.DOWNLOADING, error))
    }

    fun finishHls() {
        changeState(DownloadState.COMPLETING)
        state = DownloadState.COMPLETED
        completedAt = System.currentTimeMillis()
        engine.emit(EngineEvent.StateChanged(snapshot(), DownloadState.COMPLETING, null))
        checkpoint()
    }

    private fun LongRange.len(): Long = last - first + 1
}
