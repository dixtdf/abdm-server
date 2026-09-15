package dev.abdm.server.engine.native

import dev.abdm.server.engine.api.EngineErrorCode
import dev.abdm.server.engine.api.EngineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal but functional HLS support.
 *
 * Master playlists are resolved to the highest bandwidth variant, media playlists
 * are fetched with a sliding window of `connections` segments and written to the
 * output file strictly in order (so the resulting `.ts` is playable).
 *
 * AES-128 encrypted playlists are reported as unsupported instead of producing a
 * broken file.
 */
internal class HlsDownloader(private val engine: NativeDownloadEngine) {

    private data class Segment(val uri: URI, val durationSeconds: Double)

    suspend fun download(task: DownloadTaskRunner) {
        val client = engine.client(task.request)
        val masterUri = engine.uri(task.request.url)
        var playlistUri = masterUri
        var playlist = fetchText(client, masterUri, task)

        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val variants = parseMaster(playlist, masterUri)
            if (variants.isEmpty()) {
                throw EngineException(EngineErrorCode.NETWORK, mapOf("url" to masterUri.toString()), "empty master playlist")
            }
            val best = variants.maxByOrNull { it.second } ?: variants.first()
            playlistUri = best.first
            playlist = fetchText(client, playlistUri, task)
        }

        if (playlist.contains("#EXT-X-KEY") && playlist.contains("AES-128", ignoreCase = true)) {
            throw EngineException(
                EngineErrorCode.UNSUPPORTED_OPERATION,
                mapOf("feature" to "HLS AES-128", "url" to playlistUri.toString()),
            )
        }

        val segments = parseMedia(playlist, playlistUri)
        if (segments.isEmpty()) {
            throw EngineException(EngineErrorCode.NETWORK, mapOf("url" to playlistUri.toString()), "no media segments")
        }
        task.applyTotal(-1)
        task.beginSegments(segments.size)

        val output: OutputStream = Files.newOutputStream(
            task.path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        val written = AtomicInteger(0)
        try {
            val window = task.connections.coerceIn(1, 6)
            val pending = ArrayDeque<Deferred<ByteArray>>()
            for (segment in segments) {
                while (pending.size >= window) {
                    val head = pending.removeFirst()
                    writeSegment(output, head.await(), task, written)
                }
                task.markActiveSegments(1)
                pending.addLast(
                    engine.scope.async {
                        try {
                            fetchBytes(client, segment.uri, task)
                        } finally {
                            task.markActiveSegments(-1)
                        }
                    },
                )
            }
            while (pending.isNotEmpty()) {
                writeSegment(output, pending.removeFirst().await(), task, written)
            }
            output.flush()
        } finally {
            runCatching { output.close() }
        }
        task.applyTotal(Files.size(task.path))
        task.finishHls()
    }

    private fun writeSegment(
        output: OutputStream,
        bytes: ByteArray,
        task: DownloadTaskRunner,
        counter: AtomicInteger,
    ) {
        output.write(bytes)
        output.flush()
        counter.addAndGet(bytes.size)
        task.addBytes(0, bytes.size.toLong(), bytes.size.toLong())
    }

    // ------------------------------------------------------------------ parsing

    private fun parseMaster(text: String, base: URI): List<Pair<URI, Long>> {
        val result = ArrayList<Pair<URI, Long>>()
        val lines = text.lineSequence().map { it.trim() }.toList()
        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val next = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (next != null) result.add(base.resolve(next.trim()) to bandwidth)
            }
        }
        return result
    }

    private fun parseMedia(text: String, base: URI): List<Segment> {
        val result = ArrayList<Segment>()
        var duration = 0.0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    duration = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    result.add(Segment(base.resolve(line), duration))
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------------ io

    private suspend fun fetchText(
        client: java.net.http.HttpClient,
        uri: URI,
        task: DownloadTaskRunner,
    ): String {
        val response = engine.http.openRange(client, uri, task.request, null)
        if (response.statusCode() !in 200..299) {
            throw EngineException(
                EngineErrorCode.NETWORK,
                mapOf("url" to uri.toString(), "status" to response.statusCode().toString()),
            )
        }
        return response.body().use { it.readNBytes(4 * 1024 * 1024).toString(Charsets.UTF_8) }
    }

    private suspend fun fetchBytes(
        client: java.net.http.HttpClient,
        uri: URI,
        task: DownloadTaskRunner,
    ): ByteArray = coroutineScope {
        val response = engine.http.openRange(client, uri, task.request, null)
        if (response.statusCode() !in 200..299) {
            throw EngineException(
                EngineErrorCode.NETWORK,
                mapOf("url" to uri.toString(), "status" to response.statusCode().toString()),
            )
        }
        engine.throttle.acquire(task.id, task.request.speedLimit, 64L * 1024)
        response.body().use { it.readBytes() }
    }
}
