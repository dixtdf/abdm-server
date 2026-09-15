package dev.abdm.server.engine.api

import kotlinx.serialization.Serializable

/**
 * Everything a caller may specify when a download is created.
 *
 * Only the first four fields are surfaced by the "simple" form of the web UI;
 * the rest live behind the collapsed "Advanced" section.
 */
@Serializable
data class CreateDownloadRequest(
    val url: String,
    val fileName: String? = null,
    /** Absolute path of the target directory. Must stay inside the configured download root. */
    val folder: String? = null,
    val connections: Int? = null,
    // ---- advanced ----
    val headers: Map<String, String> = emptyMap(),
    val cookies: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    /** HTTP/SOCKS proxy URL, e.g. `http://127.0.0.1:7890`. */
    val proxy: String? = null,
    /** Bytes per second, 0 = unlimited. */
    val speedLimit: Long = 0,
    /** Optional `sha256:<hex>` checksum verified on completion. */
    val checksum: String? = null,
    /** Start immediately or park the task in the queue. */
    val startImmediately: Boolean = true,
    /** Force HLS handling (auto detected from `.m3u8` otherwise). */
    val hls: Boolean = false,
    /** Allow overwriting an already existing file. */
    val overwrite: Boolean = false,
) {
    fun normalizedConnections(): Int = Connections.coerce(connections ?: Connections.DEFAULT)
}
