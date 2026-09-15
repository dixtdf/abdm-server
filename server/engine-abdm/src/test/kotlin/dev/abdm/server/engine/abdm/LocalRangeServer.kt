package dev.abdm.server.engine.abdm

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Range capable HTTP server used by [AbdmCompatibilityTest].
 *
 * Upstream's own test advice is to download from a public mirror, which makes the
 * compatibility job network dependent and flaky. Serving the payload locally keeps
 * the test *deterministic* while still going through the real ABDM engine
 * (OkHttp, part splitting, resume, connection changes).
 */
internal class LocalRangeServer(
    private val payload: ByteArray,
    private val fileName: String = "payload.bin",
    /** Aggregate cap across all connections; 0 = unlimited. Slows the transfer down
     *  enough for pause/resume assertions to be meaningful on localhost. */
    private val bytesPerSecond: Long = 8L * 1024 * 1024,
) : AutoCloseable {

    private var windowStart = System.currentTimeMillis()
    private var windowBytes = 0L
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 256)
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        64,
        ThreadFactory { runnable -> Thread(runnable, "abdm-compat-server").apply { isDaemon = true } },
    )

    init {
        server.createContext("/$fileName") { exchange -> exchange.use { handle(it) } }
        server.executor = executor
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/$fileName"

    private fun handle(exchange: com.sun.net.httpserver.HttpExchange) {
        val headers = exchange.responseHeaders
        headers.add("Accept-Ranges", "bytes")
        headers.add("Content-Type", "application/octet-stream")
        headers.add("Content-Disposition", "attachment; filename=\"$fileName\"")
        headers.add("ETag", "\"compat-$fileName\"")

        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        val range = parse(rangeHeader)
        if (range != null) {
            val (start, end) = range
            val length = (end - start + 1).toInt()
            headers.add("Content-Range", "bytes $start-$end/${payload.size}")
            exchange.sendResponseHeaders(206, length.toLong())
            exchange.responseBody.use { it.writeThrottled(payload, start.toInt(), length) }
        } else {
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.writeThrottled(payload, 0, payload.size) }
        }
    }

    private fun java.io.OutputStream.writeThrottled(source: ByteArray, offset: Int, length: Int) {
        val block = 64 * 1024
        var written = 0
        while (written < length) {
            val chunk = minOf(block, length - written)
            write(source, offset + written, chunk)
            flush()
            written += chunk
            throttle(chunk)
        }
    }

    @Synchronized
    private fun throttle(chunk: Int) {
        if (bytesPerSecond <= 0) return
        val now = System.currentTimeMillis()
        if (now - windowStart > 5_000) {
            windowStart = now
            windowBytes = 0
        }
        windowBytes += chunk
        val expectedMillis = windowBytes * 1000 / bytesPerSecond
        val elapsed = now - windowStart
        if (expectedMillis > elapsed) {
            Thread.sleep(expectedMillis - elapsed)
        }
    }

    private fun parse(header: String?): Pair<Long, Long>? {
        if (header == null || !header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").trim()
        val start = spec.substringBefore('-').toLongOrNull() ?: return null
        val endText = spec.substringAfter('-')
        val end = if (endText.isBlank()) payload.size - 1L else endText.toLongOrNull() ?: return null
        val clamped = end.coerceAtMost(payload.size - 1L)
        if (start > clamped) return null
        return start to clamped
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
