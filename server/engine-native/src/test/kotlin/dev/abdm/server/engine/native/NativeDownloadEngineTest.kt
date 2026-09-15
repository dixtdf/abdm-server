package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.Connections
import dev.abdm.server.engine.api.CreateDownloadRequest
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.ServerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End to end behaviour of the built-in engine against a real (local) HTTP server.
 *
 * These tests are the executable form of the acceptance list in the plan:
 * segmented download, live connection changes, pause/resume and restart recovery
 * all have to produce a byte exact file.
 */
class NativeDownloadEngineTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
        scopes.add(it)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun engine(root: Path, repository: InMemoryTaskRepository, scope: CoroutineScope) =
        NativeDownloadEngine(
            config = NativeEngineConfig(
                downloadRoot = root,
                defaultConnections = 8,
                progressIntervalMs = 100,
                checkpointIntervalMs = 100,
            ),
            scope = scope,
            repository = repository,
            settingsProvider = { InMemoryTaskRepository.settingsFor(root.toString()) },
        )

    @Test
    fun `segmented download with live connection changes is byte exact`() = runBlocking {
        val payload = Random(11).nextBytes(24 * 1024 * 1024)
        LocalFileServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-segmented")
            val scope = newScope()
            val engine = engine(root, InMemoryTaskRepository(), scope)
            val collector = EventCollector(engine, scope)

            val id = engine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 8),
            )
            val created = engine.get(id)!!
            assertEquals(payload.size.toLong(), created.total)
            assertTrue(created.supportsRange, "the test server advertises Range support")

            engine.start(id)
            // 8 -> 32 -> 64 -> 16 while the transfer is running
            engine.setConnections(id, 32)
            waitUntil { engine.get(id)!!.downloaded > 0 }
            engine.setConnections(id, 64)
            engine.setConnections(id, Connections.MAX)
            waitUntil { (engine.get(id)?.downloaded ?: 0) > payload.size / 2 }
            engine.setConnections(id, 16)

            waitUntil(timeoutMillis = 180_000) { engine.get(id)?.state?.isTerminal == true }
            val finished = engine.get(id)!!
            assertEquals(DownloadState.COMPLETED, finished.state, "download failed with ${finished.error}")
            assertEquals(16, finished.connections)
            assertEquals(payload.size.toLong(), finished.downloaded)
            assertContentEquals(payload, Files.readAllBytes(Path.of(finished.path)))
            assertTrue(collector.progressCount(id) > 0, "progress events must be emitted")
            assertTrue(collector.states(id).contains(DownloadState.COMPLETED))
            assertEquals(listOf(32, 64, 256, 16), collector.connectionChanges(id))
            engine.shutdown()
            collector.close()
        }
    }

    @Test
    fun `pause keeps the bytes and resume finishes the download`() = runBlocking {
        val payload = Random(12).nextBytes(24 * 1024 * 1024)
        LocalFileServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-pause")
            val scope = newScope()
            val repository = InMemoryTaskRepository()
            val engine = engine(root, repository, scope)

            val id = engine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 4),
            )
            engine.start(id)
            waitUntil { (engine.get(id)?.downloaded ?: 0) > payload.size / 4 }
            engine.pause(id)
            val paused = engine.get(id)!!
            assertEquals(DownloadState.PAUSED, paused.state)
            val checkpoint = paused.downloaded
            assertTrue(checkpoint > 0, "partial bytes must be checkpointed")
            // nothing moves while paused
            Thread.sleep(300)
            assertEquals(checkpoint, engine.get(id)!!.downloaded)
            assertTrue(repository.rangesOf(id).isNotEmpty(), "ranges must survive for a resume")

            engine.resume(id)
            waitUntil(timeoutMillis = 180_000) { engine.get(id)?.state?.isTerminal == true }
            assertEquals(DownloadState.COMPLETED, engine.get(id)!!.state, "error=${engine.get(id)!!.error}")
            assertContentEquals(payload, Files.readAllBytes(Path.of(engine.get(id)!!.path)))
            engine.shutdown()
        }
    }

    @Test
    fun `a restart resumes from the sqlite checkpoint instead of starting over`() = runBlocking {
        val payload = Random(13).nextBytes(24 * 1024 * 1024)
        LocalFileServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-restart")
            val repository = InMemoryTaskRepository()

            val firstScope = newScope()
            val firstEngine = engine(root, repository, firstScope)
            val id = firstEngine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 4),
            )
            firstEngine.start(id)
            waitUntil { (firstEngine.get(id)?.downloaded ?: 0) > payload.size / 4 }
            firstEngine.pause(id)
            val beforeRestart = firstEngine.get(id)!!.downloaded
            firstEngine.shutdown()
            firstScope.cancel()

            // "docker restart": a brand new engine over the same checkpoint
            val secondScope = newScope()
            val secondEngine = engine(root, repository, secondScope)
            val resumable = secondEngine.restore()
            assertTrue(id in resumable, "an unfinished task must be reported as resumable")
            val restored = secondEngine.get(id)!!
            assertEquals(beforeRestart, restored.downloaded, "checkpointed bytes must be restored")
            assertEquals(DownloadState.PAUSED, restored.state)

            secondEngine.start(id)
            waitUntil(timeoutMillis = 180_000) { secondEngine.get(id)?.state?.isTerminal == true }
            assertEquals(DownloadState.COMPLETED, secondEngine.get(id)!!.state, "error=${secondEngine.get(id)!!.error}")
            assertContentEquals(payload, Files.readAllBytes(Path.of(secondEngine.get(id)!!.path)))
            secondEngine.shutdown()
        }
    }

    @Test
    fun `servers without range support fall back to a single connection`() = runBlocking {
        val payload = Random(14).nextBytes(3 * 1024 * 1024)
        LocalFileServer(payload, supportRange = false).use { server ->
            val root = Files.createTempDirectory("abdm-norange")
            val scope = newScope()
            val engine = engine(root, InMemoryTaskRepository(), scope)
            val id = engine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 32),
            )
            engine.start(id)
            waitUntil(timeoutMillis = 120_000) { engine.get(id)?.state?.isTerminal == true }
            assertEquals(DownloadState.COMPLETED, engine.get(id)!!.state, "error=${engine.get(id)!!.error}")
            assertContentEquals(payload, Files.readAllBytes(Path.of(engine.get(id)!!.path)))
            engine.shutdown()
        }
    }

    @Test
    fun `checksum mismatch fails the download`() = runBlocking {
        val payload = Random(15).nextBytes(1024 * 1024)
        LocalFileServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-checksum")
            val scope = newScope()
            val engine = engine(root, InMemoryTaskRepository(), scope)
            val id = engine.create(
                CreateDownloadRequest(
                    url = server.url,
                    folder = root.toString(),
                    connections = 2,
                    checksum = "sha256:" + "0".repeat(64),
                ),
            )
            engine.start(id)
            waitUntil(timeoutMillis = 60_000) { engine.get(id)?.state?.isTerminal == true }
            assertEquals("CHECKSUM_MISMATCH", engine.get(id)!!.error?.code)
            engine.shutdown()
        }
    }

    @Test
    fun `connection count is validated and clamped to 1-256`() = runBlocking {
        val payload = Random(16).nextBytes(2 * 1024 * 1024)
        LocalFileServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-connections")
            val scope = newScope()
            val engine = engine(root, InMemoryTaskRepository(), scope)
            val id = engine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 512),
            )
            assertEquals(Connections.MAX, engine.get(id)!!.connections, "512 is clamped to 256")
            val error = runCatching { engine.setConnections(id, 0) }.exceptionOrNull()
            assertEquals("INVALID_CONNECTION_COUNT", (error as? dev.abdm.server.engine.api.EngineException)?.error?.code)
            engine.remove(id, deleteFile = true)
            assertNull(engine.get(id))
            engine.shutdown()
        }
    }

    @Test
    fun `downloads outside the configured root are rejected`() = runBlocking {
        val root = Files.createTempDirectory("abdm-traversal")
        val scope = newScope()
        val engine = engine(root, InMemoryTaskRepository(), scope)
        val error = runCatching {
            engine.create(
                CreateDownloadRequest(
                    url = "https://example.com/file.bin",
                    folder = root.resolve("../../etc").toString(),
                ),
            )
        }.exceptionOrNull()
        assertEquals(
            "PATH_OUTSIDE_DOWNLOAD_ROOT",
            (error as? dev.abdm.server.engine.api.EngineException)?.error?.code,
        )
        engine.shutdown()
    }

    @Test
    fun `settings control the default connection count`() = runBlocking {
        val root = Files.createTempDirectory("abdm-settings")
        val scope = newScope()
        val repository = InMemoryTaskRepository()
        var settings: ServerSettings = InMemoryTaskRepository.settingsFor(root.toString(), connections = 3)
        val engine = NativeDownloadEngine(
            config = NativeEngineConfig(downloadRoot = root, defaultConnections = 8, progressIntervalMs = 100),
            scope = scope,
            repository = repository,
            settingsProvider = { settings },
        )
        val payload = Random(17).nextBytes(1024 * 1024)
        LocalFileServer(payload).use { server ->
            val id = engine.create(CreateDownloadRequest(url = server.url, folder = root.toString()))
            assertEquals(3, engine.get(id)!!.connections)
            engine.remove(id, deleteFile = true)
        }
        engine.shutdown()
    }
}
