package dev.abdm.server.persistence

import dev.abdm.server.engine.api.HostProfile
import dev.abdm.server.engine.api.HostProfileRepository
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.SettingsRepository
import kotlinx.serialization.json.Json

/** `settings` table: a single JSON document, so new fields are backward compatible. */
class SqliteSettingsRepository(
    private val store: SqliteStore,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) : SettingsRepository {

    override suspend fun load(): ServerSettings = store.read { connection ->
        connection.prepareStatement("SELECT value FROM settings WHERE key = 'server'").use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    runCatching { json.decodeFromString<ServerSettings>(rows.getString(1)) }
                        .getOrElse { ServerSettings() }
                } else {
                    ServerSettings()
                }
            }
        }
    }

    override suspend fun save(settings: ServerSettings) = store.write { connection ->
        connection.prepareStatement(
            "INSERT INTO settings (key, value) VALUES ('server', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        ).use { statement ->
            statement.setString(1, json.encodeToString(settings))
            statement.executeUpdate()
        }
    }
}

class SqliteHostProfileRepository(private val store: SqliteStore) : HostProfileRepository {

    override suspend fun all(): List<HostProfile> = store.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM host_settings ORDER BY host ASC").use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            HostProfile(
                                host = rows.getString("host"),
                                preferredConnections = rows.getInt("preferred_connections"),
                                maxConnections = rows.getInt("max_connections"),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun put(profile: HostProfile) = store.write { connection ->
        connection.prepareStatement(
            """
            INSERT INTO host_settings (host, preferred_connections, max_connections, updated_at)
            VALUES (?,?,?,?)
            ON CONFLICT(host) DO UPDATE SET
                preferred_connections = excluded.preferred_connections,
                max_connections = excluded.max_connections,
                updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, profile.host)
            statement.setInt(2, profile.preferredConnections)
            statement.setInt(3, profile.maxConnections)
            statement.setLong(4, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }
}

/** Waiting queue positions, kept across restarts so the order never changes. */
class SqliteQueueRepository(private val store: SqliteStore) {

    suspend fun save(ids: List<String>) = store.write { connection ->
        connection.createStatement().use { it.executeUpdate("DELETE FROM queue") }
        connection.prepareStatement("INSERT INTO queue (id, position, requested_at) VALUES (?,?,?)").use { statement ->
            val now = System.currentTimeMillis()
            ids.forEachIndexed { index, id ->
                statement.setString(1, id)
                statement.setInt(2, index)
                statement.setLong(3, now)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    suspend fun load(): List<String> = store.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM queue ORDER BY position ASC").use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }
        }
    }
}
