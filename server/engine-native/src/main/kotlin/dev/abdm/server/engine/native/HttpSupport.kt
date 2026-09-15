package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.CreateDownloadRequest
import dev.abdm.server.engine.api.EngineError
import dev.abdm.server.engine.api.EngineErrorCode
import dev.abdm.server.engine.api.EngineException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a `Range: bytes=0-0` probe tells us about a remote resource. */
data class ProbeResult(
    val uri: URI,
    val contentLength: Long,
    val supportsRange: Boolean,
    val fileName: String?,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?,
) {
    val isHls: Boolean
        get() = contentType?.contains("mpegurl", ignoreCase = true) == true ||
            uri.path?.endsWith(".m3u8", ignoreCase = true) == true
}

/**
 * Thin wrapper over the JDK HTTP client: no third party HTTP stack is required,
 * which keeps the runtime image small.
 */
class HttpSupport(
    private val defaultUserAgent: String = "ABDM-Server/0.1 (+https://github.com/)",
) {
    fun client(request: CreateDownloadRequest): HttpClient {
        val builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
        request.proxy?.takeIf { it.isNotBlank() }?.let { proxy ->
            val uri = runCatching { URI(proxy) }.getOrNull()
            val host = uri?.host
            val port = uri?.port ?: -1
            if (host != null && port > 0) {
                builder.proxy(ProxySelector.of(InetSocketAddress(host, port)))
            }
        }
        return builder.build()
    }

    fun requestBuilder(
        uri: URI,
        request: CreateDownloadRequest,
        range: LongRange? = null,
        method: String = "GET",
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(30))
        if (method == "HEAD") builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
        else builder.GET()
        builder.header("User-Agent", request.userAgent?.takeIf { it.isNotBlank() } ?: defaultUserAgent)
        builder.header("Accept", "*/*")
        builder.header("Accept-Encoding", "identity")
        request.referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        request.cookies?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        request.headers.forEach { (key, value) ->
            val normalized = key.lowercase()
            if (normalized in MANAGED_HEADERS) return@forEach
            builder.header(key, value)
        }
        range?.let { builder.header("Range", "bytes=${it.first}-${it.last}") }
        return builder
    }

    /** Probes content length, range support and the suggested file name. */
    suspend fun probe(uri: URI, request: CreateDownloadRequest): ProbeResult {
        val client = client(request)
        val ranged = try {
            val response = client.sendAsync(
                requestBuilder(uri, request, 0L..0L).build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            ).awaitFuture()
            response.body().close()
            response
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            throw EngineException(EngineErrorCode.NETWORK, mapOf("url" to uri.toString()), e.message, e)
        }

        val rangeHeader = ranged.headers().firstValue("Content-Range").orElse(null)
        val supportsRange = ranged.statusCode() == 206 && rangeHeader != null
        var length = when {
            supportsRange -> rangeHeader!!.substringAfter('/').trim().toLongOrNull() ?: -1L
            ranged.statusCode() == 200 -> ranged.headers().firstValue("Content-Length").orElse("-1").toLongOrNull() ?: -1L
            else -> -1L
        }
        if (length < 0 && ranged.statusCode() >= 400) {
            throw EngineException(
                EngineErrorCode.NETWORK,
                mapOf("url" to uri.toString(), "status" to ranged.statusCode().toString()),
                "unexpected status ${ranged.statusCode()}",
            )
        }
        val fileName = FileNameResolver.fromContentDisposition(
            ranged.headers().firstValue("Content-Disposition").orElse(null),
        )
        return ProbeResult(
            uri = uri,
            contentLength = length,
            supportsRange = supportsRange,
            fileName = fileName,
            contentType = ranged.headers().firstValue("Content-Type").orElse(null),
            etag = ranged.headers().firstValue("ETag").orElse(null),
            lastModified = ranged.headers().firstValue("Last-Modified").orElse(null),
        )
    }

    suspend fun openRange(
        client: HttpClient,
        uri: URI,
        request: CreateDownloadRequest,
        range: LongRange?,
    ): HttpResponse<InputStream> {
        val httpRequest = requestBuilder(uri, request, range).build()
        return try {
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream()).awaitFuture()
        } catch (e: Exception) {
            throw EngineException(EngineErrorCode.NETWORK, mapOf("url" to uri.toString()), e.message, e)
        }
    }

    companion object {
        val MANAGED_HEADERS = setOf("host", "range", "content-length", "accept-encoding", "user-agent", "referer", "cookie")

        fun toEngineError(t: Throwable, uri: String?): EngineError = when (t) {
            is EngineException -> t.error
            is java.net.UnknownHostException -> EngineError(EngineErrorCode.NETWORK, mapOf("host" to t.message.orEmpty()), t.message)
            is java.io.IOException -> {
                val message = t.message.orEmpty()
                if (message.contains("space left", ignoreCase = true) || message.contains("Not enough space", ignoreCase = true)) {
                    EngineError(EngineErrorCode.DISK_FULL, mapOf("path" to uri.orEmpty()), message)
                } else {
                    EngineError(EngineErrorCode.NETWORK, mapOf("url" to uri.orEmpty()), message)
                }
            }
            else -> EngineError(EngineErrorCode.INTERNAL, emptyMap(), t.message)
        }
    }
}

/**
 * Awaits a JDK [CompletableFuture] without pulling in an extra artifact, and
 * propagates coroutine cancellation to the underlying HTTP exchange (this is what
 * makes `pause` release its sockets immediately).
 */
suspend fun <T> CompletableFuture<T>.awaitFuture(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error != null) continuation.resumeWithException(error) else continuation.resume(value)
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
