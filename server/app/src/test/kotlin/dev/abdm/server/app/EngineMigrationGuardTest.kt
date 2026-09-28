package dev.abdm.server.app

import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.PersistedTask
import dev.abdm.server.persistence.SqliteStore
import dev.abdm.server.persistence.SqliteTaskRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineMigrationGuardTest {

    @Test
    fun `ABDM refuses to hide tasks from an existing native database`() = runBlocking {
        val root = Files.createTempDirectory("abdm-native-migration")
        val config = AppConfig(configDir = root.resolve("config"), downloadRoot = root.resolve("downloads"))
        SqliteStore(config.databasePath).use { store ->
            store.open()
            SqliteTaskRepository(store).upsert(
                PersistedTask(
                    id = "ab12cd34ef56ab78",
                    url = "https://example.com/file.bin",
                    fileName = "file.bin",
                    folder = config.downloadRoot.toString(),
                    state = DownloadState.COMPLETED,
                    total = 10,
                    downloaded = 10,
                    connections = 1,
                    supportsRange = true,
                    hls = false,
                    speedLimit = 0,
                    checksum = null,
                    error = null,
                    createdAt = 1,
                    startedAt = 1,
                    completedAt = 2,
                    queuePosition = null,
                    completedRanges = emptyList(),
                ),
            )
        }

        val server = DownloadServer(config)
        try {
            val error = assertFailsWith<IllegalStateException> { server.start(wait = false) }
            assertTrue(error.message.orEmpty().contains("legacy native task"))
        } finally {
            server.stop()
        }
    }
}
