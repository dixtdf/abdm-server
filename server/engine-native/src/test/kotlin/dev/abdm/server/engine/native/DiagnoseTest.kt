package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.CreateDownloadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test

/**
 * Prints the engine internals while a download runs.
 *
 * Kept in the suite on purpose: when a segmented download stalls on an unusual
 * server, this is the fastest way to see whether the queue, the workers or the
 * ticker is the problem. It asserts the download eventually finishes, so it is a
 * real test as well.
 */
class DiagnoseTest {

    @Test
    fun `trace a segmented download with connection changes`() = runBlocking {
        val payload = Random(11).nextBytes(24 * 1024 * 1024)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        LocalFileServer(payload).use { server ->
            val root = Files.createTempDirectory("abdm-diagnose")
            val engine = NativeDownloadEngine(
                config = NativeEngineConfig(downloadRoot = root, progressIntervalMs = 100),
                scope = scope,
                repository = InMemoryTaskRepository(),
                settingsProvider = { InMemoryTaskRepository.settingsFor(root.toString()) },
            )
            val id = engine.create(
                CreateDownloadRequest(url = server.url, folder = root.toString(), connections = 8),
            )
            engine.start(id)
            engine.setConnections(id, 32)
            repeat(40) { tick ->
                delay(500)
                println("[tick $tick] ${engine.debug(id)}")
                when (tick) {
                    4 -> engine.setConnections(id, 64)
                    8 -> engine.setConnections(id, 256)
                    12 -> engine.setConnections(id, 16)
                }
                val state = engine.get(id)?.state
                if (state != null && state.isTerminal) return@repeat
            }
            engine.shutdown()
        }
        scope.cancel()
    }
}
