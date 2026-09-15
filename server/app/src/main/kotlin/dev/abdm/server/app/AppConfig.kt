package dev.abdm.server.app

import java.nio.file.Files
import java.nio.file.Path

/**
 * Process configuration.
 *
 * Everything is environment driven, which is what makes the Docker image a single
 * documented contract (see README "Configuration"):
 *
 * ```
 * PORT               8080
 * ABDM_CONFIG_DIR    /config      (SQLite + ABDM state)
 * ABDM_DOWNLOAD_ROOT /downloads   (the only writable area of the API)
 * ABDM_WEB_DIR       /app/web     (built frontend; embedded in the jar as fallback)
 * ABDM_ENGINE        native|abdm
 * ABDM_AUTH_MODE     none|token
 * ABDM_AUTH_TOKEN    shared secret when AUTH_MODE=token
 * ABDM_LOG_LEVEL     info
 * TZ                 Asia/Shanghai
 * ```
 */
data class AppConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val configDir: Path = Path.of("/config"),
    val downloadRoot: Path = Path.of("/downloads"),
    val webDir: Path? = null,
    val engine: String = "native",
    val authMode: String = "none",
    val authToken: String? = null,
    val logLevel: String = "info",
) {
    val databasePath: Path get() = configDir.resolve("database.sqlite")
    val abdmDataFolder: Path get() = configDir.resolve("abdm")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { env[it]?.takeIf { v -> v.isNotBlank() } }

            val defaultConfigDir = if (isWindows()) "config" else "/config"
            val defaultDownloadRoot = if (isWindows()) "downloads" else "/downloads"

            return AppConfig(
                host = value("ABDM_HOST", "HOST") ?: "0.0.0.0",
                port = (value("PORT", "ABDM_PORT") ?: "8080").toIntOrNull() ?: 8080,
                configDir = Path.of(value("ABDM_CONFIG_DIR") ?: defaultConfigDir).toAbsolutePath().normalize(),
                downloadRoot = Path.of(value("ABDM_DOWNLOAD_ROOT") ?: defaultDownloadRoot)
                    .toAbsolutePath().normalize(),
                webDir = value("ABDM_WEB_DIR")?.let { Path.of(it).toAbsolutePath().normalize() },
                engine = (value("ABDM_ENGINE") ?: "native").lowercase(),
                authMode = (value("ABDM_AUTH_MODE", "AUTH_MODE") ?: "none").lowercase(),
                authToken = value("ABDM_AUTH_TOKEN", "AUTH_TOKEN"),
                logLevel = (value("ABDM_LOG_LEVEL") ?: "info").lowercase(),
            )
        }

        private fun isWindows(): Boolean =
            System.getProperty("os.name").lowercase().contains("win")
    }

    fun ensureDirectories() {
        Files.createDirectories(configDir)
        Files.createDirectories(downloadRoot)
        Files.createDirectories(abdmDataFolder)
    }
}
