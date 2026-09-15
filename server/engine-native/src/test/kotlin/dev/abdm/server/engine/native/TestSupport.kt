package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineEvent
import dev.abdm.server.engine.api.HistoryEntry
import dev.abdm.server.engine.api.PersistedTask
import dev.abdm.server.engine.api.RangeCodec
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory stand-in for the SQLite repository.
 *
 * It mirrors the real semantics that matter for recovery: whatever was
 * checkpointed survives a "restart" (a new engine instance over the same map).
 */
class InMemoryTaskRepository : TaskRepository {

    private val tasks = ConcurrentHashMap<String, PersistedTask>()
    private val history = ArrayList<HistoryEntry>()

    override suspend fun loadAll(): List<PersistedTask> = tasks.values.sortedBy { it.sequence }

    override suspend fun upsert(task: PersistedTask) {
        tasks[task.id] = task
    }

    override suspend fun updateProgress(id: String, downloaded: Long, completedRanges: List<LongRange>) {
        tasks[id]?.let { current ->
            tasks[id] = current.copy(downloaded = downloaded, completedRanges = completedRanges)
        }
    }

    override suspend fun delete(id: String) {
        tasks.remove(id)
    }

    override suspend fun nextSequence(): Long = (tasks.values.maxOfOrNull { it.sequence } ?: 0) + 1

    override suspend fun appendHistory(entry: HistoryEntry) {
        history.add(entry)
    }

    override suspend fun history(limit: Int): List<HistoryEntry> = history.takeLast(limit)

    override suspend fun clearHistory() {
        history.clear()
    }

    /** Simulates what the process would find on disk after a crash. */
    fun checkpointed(id: String): PersistedTask? = tasks[id]

    fun rangesOf(id: String): List<LongRange> = tasks[id]?.completedRanges.orEmpty()

    companion object {
        fun settingsFor(folder: String, connections: Int = 8) = ServerSettings(
            downloadRoot = folder,
            defaultFolder = folder,
            defaultConnections = connections,
            progressIntervalMs = 100,
        )
    }
}

suspend fun waitUntil(timeoutMillis: Long = 60_000, condition: suspend () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(50)
    }
    throw AssertionError("condition not met within ${timeoutMillis}ms")
}

/** Collects engine events in the background so tests can assert on them. */
class EventCollector(engine: dev.abdm.server.engine.api.DownloadEngine, scope: kotlinx.coroutines.CoroutineScope) :
    AutoCloseable {

    private val events = java.util.Collections.synchronizedList(mutableListOf<EngineEvent>())

    private val job: kotlinx.coroutines.Job = scope.launch {
        engine.events().collect { events.add(it) }
    }

    fun all(): List<EngineEvent> = synchronized(events) { events.toList() }

    fun progressCount(taskId: String): Int = all().count {
        it is EngineEvent.Progress && it.taskId == taskId
    }

    fun states(taskId: String): List<DownloadState> = all().filterIsInstance<EngineEvent.StateChanged>()
        .filter { it.taskId == taskId }
        .map { it.task.state }

    fun connectionChanges(taskId: String): List<Int> = all()
        .filterIsInstance<EngineEvent.ConnectionsChanged>()
        .filter { it.taskId == taskId }
        .map { it.requested }

    override fun close() {
        job.cancel()
    }
}

/** Encodes long ranges the same way the SQLite column does (used in assertions). */
fun encodeRanges(ranges: List<LongRange>): String = RangeCodec.encode(ranges)
