package dev.abdm.server.engine.api

/**
 * Engine independent error code.
 *
 * The backend never returns a human readable sentence: it returns a stable code plus
 * parameters. The web UI resolves it through i18n, e.g.
 *
 * ```
 * DOWNLOAD_DIRECTORY_NOT_FOUND + {path=/downloads/linux}
 *   en-US -> Download directory "/downloads/linux" does not exist.
 *   zh-CN -> 下载目录“/downloads/linux”不存在。
 * ```
 */
object EngineErrorCode {
    const val INVALID_URL = "INVALID_URL"
    const val UNSUPPORTED_SCHEME = "UNSUPPORTED_SCHEME"
    const val DOWNLOAD_DIRECTORY_NOT_FOUND = "DOWNLOAD_DIRECTORY_NOT_FOUND"
    const val PATH_OUTSIDE_DOWNLOAD_ROOT = "PATH_OUTSIDE_DOWNLOAD_ROOT"
    const val TASK_NOT_FOUND = "TASK_NOT_FOUND"
    const val TASK_ALREADY_EXISTS = "TASK_ALREADY_EXISTS"
    const val INVALID_CONNECTION_COUNT = "INVALID_CONNECTION_COUNT"
    const val NETWORK = "NETWORK"
    const val SERVER_NO_RANGE_SUPPORT = "SERVER_NO_RANGE_SUPPORT"
    const val DISK_FULL = "DISK_FULL"
    const val CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    const val ENGINE_UNAVAILABLE = "ENGINE_UNAVAILABLE"
    const val UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val INTERNAL = "INTERNAL"
}

data class EngineError(
    val code: String,
    val params: Map<String, String> = emptyMap(),
    val detail: String? = null,
) {
    override fun toString(): String =
        buildString {
            append(code)
            if (params.isNotEmpty()) append(params)
            detail?.let { append(": ").append(it) }
        }
}

open class EngineException(
    val error: EngineError,
    cause: Throwable? = null,
) : RuntimeException(error.toString(), cause) {
    constructor(
        code: String,
        params: Map<String, String> = emptyMap(),
        detail: String? = null,
        cause: Throwable? = null,
    ) : this(EngineError(code, params, detail), cause)
}

fun Throwable.toEngineError(fallbackDetail: String? = null): EngineError = when (this) {
    is EngineException -> error
    is java.io.FileNotFoundException -> EngineError(
        EngineErrorCode.DOWNLOAD_DIRECTORY_NOT_FOUND,
        mapOf("path" to (fallbackDetail ?: message.orEmpty())),
        message,
    )
    is java.net.UnknownHostException -> EngineError(EngineErrorCode.NETWORK, mapOf("host" to (message ?: "")), message)
    is java.io.IOException -> EngineError(EngineErrorCode.NETWORK, emptyMap(), message ?: fallbackDetail)
    else -> EngineError(EngineErrorCode.INTERNAL, emptyMap(), message ?: fallbackDetail)
}
