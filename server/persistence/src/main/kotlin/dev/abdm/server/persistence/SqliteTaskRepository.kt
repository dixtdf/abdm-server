package dev.abdm.server.persistence

import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.EngineError
import dev.abdm.server.engine.api.HistoryEntry
import dev.abdm.server.engine.api.PersistedTask
import dev.abdm.server.engine.api.RangeCodec
import dev.abdm.server.engine.api.TaskRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.ResultSet

/** SQLite implementation of the task/history ports declared in `engine-api`. */
class SqliteTaskRepository(
    private val store: SqliteStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TaskRepository {

    override suspend fun loadAll(): List<PersistedTask> = store.read { connection ->
        connection.prepareStatement("SELECT * FROM downloads ORDER BY sequence ASC, created_at ASC").use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toTask())
                }
            }
        }
    }

    override suspend fun upsert(task: PersistedTask) = store.write { connection ->
        connection.prepareStatement(
            """
            INSERT INTO downloads (
                id, url, file_name, folder, state, total, downloaded, connections,
                supports_range, hls, speed_limit, checksum, error_code, error_params, error_detail,
                created_at, started_at, completed_at, headers, cookies, referer, user_agent, proxy,
                completed_ranges, sequence
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                url=excluded.url, file_name=excluded.file_name, folder=excluded.folder,
                state=excluded.state, total=excluded.total, downloaded=excluded.downloaded,
                connections=excluded.connections, supports_range=excluded.supports_range,
                hls=excluded.hls, speed_limit=excluded.speed_limit, checksum=excluded.checksum,
                error_code=excluded.error_code, error_params=excluded.error_params,
                error_detail=excluded.error_detail, started_at=excluded.started_at,
                completed_at=excluded.completed_at, headers=excluded.headers, cookies=excluded.cookies,
                referer=excluded.referer, user_agent=excluded.user_agent, proxy=excluded.proxy,
                completed_ranges=excluded.completed_ranges, sequence=excluded.sequence
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setString(index++, task.id)
            statement.setString(index++, task.url)
            statement.setString(index++, task.fileName)
            statement.setString(index++, task.folder)
            statement.setString(index++, task.state.name)
            statement.setLong(index++, task.total)
            statement.setLong(index++, task.downloaded)
            statement.setInt(index++, task.connections)
            statement.setInt(index++, if (task.supportsRange) 1 else 0)
            statement.setInt(index++, if (task.hls) 1 else 0)
            statement.setLong(index++, task.speedLimit)
            statement.setString(index++, task.checksum)
            statement.setString(index++, task.error?.code)
            statement.setString(index++, task.error?.let { json.encodeToString(it.params) })
            statement.setString(index++, task.error?.detail)
            statement.setLong(index++, task.createdAt)
            statement.setObject(index++, task.startedAt)
            statement.setObject(index++, task.completedAt)
            statement.setString(index++, json.encodeToString(task.headers))
            statement.setString(index++, task.cookies)
            statement.setString(index++, task.referer)
            statement.setString(index++, task.userAgent)
            statement.setString(index++, task.proxy)
            statement.setString(index++, RangeCodec.encode(task.completedRanges))
            statement.setLong(index, task.sequence)
            statement.executeUpdate()
        }
    }

    override suspend fun updateProgress(id: String, downloaded: Long, completedRanges: List<LongRange>) =
        store.write { connection ->
            connection.prepareStatement(
                "UPDATE downloads SET downloaded = ?, completed_ranges = ? WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, downloaded)
                statement.setString(2, RangeCodec.encode(completedRanges))
                statement.setString(3, id)
                statement.executeUpdate()
            }
        }

    override suspend fun delete(id: String) = store.write { connection ->
        connection.prepareStatement("DELETE FROM downloads WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeUpdate()
        }
    }

    override suspend fun nextSequence(): Long = store.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COALESCE(MAX(sequence), 0) + 1 FROM downloads").use { rows ->
                if (rows.next()) rows.getLong(1) else 1L
            }
        }
    }

    override suspend fun appendHistory(entry: HistoryEntry) = store.write { connection ->
        connection.prepareStatement(
            """
            INSERT INTO download_history (id, url, file_name, folder, size, checksum, outcome, finished_at)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET outcome = excluded.outcome, finished_at = excluded.finished_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "${entry.id}:${entry.finishedAt}")
            statement.setString(2, entry.url)
            statement.setString(3, entry.fileName)
            statement.setString(4, entry.folder)
            statement.setLong(5, entry.size)
            statement.setString(6, entry.checksum)
            statement.setString(7, entry.outcome)
            statement.setLong(8, entry.finishedAt)
            statement.executeUpdate()
        }
    }

    override suspend fun history(limit: Int): List<HistoryEntry> = store.read { connection ->
        connection.prepareStatement(
            "SELECT * FROM download_history ORDER BY finished_at DESC LIMIT ?",
        ).use { statement ->
            statement.setInt(1, limit.coerceIn(1, 1000))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            HistoryEntry(
                                id = rows.getString("id"),
                                url = rows.getString("url"),
                                fileName = rows.getString("file_name"),
                                folder = rows.getString("folder"),
                                size = rows.getLong("size"),
                                checksum = rows.getString("checksum"),
                                outcome = rows.getString("outcome"),
                                finishedAt = rows.getLong("finished_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun clearHistory() = store.write { connection ->
        connection.createStatement().use { it.executeUpdate("DELETE FROM download_history") }
    }

    private fun ResultSet.toTask(): PersistedTask {
        val errorCode = getString("error_code")
        val error = errorCode?.let {
            EngineError(
                code = it,
                params = decodeParams(getString("error_params")),
                detail = getString("error_detail"),
            )
        }
        val startedAt = getLong("started_at").takeIf { !wasNull() }
        val completedAt = getLong("completed_at").takeIf { !wasNull() }
        return PersistedTask(
            id = getString("id"),
            url = getString("url"),
            fileName = getString("file_name"),
            folder = getString("folder"),
            state = runCatching { DownloadState.valueOf(getString("state")) }.getOrDefault(DownloadState.QUEUED),
            total = getLong("total"),
            downloaded = getLong("downloaded"),
            connections = getInt("connections"),
            supportsRange = getInt("supports_range") == 1,
            hls = getInt("hls") == 1,
            speedLimit = getLong("speed_limit"),
            checksum = getString("checksum"),
            error = error,
            createdAt = getLong("created_at"),
            startedAt = startedAt,
            completedAt = completedAt,
            queuePosition = null,
            completedRanges = RangeCodec.decode(getString("completed_ranges")),
            headers = decodeHeaders(getString("headers")),
            cookies = getString("cookies"),
            referer = getString("referer"),
            userAgent = getString("user_agent"),
            proxy = getString("proxy"),
            sequence = getLong("sequence"),
        )
    }

    private fun decodeParams(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val element = json.parseToJsonElement(raw)
            (element as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun decodeHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val element = json.parseToJsonElement(raw)
            (element as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    @Suppress("unused")
    private fun sample() = buildJsonObject { put("sample", "value") }
}
