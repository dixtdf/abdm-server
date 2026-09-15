package dev.abdm.server.engine.api

/**
 * Identifies who actually performs the downloads.
 *
 * Surfaced by `GET /api/v1/version` and on the About page, so a user always knows
 * whether the ABDM engine or the built-in engine is running.
 */
data class EngineDescriptor(
    val name: String,
    val version: String,
    val vendor: String = "abdm-server",
    /** Upstream project name when the engine is a bridge to another downloader. */
    val upstreamName: String? = null,
    val upstreamVersion: String? = null,
    val upstreamCommit: String? = null,
    val capabilities: Set<EngineCapability> = emptySet(),
)
