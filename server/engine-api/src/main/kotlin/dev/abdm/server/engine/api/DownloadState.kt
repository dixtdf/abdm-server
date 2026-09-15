package dev.abdm.server.engine.api

/**
 * The single, engine independent set of download states exposed by this project.
 *
 * No vendor (AB Download Manager) status enum is ever returned to the API layer:
 * the adapter is responsible for translating its own states into these values.
 */
enum class DownloadState {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    PAUSING,
    PAUSED,
    COMPLETING,
    COMPLETED,
    FAILED,
    CANCELED,
    RECOVERING,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELED

    val isActive: Boolean
        get() = this == CONNECTING || this == DOWNLOADING || this == COMPLETING || this == RECOVERING

    val isResumable: Boolean
        get() = this == PAUSED || this == FAILED || this == CANCELED || this == QUEUED
}

/** Connection count bounds. Also documented in the API: `connections` is 1..256. */
object Connections {
    const val MIN = 1
    const val MAX = 256
    const val DEFAULT = 8

    fun coerce(value: Int): Int = value.coerceIn(MIN, MAX)
}
