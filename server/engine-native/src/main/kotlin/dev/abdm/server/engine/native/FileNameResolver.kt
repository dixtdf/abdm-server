package dev.abdm.server.engine.native

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Small helpers around file names coming from URLs and `Content-Disposition`. */
object FileNameResolver {

    private val invalid = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    fun sanitize(candidate: String): String? {
        var name = candidate.trim().trim('"', '\'')
        name = name.substringAfterLast('/').substringAfterLast('\\')
        name = name.substringBefore('?').substringBefore('#')
        name = name.map { if (it in invalid || it.code < 32) '_' else it }.joinToString("")
        name = name.trim().trimEnd('.')
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.length > 180) {
            val ext = name.substringAfterLast('.', "")
            val base = name.substringBeforeLast('.', name).take(120)
            name = if (ext.isEmpty()) base else "$base.$ext".take(180)
        }
        return name
    }

    /** RFC 6266 / RFC 5987 aware `Content-Disposition` parsing. */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.split(';').map { it.trim() }
        for (part in parts) {
            val lower = part.lowercase()
            if (lower.startsWith("filename*=")) {
                val value = part.substringAfter('=').trim()
                val encoded = value.substringAfter("''", value)
                return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8) }.getOrNull()
                    ?.let { sanitize(it) }
            }
        }
        for (part in parts) {
            val lower = part.lowercase()
            if (lower.startsWith("filename=")) {
                return sanitize(part.substringAfter('='))
            }
        }
        return null
    }

    /** Last resort: derive a name from the URL path. */
    fun fromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val last = path.trimEnd('/').substringAfterLast('/')
        return sanitize(last) ?: "download.bin"
    }
}
