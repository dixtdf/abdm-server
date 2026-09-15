package dev.abdm.server.scheduler

import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineEvent
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Concurrency and ordering policy.
 *
 * The engine knows how to download; the scheduler decides *when*. The queue lives
 * here (and is mirrored into SQLite so the order survives a restart), which keeps
 * `engine-api` free of policy.
 */
class DownloadScheduler(
    private val engine: DownloadEngine,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> ServerSettings,
    private val queueStore: QueuePersistence? = null,
) {

    /** Implemented by the persistence module; kept as a lambda-friendly port. */
    interface QueuePersistence {
        suspend fun save(ids: List<String>)
        suspend fun load(): List<String>
    }

    private val log = LoggerFactory.getLogger(DownloadScheduler::class.java)
    private val mutex = Mutex()
    private val waiting = ArrayList<String>()
    private var watcher: Job? = null

    fun start() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            engine.events().collect { event -> onEngineEvent(event) }
        }
    }

    suspend fun stop() {
        watcher?.cancel()
        watcher = null
    }

    /** Restores the persisted queue and starts as many tasks as the setting allows. */
    suspend fun restore() {
        val stored = queueStore?.load().orEmpty()
        mutex.withLock {
            waiting.clear()
            waiting.addAll(stored.filter { id -> engine.get(id) != null })
        }
        if (waiting.isNotEmpty()) {
            log.info("restored queue: {}", waiting)
        }
        pump()
    }

    /** A task wants to run (new download, resume, or "resume all" after boot). */
    suspend fun submit(id: String) {
        val snapshot = engine.get(id) ?: return
        if (snapshot.state == DownloadState.COMPLETED) return
        val canRun = mutex.withLock {
            if (waiting.contains(id)) return@withLock false
            val active = activeCount()
            if (active < settingsProvider().maxConcurrentDownloads.coerceAtLeast(1)) {
                true
            } else {
                waiting.add(id)
                persistQueue()
                false
            }
        }
        if (canRun) {
            engine.start(id)
        } else {
            log.debug("task {} queued (limit reached)", id)
        }
    }

    /** Marks a task as intentionally not scheduled (created with startImmediately = false). */
    suspend fun hold(id: String) {
        mutex.withLock { waiting.remove(id) }
        persistQueue()
    }

    /** Promotes every waiting task to the front (used by "start all"). */
    suspend fun pump() {
        val toStart = ArrayList<String>()
        mutex.withLock {
            val limit = settingsProvider().maxConcurrentDownloads.coerceAtLeast(1)
            while (activeCount() + toStart.size < limit && waiting.isNotEmpty()) {
                toStart.add(waiting.removeAt(0))
            }
            persistQueue()
        }
        toStart.forEach { engine.start(it) }
    }

    /** Queue positions as exposed in `Task.queuePosition` (1 based). */
    suspend fun positions(): Map<String, Int> = mutex.withLock {
        waiting.mapIndexed { index, id -> id to index + 1 }.toMap()
    }

    suspend fun waitingIds(): List<String> = mutex.withLock { waiting.toList() }

    /** Re-evaluates the queue, e.g. after `maxConcurrentDownloads` changed. */
    suspend fun refresh() = pump()

    private suspend fun onEngineEvent(event: EngineEvent) {
        val finished = when (event) {
            is EngineEvent.StateChanged -> event.task.state.isTerminal || event.task.state == DownloadState.PAUSED
            is EngineEvent.Removed -> true
            else -> false
        }
        if (finished) pump()
    }

    /** Derived from the engine so it can never drift out of sync. */
    private suspend fun activeCount(): Int = engine.list().count { it.state.isActive }

    private suspend fun persistQueue() {
        queueStore?.save(waiting.toList())
    }
}
