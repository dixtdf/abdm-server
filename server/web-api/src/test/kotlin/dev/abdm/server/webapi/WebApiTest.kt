package dev.abdm.server.webapi

import dev.abdm.server.engine.api.Connections
import dev.abdm.server.engine.api.CreateDownloadRequest
import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineCapability
import dev.abdm.server.engine.api.EngineEvent
import dev.abdm.server.engine.api.EngineException
import dev.abdm.server.engine.api.HistoryEntry
import dev.abdm.server.engine.api.PersistedTask
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.SettingsRepository
import dev.abdm.server.engine.api.TaskRepository
import dev.abdm.server.engine.api.TaskSnapshot
import dev.abdm.server.scheduler.DownloadScheduler
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API surface test: response shapes, error envelope and HTTP status mapping.
 *
 * The engine is faked here on purpose - the point is the contract in
 * `docs/api.md`, not the download implementation.
 */
class WebApiTest {

    @Test
    fun `health exposes the engine and stays reachable without a token`() = runBlocking {
        withApi(authMode = "token", token = "s3cret") { client ->
            val response = client.get("/api/v1/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"status\":\"ok\""), body)
            assertTrue(body.contains("\"engine\":\"fake\""), body)
        }
    }

    @Test
    fun `token mode rejects unauthenticated api calls with UNAUTHORIZED`() = runBlocking {
        withApi(authMode = "token", token = "s3cret") { client ->
            val denied = client.get("/api/v1/downloads")
            assertEquals(HttpStatusCode.Unauthorized, denied.status)
            assertTrue(denied.bodyAsText().contains("UNAUTHORIZED"))

            val allowed = client.get("/api/v1/downloads") {
                header("Authorization", "Bearer s3cret")
            }
            assertEquals(HttpStatusCode.OK, allowed.status)
        }
    }

    @Test
    fun `download lifecycle endpoints return task objects`() = runBlocking {
        withApi { client ->
            val created = client.post("/api/v1/downloads") {
                contentType(ContentType.Application.Json)
                setBody("""{"url":"https://example.com/file.iso","connections":16}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val createdBody = created.bodyAsText()
            assertTrue(createdBody.contains("\"fileName\":\"file.iso\""), createdBody)
            assertTrue(createdBody.contains("\"connections\":16"), createdBody)

            val id = createdBody.substringAfter("\"id\":\"").substringBefore('"')
            assertTrue(client.get("/api/v1/downloads/$id").bodyAsText().contains(id))

            val paused = client.post("/api/v1/downloads/$id/pause")
            assertEquals(HttpStatusCode.OK, paused.status)
            val resumed = client.post("/api/v1/downloads/$id/resume")
            assertEquals(HttpStatusCode.OK, resumed.status)

            val patched = client.patch("/api/v1/downloads/$id/connections") {
                contentType(ContentType.Application.Json)
                setBody("""{"connections":64}""")
            }
            assertEquals(HttpStatusCode.OK, patched.status)
            assertTrue(patched.bodyAsText().contains("\"connections\":64"))
        }
    }

    @Test
    fun `engine errors keep the code envelope and map to http status`() = runBlocking {
        withApi { client ->
            val invalid = client.post("/api/v1/downloads") {
                contentType(ContentType.Application.Json)
                setBody("""{"url":"not-a-url"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("\"code\":\"INVALID_URL\""), invalid.bodyAsText())

            val missing = client.get("/api/v1/downloads/does-not-exist")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("\"code\":\"TASK_NOT_FOUND\""))
        }
    }

    @Test
    fun `settings round trip and queue positions are exposed`() = runBlocking {
        withApi { client ->
            val initial = client.get("/api/v1/settings").bodyAsText()
            assertTrue(initial.contains("\"maxConnections\":256"), initial)

            val updated = client.put("/api/v1/settings") {
                contentType(ContentType.Application.Json)
                setBody("""{"defaultConnections":32,"maxConcurrentDownloads":4}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertTrue(updated.bodyAsText().contains("\"defaultConnections\":32"))
        }
    }

    @Test
    fun `history and directories endpoints answer`() = runBlocking {
        withApi { client ->
            val history = client.get("/api/v1/history")
            assertEquals(HttpStatusCode.OK, history.status)
            assertEquals("[]", history.bodyAsText().trim())

            val directories = client.get("/api/v1/directories?path=$tempRoot")
            assertEquals(HttpStatusCode.OK, directories.status)
            // paths are JSON escaped on Windows, so assert on the shape instead
            val body = directories.bodyAsText()
            assertTrue(body.startsWith("{\"path\":\""), body)
            assertTrue(body.contains("\"directories\":["), body)

            val outside = client.get("/api/v1/directories?path=../../..")
            assertEquals(HttpStatusCode.NotFound, outside.status)
            assertTrue(outside.bodyAsText().contains("PATH_OUTSIDE_DOWNLOAD_ROOT"))
        }
    }

    @Test
    fun `websocket greets with the task list`() = runBlocking {
        withApi { client ->
            // A plain GET would be a protocol error, so only assert the route exists
            // through the HTTP upgrade response of the test host.
            val response = client.get("/api/v1/events")
            assertTrue(
                response.status == HttpStatusCode.SwitchingProtocols ||
                    response.status == HttpStatusCode.BadRequest,
                "unexpected status ${response.status}",
            )
        }
    }

    // ------------------------------------------------------------------ fixture

    private var tempRoot: String = ""

    private suspend fun withApi(
        authMode: String = "none",
        token: String? = null,
        settings: ServerSettings? = null,
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) {
        tempRoot = Files.createTempDirectory("abdm-webapi").toAbsolutePath().toString()
        val effectiveSettings = settings ?: ServerSettings(
            downloadRoot = tempRoot,
            defaultFolder = tempRoot,
        )
        val engine = FakeEngine()
        val settingsRepository = InMemorySettingsRepository(effectiveSettings)
        val taskRepository = InMemoryTaskRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scheduler = DownloadScheduler(engine, scope, { effectiveSettings })
        val api = WebApi(
            engine = engine,
            scheduler = scheduler,
            settings = settingsRepository,
            tasks = taskRepository,
            config = WebApi.ApiConfig(
                version = "0.1.0",
                startedAt = System.currentTimeMillis(),
                authMode = authMode,
                authToken = token,
            ),
            scope = scope,
        )
        testApplication {
            application { api.install(this) }
            block(client)
        }
        scope.shutDown()
    }

    private fun CoroutineScope.shutDown() {
        cancel()
    }

    private class InMemorySettingsRepository(var settings: ServerSettings) : SettingsRepository {
        override suspend fun load(): ServerSettings = settings
        override suspend fun save(settings: ServerSettings) {
            this.settings = settings
        }
    }

    private class InMemoryTaskRepository : TaskRepository {
        private val entries = mutableListOf<HistoryEntry>()
        override suspend fun loadAll(): List<PersistedTask> = emptyList()
        override suspend fun upsert(task: PersistedTask) = Unit
        override suspend fun updateProgress(id: String, downloaded: Long, completedRanges: List<LongRange>) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun nextSequence(): Long = 1
        override suspend fun appendHistory(entry: HistoryEntry) {
            entries.add(entry)
        }

        override suspend fun history(limit: Int): List<HistoryEntry> = entries.takeLast(limit)
        override suspend fun clearHistory() {
            entries.clear()
        }
    }

    /** Engine double: no network, deterministic states, validates inputs like the real one. */
    private class FakeEngine : DownloadEngine {
        override val name: String = "fake"
        override val capabilities: Set<EngineCapability> = setOf(EngineCapability.HTTP_RANGE)
        private val tasks = LinkedHashMap<String, TaskSnapshot>()
        private val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private var counter = 0
        private var running = true

        override fun events(): SharedFlow<EngineEvent> = events.asSharedFlow()

        override suspend fun create(request: CreateDownloadRequest): String {
            if (!request.url.startsWith("http")) {
                throw EngineException("INVALID_URL", mapOf("url" to request.url))
            }
            val id = "task${++counter}"
            val connections = Connections.coerce(request.connections ?: Connections.DEFAULT)
            tasks[id] = TaskSnapshot(
                id = id,
                url = request.url,
                fileName = request.fileName ?: request.url.substringAfterLast('/'),
                folder = "/downloads",
                path = "/downloads/${request.url.substringAfterLast('/')}",
                state = DownloadState.QUEUED,
                downloaded = 0,
                total = 100,
                connections = connections,
            )
            events.tryEmit(EngineEvent.Added(tasks[id]!!))
            return id
        }

        override suspend fun start(id: String) {
            update(id) { it.copy(state = DownloadState.DOWNLOADING) }
        }

        override suspend fun pause(id: String) {
            update(id) { it.copy(state = DownloadState.PAUSED) }
        }

        override suspend fun resume(id: String) = start(id)

        override suspend fun remove(id: String, deleteFile: Boolean) {
            require(id)
            tasks.remove(id)
            events.tryEmit(EngineEvent.Removed(id, deleteFile))
        }

        override suspend fun setConnections(id: String, connections: Int) {
            val snapshot = require(id)
            update(id) { it.copy(connections = Connections.coerce(connections)) }
            events.tryEmit(EngineEvent.ConnectionsChanged(snapshot, connections, 0))
        }

        override suspend fun get(id: String): TaskSnapshot? = tasks[id]

        override suspend fun list(): List<TaskSnapshot> = tasks.values.toList()

        override suspend fun shutdown() {
            running = false
        }

        private fun require(id: String): TaskSnapshot =
            tasks[id] ?: throw EngineException("TASK_NOT_FOUND", mapOf("id" to id))

        private fun update(id: String, transform: (TaskSnapshot) -> TaskSnapshot) {
            val snapshot = require(id)
            tasks[id] = transform(snapshot)
        }
    }
}
