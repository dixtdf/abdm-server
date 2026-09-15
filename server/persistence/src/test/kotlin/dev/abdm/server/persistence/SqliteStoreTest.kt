package dev.abdm.server.persistence

import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineError
import dev.abdm.server.engine.api.HistoryEntry
import dev.abdm.server.engine.api.PersistedTask
import dev.abdm.server.engine.api.ServerSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteStoreTest {

    private val stores = mutableListOf<SqliteStore>()

    private fun open(): SqliteStore {
        val dir = Files.createTempDirectory("abdm-sqlite")
        return SqliteStore(dir.resolve("database.sqlite")).also {
            it.open()
            stores.add(it)
        }
    }

    @AfterTest
    fun tearDown() {
        stores.forEach { it.close() }
    }

    private fun task(id: String, state: DownloadState = DownloadState.DOWNLOADING) = PersistedTask(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        folder = "/downloads",
        state = state,
        total = 1024,
        downloaded = 512,
        connections = 32,
        supportsRange = true,
        hls = false,
        speedLimit = 0,
        checksum = "sha256:abc",
        error = EngineError("NETWORK", mapOf("url" to "x")),
        createdAt = 1,
        startedAt = 2,
        completedAt = null,
        queuePosition = null,
        completedRanges = listOf(0L until 256L, 512L until 768L),
        headers = mapOf("X-Test" to "1"),
        cookies = "a=b",
        referer = "https://example.com",
        userAgent = "agent",
        proxy = "http://127.0.0.1:1",
        sequence = 7,
    )

    @Test
    fun `tasks round trip including ranges and error params`() = runBlocking {
        val store = open()
        val repository = SqliteTaskRepository(store)
        val original = task("a1")
        repository.upsert(original)

        val loaded = repository.loadAll().single()
        assertEquals(original.id, loaded.id)
        assertEquals(original.state, loaded.state)
        assertEquals(original.completedRanges, loaded.completedRanges)
        assertEquals(original.headers, loaded.headers)
        assertEquals(original.cookies, loaded.cookies)
        assertEquals(original.sequence, loaded.sequence)
        assertEquals("NETWORK", loaded.error?.code)
        assertEquals(mapOf("url" to "x"), loaded.error?.params)

        // upsert must update in place, not duplicate
        repository.upsert(original.copy(state = DownloadState.COMPLETED, downloaded = 1024))
        val updated = repository.loadAll().single()
        assertEquals(DownloadState.COMPLETED, updated.state)
        assertEquals(1024, updated.downloaded)

        repository.delete(original.id)
        assertTrue(repository.loadAll().isEmpty())
    }

    @Test
    fun `next sequence keeps growing`() = runBlocking {
        val repository = SqliteTaskRepository(open())
        assertEquals(1, repository.nextSequence())
        repository.upsert(task("a1").copy(sequence = 1))
        assertEquals(2, repository.nextSequence())
    }

    @Test
    fun `history is append only and ordered newest first`() = runBlocking {
        val repository = SqliteTaskRepository(open())
        repository.appendHistory(HistoryEntry("1", "u1", "f1", "/d", 10, null, "COMPLETED", 100))
        repository.appendHistory(HistoryEntry("2", "u2", "f2", "/d", 20, "sha256:x", "FAILED", 200))
        val history = repository.history(10)
        assertEquals(listOf("u2", "u1"), history.map { it.url })
        assertEquals(200, history.first().finishedAt)
        repository.clearHistory()
        assertTrue(repository.history(10).isEmpty())
    }

    @Test
    fun `settings persist as a json document`() = runBlocking {
        val store = open()
        val repository = SqliteSettingsRepository(store)
        val defaults = repository.load()
        assertEquals(8, defaults.defaultConnections)

        val custom = ServerSettings(
            downloadRoot = "/downloads",
            defaultFolder = "/downloads/iso",
            defaultConnections = 64,
            maxConcurrentDownloads = 5,
            globalSpeedLimit = 1024,
            resumeOnStartup = false,
            progressIntervalMs = 250,
            authMode = "token",
        )
        repository.save(custom)
        assertEquals(custom, SqliteSettingsRepository(store).load())
    }

    @Test
    fun `queue order survives a reopen`() = runBlocking {
        val store = open()
        val repository = SqliteQueueRepository(store)
        repository.save(listOf("b", "a", "c"))
        assertEquals(listOf("b", "a", "c"), repository.load())
        repository.save(listOf("c"))
        assertEquals(listOf("c"), repository.load())
    }

    @Test
    fun `host profiles are upserted`() = runBlocking {
        val repository = SqliteHostProfileRepository(open())
        repository.put(dev.abdm.server.engine.api.HostProfile("github.com", 16))
        repository.put(dev.abdm.server.engine.api.HostProfile("github.com", 32, 64))
        val all = repository.all()
        assertEquals(1, all.size)
        assertEquals(32, all.single().preferredConnections)
        assertEquals(64, all.single().maxConnections)
    }

    @Test
    fun `wal mode is enabled and the file exists on disk`() = runBlocking {
        val dir = Files.createTempDirectory("abdm-sqlite-wal")
        val path = dir.resolve("database.sqlite")
        SqliteStore(path).use { store ->
            store.open()
            store.write { connection ->
                connection.createStatement().use { it.executeUpdate("INSERT INTO settings (key, value) VALUES ('k','v')") }
            }
            assertNotNull(path.toFile())
            assertTrue(Files.exists(path))
        }
    }
}
