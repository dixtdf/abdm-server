package dev.abdm.server.engine.native

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Minimal range-capable HTTP server used by the engine tests.
 *
 * It speaks exactly the dialect the engine relies on: `Accept-Ranges`, `206` with
 * `Content-Range` for a ranged GET, `200` with the whole body otherwise.
 */
class LocalFileServer(
    private val payload: ByteArray,
    private val fileName: String = "file.bin",
    private val supportRange: Boolean = true,
    private val onRequest: ((HttpExchange) -> Unit)? = null,
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 512)
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        320,
        ThreadFactory { runnable ->
            // daemon threads: a leaked server must never keep the test JVM alive
            Thread(runnable, "test-http-server").apply { isDaemon = true }
        },
    )

    init {
        server.createContext("/$fileName") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/$fileName"

    val port: Int get() = server.address.port

    private fun handle(exchange: HttpExchange) {
        onRequest?.invoke(exchange)
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        val headers = exchange.responseHeaders
        headers.add("Accept-Ranges", if (supportRange) "bytes" else "none")
        headers.add("ETag", "\"test-etag\"")
        headers.add("Content-Type", "application/octet-stream")

        val range = if (supportRange) parseRange(rangeHeader) else null
        if (range != null) {
            val (start, endInclusive) = range
            val length = (endInclusive - start + 1).toInt()
            headers.add("Content-Range", "bytes $start-$endInclusive/${payload.size}")
            exchange.sendResponseHeaders(206, length.toLong())
            exchange.responseBody.use { it.write(payload, start.toInt(), length) }
        } else {
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
    }

    private fun parseRange(header: String?): Pair<Long, Long>? {
        if (header == null || !header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").trim()
        val startText = spec.substringBefore('-')
        val endText = spec.substringAfter('-')
        val start = startText.toLongOrNull() ?: return null
        val end = endText.toLongOrNull() ?: (payload.size - 1).toLong()
        val clampedEnd = end.coerceAtMost((payload.size - 1).toLong())
        if (start > clampedEnd) return null
        return start to clampedEnd
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
