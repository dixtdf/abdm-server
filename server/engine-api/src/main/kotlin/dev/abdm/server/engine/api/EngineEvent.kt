package dev.abdm.server.engine.api

/** Engine -> server events. Mapped to `download.*` WebSocket envelopes by `server:web-api`. */
sealed interface EngineEvent {
    val taskId: String

    data class Added(val task: TaskSnapshot) : EngineEvent {
        override val taskId: String get() = task.id
    }

    data class Progress(val progress: TaskProgress) : EngineEvent {
        override val taskId: String get() = progress.id
    }

    data class StateChanged(
        val task: TaskSnapshot,
        val previous: DownloadState,
        val error: EngineError? = null,
    ) : EngineEvent {
        override val taskId: String get() = task.id
    }

    data class ConnectionsChanged(
        val task: TaskSnapshot,
        val requested: Int,
        val active: Int,
    ) : EngineEvent {
        override val taskId: String get() = task.id
    }

    data class Removed(override val taskId: String, val deletedFile: Boolean) : EngineEvent
}
