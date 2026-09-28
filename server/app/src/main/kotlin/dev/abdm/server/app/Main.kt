package dev.abdm.server.app

import dev.abdm.server.engine.abdm.AbdmEngineBridge
import dev.abdm.server.engine.abdm.AbdmEngineConfig
import dev.abdm.server.engine.abdm.AbdmEngineFactory
import dev.abdm.server.engine.api.Connections
import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.DownloadState
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.SettingsRepository
import dev.abdm.server.engine.api.TaskRepository
import dev.abdm.server.persistence.SqliteQueueRepository
import dev.abdm.server.persistence.SqliteSettingsRepository
import dev.abdm.server.persistence.SqliteStore
import dev.abdm.server.persistence.SqliteTaskRepository
import dev.abdm.server.scheduler.DownloadScheduler
import dev.abdm.server.webapi.WebApi
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Entry point of the headless server.
 *
 * Boot order: config -> SQLite -> engine (+ adapter) -> scheduler -> REST/WebSocket
 * -> static frontend -> restart recovery.
 */
fun main(args: Array<String>) {
    // `--print-version` lets the release pipeline prove that the built jar carries the
    // version it is about to publish.
    if (args.any { it == "--print-version" || it == "-V" || it == "--version" }) {
        println(serverVersion())
        return
    }

    if (!System.getenv("ABDM_ENGINE").isNullOrBlank()) {
        System.err.println("ABDM_ENGINE is obsolete and ignored; the upstream ABDM engine is always used.")
    }
    val config = AppConfig.fromEnvironment()
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", config.logLevel)

    val server = DownloadServer(config)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { server.stop() }
        },
    )
    runBlocking { server.start(wait = true) }
}

/**
 * Version of this build: the jar manifest first (so a release build reports the version
 * it was released as), falling back to the version this source tree was developed as.
 */
internal fun serverVersion(): String =
    DownloadServer::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() }
        ?: FALLBACK_VERSION

// The former native engine generated 16-character lowercase hexadecimal task IDs;
// upstream ABDM uses decimal IDs and keeps its own part metadata. They cannot be
// resumed by merely pointing the new engine at the same SQLite database.
private val NATIVE_TASK_ID = Regex("^[0-9a-f]{16}$")

class DownloadServer(private val config: AppConfig) {

