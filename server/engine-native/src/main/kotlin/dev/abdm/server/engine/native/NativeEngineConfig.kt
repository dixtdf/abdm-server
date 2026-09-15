package dev.abdm.server.engine.native

import java.nio.file.Path

/**
 * Runtime configuration of the built-in engine.
 *
 * Values are captured from `ServerSettings` at boot; the engine only re-reads the
 * mutable ones (speed limits, progress interval) so that a settings change does not
 * require a restart.
 */
data class NativeEngineConfig(
    val downloadRoot: Path,
    val userAgent: String = "ABDM-Server/0.1",
    val defaultConnections: Int = 8,
    val progressIntervalMs: Long = 500,
    val checkpointIntervalMs: Long = 3_000,
    val maxRetriesPerChunk: Int = 3,
    val chunkBufferSize: Int = 128 * 1024,
)
