package dev.abdm.server.engine.api

import java.nio.file.Path

/**
 * Path containment rules for everything the API can write to.
 *
 * The service is LAN-first, but path traversal is never acceptable: `../../etc/passwd`
 * must be rejected even on a trusted network.
 */
object PathGuard {

    fun resolveRoot(raw: String): Path = Path.of(raw).toAbsolutePath().normalize()

    /**
     * Resolves [candidate] (absolute or relative to [root]).
     *
     * @throws EngineException [EngineErrorCode.PATH_OUTSIDE_DOWNLOAD_ROOT] when the
     *         normalized result escapes [root].
     */
    fun resolveWithin(root: Path, candidate: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val target = if (Path.of(candidate).isAbsolute) {
            Path.of(candidate)
        } else {
            normalizedRoot.resolve(candidate)
        }
        val resolved = target.toAbsolutePath().normalize()
        if (!resolved.startsWith(normalizedRoot)) {
            throw EngineException(
                EngineErrorCode.PATH_OUTSIDE_DOWNLOAD_ROOT,
                mapOf("path" to candidate, "root" to normalizedRoot.toString()),
            )
        }
        return resolved
    }

    /** Same as [resolveWithin] but does not require the path to exist yet. */
    fun resolveFileWithin(root: Path, folder: String, fileName: String): Path =
        resolveWithin(root, Path.of(folder, fileName).toString())
}