    private val log = LoggerFactory.getLogger(DownloadServer::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startedAt = System.currentTimeMillis()

    private lateinit var store: SqliteStore
    private lateinit var engine: DownloadEngine
    private lateinit var scheduler: DownloadScheduler
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var queueRepository: SqliteQueueRepository

    @Volatile
    private var settings: ServerSettings = ServerSettings()

    private var server: EmbeddedServer<*, *>? = null
    private var stopped = false

    suspend fun start(wait: Boolean) {
        config.ensureDirectories()

        store = SqliteStore(config.databasePath).apply { open() }
        taskRepository = SqliteTaskRepository(store)
        settingsRepository = SqliteSettingsRepository(store)
        queueRepository = SqliteQueueRepository(store)

        settings = settingsRepository.load().let { stored ->
            stored.copy(
                downloadRoot = config.downloadRoot.toString(),
                defaultFolder = stored.defaultFolder.takeIf { it.startsWith(config.downloadRoot.toString()) }
                    ?: config.downloadRoot.toString(),
                authMode = config.authMode,
            )
        }
        settingsRepository.save(settings)

        val nativeCount = taskRepository.loadAll().count { NATIVE_TASK_ID.matches(it.id) }
        check(nativeCount == 0) {
            "Cannot start ABDM: $nativeCount legacy native task(s) remain in " +
                "${config.configDir}. Back up /config and /downloads, then migrate or " +
                "remove those records before starting this ABDM-only build."
        }
        engine = createEngine()
        scheduler = DownloadScheduler(
            engine = engine,
            scope = scope,
            settingsProvider = { settings },
            queueStore = object : DownloadScheduler.QueuePersistence {
                override suspend fun save(ids: List<String>) = queueRepository.save(ids)
                override suspend fun load(): List<String> = queueRepository.load()
            },
        )
        engine.list().also { log.info("engine {} ready with {} task(s)", engine.name, it.size) }

        val webApi = WebApi(
            engine = engine,
            scheduler = scheduler,
            settings = settingsRepository,
            tasks = taskRepository,
            config = WebApi.ApiConfig(
                version = serverVersion(),
                apiVersion = API_VERSION,
                startedAt = startedAt,
                authMode = config.authMode,
                authToken = config.authToken,
                onSettingsChanged = { updated ->
                    settings = updated
                    applyEngineSettings(updated)
                },
            ),
            scope = scope,
        )

        server = embeddedServer(CIO, port = config.port, host = config.host) {
            install(DefaultHeaders)
            install(CORS) {
                anyHost()
                allowNonSimpleContentTypes = true
                allowHeader(io.ktor.http.HttpHeaders.ContentType)
                allowHeader(io.ktor.http.HttpHeaders.Authorization)
                allowMethod(io.ktor.http.HttpMethod.Options)
                allowMethod(io.ktor.http.HttpMethod.Put)
                allowMethod(io.ktor.http.HttpMethod.Patch)
                allowMethod(io.ktor.http.HttpMethod.Delete)
            }
            webApi.install(this)
            installStaticFrontend()
        }.start(wait = false)

        banner()

        scheduler.start()
        restore()
        if (wait) {
            Thread.currentThread().join()
        }
    }

    private suspend fun restore() {
        val resumable = (engine as AbdmEngineBridge).boot()
        if (resumable.isNotEmpty()) {
            log.info("ABDM engine restored {} unfinished task(s)", resumable.size)
        }
        scheduler.restore()
        if (settings.resumeOnStartup) {
            if (resumable.isNotEmpty()) {
                log.info("resume downloads after restart: {}", resumable)
                resumable.forEach { scheduler.submit(it) }
            }
        } else {
            log.info("resume downloads after restart is disabled (settings.resumeOnStartup=false)")
        }
    }

    private fun createEngine(): DownloadEngine {
        log.info("using the AB Download Manager engine ({})", AbdmEngineFactory.descriptor().upstreamVersion)
        return AbdmEngineFactory.create(
            config = AbdmEngineConfig(
                downloadRoot = config.downloadRoot,
                dataFolder = config.abdmDataFolder,
                defaultConnections = settings.defaultConnections,
                progressIntervalMs = settings.progressIntervalMs,
            ),
            scope = scope,
            repository = taskRepository,
            settingsProvider = { settings },
        )
    }

    private fun applyEngineSettings(updated: ServerSettings) {
        (engine as AbdmEngineBridge).applySettings(updated)
    }

    suspend fun stop() {
        if (stopped) return
        stopped = true
        log.info("shutting down: flushing download checkpoints")
        runCatching { scheduler.stop() }
        runCatching { engine.shutdown() }
        runCatching { server?.stop(500, 2_000) }
        runCatching { store.close() }
        log.info("bye")
    }

    private fun banner() {
        val descriptor = engine.descriptor
        val lines = buildList {
            add("")
            add("  abdm-server $VERSION  (api $API_VERSION)")
            add("  engine      : ${descriptor.name} ${descriptor.version}" +
                (descriptor.upstreamVersion?.let { " <- ${descriptor.upstreamName} $it / ${descriptor.upstreamCommit}" } ?: ""))
            add("  listening   : http://${config.host}:${config.port}")
            add("  config dir  : ${config.configDir}")
            add("  downloads   : ${config.downloadRoot}")
            add("  web assets  : ${config.webDir ?: "embedded in the jar"}")
            add("  connections : ${Connections.MIN}..${Connections.MAX} (default ${settings.defaultConnections})")
            if (config.authMode == "none") {
                add("")
                add("  !! Authentication is disabled.")
                add("  !! Do not expose this service directly to the Internet.")
                add("     Use AUTH_MODE=token, or put it behind Tailscale/WireGuard/a reverse proxy.")
            } else {
                add("  auth        : bearer token required")
            }
            add("")
        }
        lines.forEach { println(it) }
    }

    // ------------------------------------------------------------------ frontend

    private fun Application.installStaticFrontend() {
        val webDir = config.webDir
        val indexFile: Path? = webDir?.resolve("index.html")?.takeIf { Files.isRegularFile(it) }

        routing {
            get("/") { call.respondIndexOrAsset("", indexFile) }
            get("/{path...}") {
                val path = call.request.path()
                if (path.startsWith("/api/")) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.respondIndexOrAsset(path.trimStart('/'), indexFile)
            }
        }
    }

    /**
     * Serves the single page app.
     *
     * Asset resolution is explicit (filesystem first, jar resources second) instead of
     * relying on the static file plugin: a catch-all route would otherwise shadow the
     * asset directory and answer every script with `index.html`.
     */
    private suspend fun ApplicationCall.respondIndexOrAsset(relative: String, indexFile: Path?) {
        if (relative.isNotEmpty() && relative.contains('.')) {
            val bytes = readAsset(relative)
            if (bytes != null) {
                respondBytes(bytes, contentTypeFor(relative), HttpStatusCode.OK)
                return
            }
        }
        val html = indexHtml(indexFile)
        if (html == null) {
            respondText(
                "abdm-server is running, but no web assets were found. " +
                    "Build the frontend (cd web && npm install && npm run build) or set ABDM_WEB_DIR.",
                ContentType.Text.Plain,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        respondText(html, ContentType.Text.Html)
    }

    private fun readAsset(relative: String): ByteArray? {
        if (relative.contains("..")) return null
        val webDir = config.webDir
        if (webDir != null) {
            val candidate = webDir.resolve(relative).normalize()
            if (candidate.startsWith(webDir) && Files.isRegularFile(candidate)) {
                return Files.readAllBytes(candidate)
            }
        }
        return javaClass.classLoader.getResourceAsStream("web/$relative")?.use { it.readBytes() }
    }

    private fun indexHtml(indexFile: Path?): String? = when {
        indexFile != null -> Files.readString(indexFile)
        else -> javaClass.classLoader.getResourceAsStream("web/index.html")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
    }

    private fun contentTypeFor(relative: String): ContentType = when (relative.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> ContentType.Application.JavaScript
        "css" -> ContentType.Text.CSS
        "html" -> ContentType.Text.Html
        "json", "map" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "webp" -> ContentType("image", "webp")
        "ico" -> ContentType.Image.XIcon
        "woff2" -> ContentType("font", "woff2")
        "woff" -> ContentType("font", "woff")
        "txt" -> ContentType.Text.Plain
        else -> ContentType.Application.OctetStream
    }

    private companion object {
        val VERSION: String = serverVersion()
        const val API_VERSION = "v1"
    }
}

/** Version this source tree declares; a release jar overrides it from the manifest. */
private const val FALLBACK_VERSION = "1.0.2"
