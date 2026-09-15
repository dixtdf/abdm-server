package dev.abdm.server.engine.abdm

import dev.abdm.server.engine.api.Connections
import dev.abdm.server.engine.api.CreateDownloadRequest
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineCapability
import dev.abdm.server.engine.api.HistoryEntry
import dev.abdm.server.engine.api.PersistedTask
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compatibility test against the pinned upstream release.
 *
 * This is the "canary" of the whole integration: it exercises exactly the upstream
 * capabilities the project depends on - create, pause, resume, remove, Range
 * downloads and *live connection count changes* - through the real
 * AB Download Manager engine (no stubs).
 *
 * If upstream changes any of those signatures, this module stops compiling and the
 * `abdm-compat` CI job fails immediately (see `docs/upstream.md`).
 */
class AbdmCompatibilityTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
        scopes.add(it)
    }

    private fun engine(root: Path, data: Path, scope: CoroutineScope): AbdmDownloadEngine =
        AbdmDownloadEngine(
            config = AbdmEngineConfig(
                downloadRoot = root,
                dataFolder = data,
                defaultConnections = 4,
                progressIntervalMs = 200,
            ),
            scope = scope,
            repository = NoopRepository,
            settingsProvider = {
                ServerSettings(
                    downloadRoot = root.toString(),
                    defaultFolder = root.toString(),
                    defaultConnections = 4,
                )
            },
        )

    @Test
    fun `the adapter is linked against the pinned upstream`() {
        assertTrue(AbdmEngineFactory.isAvailable(), "the bridge must be compiled in")
        assertEquals("1.10.4", AbdmEngineInfo.UPSTREAM_VERSION)
        assertEquals("afc57634b3c121c6415213242b2b600cccc6fd6e", AbdmEngineInfo.UPSTREAM_COMMIT)
        val descriptor = AbdmEngineFactory.descriptor()
        assertEquals("abdm", descriptor.name)
        assertEquals("AB Download Manager", descriptor.upstreamName)
        assertEquals(AbdmEngineInfo.UPSTREAM_VERSION, descriptor.upstreamVersion)
        assertEquals("afc5763", descriptor.upstreamCommit)
        assertTrue(EngineCapability.DYNAMIC_CONNECTIONS in descriptor.capabilities)
    }

    @Test
    fun `create pause resume remove and live connection changes work end to end`() = runBlocking {
        val payload = Random(101).nextBytes(8 * 1024 * 1024)
        LocalRangeServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-compat-out")
            val data = Files.createTempDirectory("abdm-compat-data")
            val scope = newScope()
            val engine = engine(root, data, scope)
            try {
                engine.boot()
                val id = engine.create(
                    CreateDownloadRequest(
                        url = server.url,
                        folder = root.toString(),
                        connections = Connections.MIN,
                        startImmediately = false,
                    ),
                )
                assertNotNull(engine.get(id), "the task must be listed right after creation")

                engine.start(id)
                waitDownloading(engine, id)
                // live reconfiguration, without pausing the transfer
                engine.setConnections(id, 64)
                assertEquals(64, engine.get(id)?.connections)
                engine.setConnections(id, 8)
                assertEquals(8, engine.get(id)?.connections)

                waitUntil(180_000) { engine.get(id)?.state?.isTerminal == true }
                val snapshot = engine.get(id)!!
                assertEquals(DownloadState.COMPLETED, snapshot.state, "download failed: ${snapshot.error}")
                assertEquals(payload.size.toLong(), snapshot.downloaded)
                assertTrue(Files.exists(Path.of(snapshot.path)), "output file at ${snapshot.path}")
                assertContentEquals(payload, Files.readAllBytes(Path.of(snapshot.path)))

                // The web UI shows the same part table the desktop client shows, so the
                // adapter must expose upstream's own range rows.
                assertTrue(snapshot.parts.isNotEmpty(), "the upstream part table must be exposed")
                assertTrue(
                    snapshot.parts.all { it.total > 0 },
                    "every row needs the size of its range: ${snapshot.parts}",
                )

                engine.remove(id, deleteFile = true)
                assertEquals(null, engine.get(id))
            } finally {
                engine.shutdown()
            }
        }
    }

    @Test
    fun `pause keeps partial progress and resume finishes byte exact`() = runBlocking {
        val payload = Random(102).nextBytes(16 * 1024 * 1024)
        LocalRangeServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-compat-pause")
            val data = Files.createTempDirectory("abdm-compat-pause-data")
            val scope = newScope()
            val engine = engine(root, data, scope)
            try {
                engine.boot()
                val id = engine.create(
                    CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 8),
                )
                engine.start(id)
                waitDownloading(engine, id)
                engine.pause(id)
                waitUntil(30_000) { engine.get(id)?.state == DownloadState.PAUSED }
                val paused = engine.get(id)!!
                assertEquals(DownloadState.PAUSED, paused.state)
                assertTrue((paused.downloaded) > 0, "partial progress must survive a pause")
                assertTrue(
                    paused.parts.isNotEmpty(),
                    "a running upstream download must report its range rows: ${paused.parts}",
                )

                engine.resume(id)
                waitUntil(180_000) { engine.get(id)?.state?.isTerminal == true }
                val snapshot = engine.get(id)!!
                assertEquals(DownloadState.COMPLETED, snapshot.state, "resume failed: ${snapshot.error}")
                assertContentEquals(payload, Files.readAllBytes(Path.of(snapshot.path)))
                engine.remove(id, deleteFile = true)
            } finally {
                engine.shutdown()
            }
        }
    }

    @Test
    fun `upstream resumes across an engine restart`() = runBlocking {
        val payload = Random(103).nextBytes(16 * 1024 * 1024)
        LocalRangeServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-compat-restart")
            val data = Files.createTempDirectory("abdm-compat-restart-data")

            val firstScope = newScope()
            val firstEngine = engine(root, data, firstScope)
            val id = firstEngine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 4),
            )
            firstEngine.start(id)
            waitDownloading(firstEngine, id)
            firstEngine.pause(id)
            waitUntil(30_000) { firstEngine.get(id)?.state == DownloadState.PAUSED }
            val before = firstEngine.get(id)!!.downloaded
            firstEngine.shutdown()
            firstScope.cancel()

            // "docker restart": a fresh engine over the same upstream data folder
            val secondScope = newScope()
            val secondEngine = engine(root, data, secondScope)
            try {
                val resumable = secondEngine.boot()
                assertTrue(id in resumable, "upstream state must survive the restart: $resumable")
                val restored = secondEngine.get(id)!!
                assertTrue(
                    restored.downloaded >= before,
                    "checkpointed progress must be restored (was $before, now ${restored.downloaded})",
                )

                secondEngine.resume(id)
                waitUntil(180_000) { secondEngine.get(id)?.state?.isTerminal == true }
                val snapshot = secondEngine.get(id)!!
                assertEquals(DownloadState.COMPLETED, snapshot.state, "restart resume failed: ${snapshot.error}")
                assertContentEquals(payload, Files.readAllBytes(Path.of(snapshot.path)))
            } finally {
                secondEngine.shutdown()
            }
        }
    }

    private suspend fun waitStarted(engine: AbdmDownloadEngine, id: String) {
        waitUntil(120_000) {
            val snapshot = engine.get(id)
            snapshot != null && (snapshot.downloaded > 0 || snapshot.state.isTerminal)
        }
        val snapshot = engine.get(id)!!
        assertTrue(
            snapshot.downloaded > 0,
            "the engine never reported progress: state=${snapshot.state} error=${snapshot.error} total=${snapshot.total}",
        )
    }

    /** Waits until the transfer is actually mid flight (the local server is throttled,
     *  so this is deterministic instead of a race). */
    private suspend fun waitDownloading(engine: AbdmDownloadEngine, id: String) {
        waitUntil(120_000) {
            val snapshot = engine.get(id)
            snapshot != null && snapshot.state == DownloadState.DOWNLOADING && snapshot.downloaded > 0
        }
    }

    private suspend fun waitUntil(timeoutMillis: Long, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(200)
        }
        throw AssertionError("condition not met within ${timeoutMillis}ms")
    }

    private object NoopRepository : TaskRepository {
        override suspend fun loadAll(): List<PersistedTask> = emptyList()
        override suspend fun upsert(task: PersistedTask) = Unit
        override suspend fun updateProgress(id: String, downloaded: Long, completedRanges: List<LongRange>) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun nextSequence(): Long = 1
        override suspend fun appendHistory(entry: HistoryEntry) = Unit
        override suspend fun history(limit: Int): List<HistoryEntry> = emptyList()
        override suspend fun clearHistory() = Unit
    }
}
