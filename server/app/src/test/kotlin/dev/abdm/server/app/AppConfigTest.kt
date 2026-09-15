package dev.abdm.server.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {

    @Test
    fun `defaults to the documented port and directories`() {
        val config = AppConfig.fromEnvironment(emptyMap())
        assertEquals(6868, config.port)
        assertEquals("none", config.authMode)
        assertEquals("native", config.engine)
    }

    @Test
    fun `reads port, auth and engine from the environment`() {
        val config = AppConfig.fromEnvironment(
            mapOf(
                "PORT" to "7000",
                "ABDM_AUTH_MODE" to "token",
                "ABDM_AUTH_TOKEN" to "***",
                "ABDM_ENGINE" to "abdm",
                "ABDM_LOG_LEVEL" to "debug",
            ),
        )
        assertEquals(7000, config.port)
        assertEquals("token", config.authMode)
        assertEquals("abdm", config.engine)
        assertEquals("debug", config.logLevel)
    }

    @Test
    fun `a bad port falls back to the default instead of crashing`() {
        assertEquals(6868, AppConfig.fromEnvironment(mapOf("PORT" to "not-a-number")).port)
    }

    @Test
    fun `ensureDirectories creates the tree and explains a directory it cannot use`() {
        val root = Files.createTempDirectory("abdm-config")
        val config = AppConfig(
            configDir = root.resolve("config"),
            downloadRoot = root.resolve("downloads"),
        )
        config.ensureDirectories()
        assertTrue(Files.isDirectory(config.configDir))
        assertTrue(Files.isDirectory(config.downloadRoot))
        assertTrue(Files.isDirectory(config.abdmDataFolder))

        // A *file* where a directory is expected is the portable way to reproduce a
        // broken mount: the failure must name the directory, the variable and the fix.
        val blocked = root.resolve("blocked")
        Files.writeString(blocked, "not a directory")
        val failure = runCatching {
            AppConfig(configDir = blocked, downloadRoot = root.resolve("downloads")).ensureDirectories()
        }.exceptionOrNull()

        val message = failure?.message ?: ""
        assertTrue(failure is IllegalStateException, "expected IllegalStateException, got $failure")
        assertTrue(message.contains("ABDM_CONFIG_DIR"), message)
        assertTrue(message.contains("chown -R 1000:1000"), message)
    }
}
