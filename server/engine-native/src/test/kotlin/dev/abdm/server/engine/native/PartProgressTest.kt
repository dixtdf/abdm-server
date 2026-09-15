package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.CreateDownloadRequest
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.PartState
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
import kotlin.test.assertTrue

/**
 * The per connection ("分片") view the web UI renders.
 *
 * The local server is throttled so the download is genuinely in flight while the
 * assertions run: without that, a 16 MiB transfer over loopback finishes before the
 * first observation and the test would only ever see the completed state.
 */
class PartProgressTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
        scopes.add(it)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun engine(root: Path, scope: CoroutineScope) = NativeDownloadEngine(
        config = NativeEngineConfig(downloadRoot = root, progressIntervalMs = 100),
        scope = scope,
        repository = InMemoryTaskRepository(),
        settingsProvider = { InMemoryTaskRepository.settingsFor(root.toString()) },
    )

    @Test
    fun `one row per connection with a real range and total`() = runBlocking {
        val payload = Random(31).nextBytes(16 * 1024 * 1024)
        // ~4 MiB/s: a few seconds of transfer, enough to observe every state
        LocalFileServer(payload, bytesPerSecond = 4L * 1024 * 1024).use { server ->
            val root = Files.createTempDirectory("abdm-parts")
            val scope = newScope()
            val engine = engine(root, scope)

            val id = engine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 4),
            )
            engine.start(id)
            waitUntil(60_000) { (engine.get(id)?.downloaded ?: 0) > 0 && engine.get(id)!!.parts.size == 4 }

            val midFlight = engine.get(id)!!
            assertEquals(4, midFlight.parts.size, "4 connections must produce 4 rows")
            assertEquals(
                payload.size.toLong(),
                midFlight.parts.sumOf { it.total },
                "the part totals must add up to the whole file",
            )
            assertTrue(midFlight.parts.all { it.total > 0 }, "every row needs a size: ${midFlight.parts}")
            assertEquals(
                listOf(1, 2, 3, 4),
                midFlight.parts.map { it.index },
                "rows are numbered in order",
            )
            assertTrue(
                midFlight.parts.any { it.state == PartState.DOWNLOADING || it.state == PartState.CONNECTING },
                "at least one connection must be active: ${midFlight.parts.map { it.state }}",
            )
            assertTrue(
                midFlight.parts.map { it.rangeStart }.zipWithNext().all { (a, b) -> a < b },
                "rows must cover increasing file offsets: ${midFlight.parts.map { it.rangeStart }}",
            )

            waitUntil(120_000) { engine.get(id)?.state?.isTerminal == true }
            val done = engine.get(id)!!
            assertEquals(DownloadState.COMPLETED, done.state, "download failed: ${done.error}")
            assertTrue(done.parts.all { it.state == PartState.DONE }, "rows: ${done.parts.map { it.state }}")
            assertEquals(
                payload.size.toLong(),
                done.parts.sumOf { it.downloaded },
                "the rows must account for every downloaded byte",
            )
            assertContentEquals(payload, Files.readAllBytes(Path.of(done.path)))
            engine.shutdown()
        }
    }

    @Test
    fun `changing the connection count re-partitions without losing the numbers`() = runBlocking {
        val payload = Random(32).nextBytes(32 * 1024 * 1024)
        LocalFileServer(payload, bytesPerSecond = 4L * 1024 * 1024).use { server ->
            val root = Files.createTempDirectory("abdm-parts-repartition")
            val scope = newScope()
            val engine = engine(root, scope)

            val id = engine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 4),
            )
            engine.start(id)
            waitUntil(60_000) { (engine.get(id)?.downloaded ?: 0) > 0 }
            val before = engine.get(id)!!
            val totalsBefore = before.parts.sumOf { it.total }
            val downloadedBefore = before.parts.sumOf { it.downloaded }

            engine.setConnections(id, 8)
            waitUntil(30_000) { engine.get(id)!!.parts.size == 8 }
            val afterGrow = engine.get(id)!!
            assertEquals(8, afterGrow.parts.size)
            assertEquals(totalsBefore, afterGrow.parts.sumOf { it.total }, "splitting must preserve the total")
            assertTrue(
                afterGrow.parts.sumOf { it.downloaded } >= downloadedBefore,
                "no progress may be lost when splitting",
            )

            engine.setConnections(id, 3)
            waitUntil(30_000) { engine.get(id)!!.parts.size == 3 }
            val afterShrink = engine.get(id)!!
            assertEquals(3, afterShrink.parts.size)
            assertEquals(totalsBefore, afterShrink.parts.sumOf { it.total }, "merging must preserve the total")
            assertTrue(
                afterShrink.parts.sumOf { it.downloaded } >= downloadedBefore,
                "no progress may be lost when merging",
            )

            waitUntil(180_000) { engine.get(id)?.state?.isTerminal == true }
            val done = engine.get(id)!!
            assertEquals(DownloadState.COMPLETED, done.state, "download failed: ${done.error}")
            assertContentEquals(payload, Files.readAllBytes(Path.of(done.path)))
            engine.shutdown()
        }
    }

    @Test
    fun `the abdm style table is empty while queued and after removal`() = runBlocking {
        val payload = Random(33).nextBytes(1024 * 1024)
        LocalFileServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-parts-queued")
            val scope = newScope()
            val engine = engine(root, scope)
            val id = engine.create(
                CreateDownloadRequest(
                    url = server.url,
                    folder = root.toString(),
                    connections = 8,
                    startImmediately = false,
                ),
            )
            assertTrue(engine.get(id)!!.parts.isEmpty(), "nothing to show before the download starts")
            engine.remove(id, deleteFile = true)
            assertEquals(null, engine.get(id))
            engine.shutdown()
        }
    }
}
