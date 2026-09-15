package dev.abdm.server.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite storage.
 *
 * One connection guarded by a [Mutex]: SQLite in WAL mode handles a single writer
 * extremely well and this keeps the whole module free of pooling complexity.
 * `database.sqlite` lives in the config directory and survives container restarts.
 */
class SqliteStore(
    private val databasePath: Path,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(SqliteStore::class.java)
    private val mutex = Mutex()
    private lateinit var connection: Connection

    fun open() {
        Files.createDirectories(databasePath.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = NORMAL")
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
        migrate()
        log.info("SQLite ready at {} (WAL)", databasePath.toAbsolutePath())
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS downloads (
                    id               TEXT PRIMARY KEY,
                    url              TEXT NOT NULL,
                    file_name        TEXT NOT NULL,
                    folder           TEXT NOT NULL,
                    state            TEXT NOT NULL,
                    total            INTEGER NOT NULL DEFAULT -1,
                    downloaded       INTEGER NOT NULL DEFAULT 0,
                    connections      INTEGER NOT NULL DEFAULT 8,
                    supports_range   INTEGER NOT NULL DEFAULT 0,
                    hls              INTEGER NOT NULL DEFAULT 0,
                    speed_limit      INTEGER NOT NULL DEFAULT 0,
                    checksum         TEXT,
                    error_code       TEXT,
                    error_params     TEXT,
                    error_detail     TEXT,
                    created_at       INTEGER NOT NULL,
                    started_at       INTEGER,
                    completed_at     INTEGER,
                    headers          TEXT,
                    cookies          TEXT,
                    referer          TEXT,
                    user_agent       TEXT,
                    proxy            TEXT,
                    completed_ranges TEXT,
                    sequence         INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS queue (
                    id           TEXT PRIMARY KEY,
                    position     INTEGER NOT NULL,
                    requested_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS host_settings (
                    host                  TEXT PRIMARY KEY,
                    preferred_connections INTEGER NOT NULL,
                    max_connections       INTEGER NOT NULL,
                    updated_at            INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS download_history (
                    id          TEXT PRIMARY KEY,
                    url         TEXT NOT NULL,
                    file_name   TEXT NOT NULL,
                    folder      TEXT NOT NULL,
                    size        INTEGER NOT NULL,
                    checksum    TEXT,
                    outcome     TEXT NOT NULL,
                    finished_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_history_finished ON download_history(finished_at DESC)")
        }
    }

    internal suspend fun <T> read(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(connection) }
    }

    internal suspend fun write(block: (Connection) -> Unit): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { block(connection) }
    }

    override fun close() {
        if (::connection.isInitialized) {
            runCatching { connection.close() }
        }
    }
}
