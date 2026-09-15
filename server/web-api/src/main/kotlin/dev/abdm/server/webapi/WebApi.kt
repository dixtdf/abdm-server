package dev.abdm.server.webapi

import dev.abdm.server.engine.api.Connections
import dev.abdm.server.engine.api.DownloadEngine
import dev.abdm.server.engine.api.EngineError
import dev.abdm.server.engine.api.EngineErrorCode
import dev.abdm.server.engine.api.EngineEvent
import dev.abdm.server.engine.api.EngineException
import dev.abdm.server.engine.api.PathGuard
import dev.abdm.server.engine.api.ServerSettings
import dev.abdm.server.engine.api.SettingsRepository
import dev.abdm.server.engine.api.TaskRepository
import dev.abdm.server.engine.api.toEngineError
import dev.abdm.server.scheduler.DownloadScheduler
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * REST + WebSocket surface.
 *
 * Everything a client needs is exposed here; no user facing sentence is ever
 * built on the server (see `docs/api.md`).
 */
class WebApi(
    private val engine: DownloadEngine,
    private val scheduler: DownloadScheduler,
    private val settings: SettingsRepository,
    private val tasks: TaskRepository,
    private val config: ApiConfig,
    private val scope: CoroutineScope,
) {

    data class ApiConfig(
        val version: String,
        val apiVersion: String = "v1",
        val startedAt: Long = System.currentTimeMillis(),
        val authMode: String = "none",
        val authToken: String? = null,
        val onSettingsChanged: suspend (ServerSettings) -> Unit = {},
    )

    private val log = LoggerFactory.getLogger(WebApi::class.java)
    private val json = Json {
        prettyPrint = false
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun install(app: Application) {
        app.install(ContentNegotiation) { json(json) }
        app.install(WebSockets)
        app.install(StatusPages) {
            exception<EngineException> { call, cause ->
                call.respond(cause.error.toStatus(), ErrorEnvelope(cause.error.toDto()))
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(ErrorDto(EngineErrorCode.INVALID_URL, emptyMap(), cause.message)),
                )
            }
            exception<Throwable> { call, cause ->
                log.error("unhandled api error on {}", call.request.path(), cause)
                val error = cause.toEngineError()
                call.respond(error.toStatus(), ErrorEnvelope(error.toDto()))
            }
        }
        if (config.authMode == "token") {
            log.info("API authentication enabled (AUTH_MODE=token)")
            app.intercept(ApplicationCallPipeline.Plugins) {
                val call = context
                val path = call.request.path()
                if (!path.startsWith("/api/") || path == "/api/v1/health") {
                    return@intercept
                }
                if (!call.authorized()) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorEnvelope(ErrorDto(EngineErrorCode.UNAUTHORIZED)),
                    )
                    finish()
                }
            }
        } else {
            log.warn(
                "Authentication is disabled. Do not expose this service directly to the Internet.",
            )
        }
        app.routing {
            route("/api/v1") {
                health()
                version()
                settingsRoutes()
                directoryRoutes()
                downloadRoutes()
                historyRoutes()
                eventStream()
            }
        }
    }

    // ------------------------------------------------------------------ routes

    private fun io.ktor.server.routing.Route.health() = get("/health") {
        call.respond(
            HealthDto(
                status = "ok",
                uptimeSeconds = (System.currentTimeMillis() - config.startedAt) / 1000,
                version = config.version,
                engine = engine.name,
            ),
        )
    }

    private fun io.ktor.server.routing.Route.version() = get("/version") {
        val descriptor = engine.descriptor
        call.respond(
            VersionDto(
                version = config.version,
                apiVersion = config.apiVersion,
                engine = descriptor.name,
                engineVersion = descriptor.version,
                engineVendor = descriptor.vendor,
                upstreamName = descriptor.upstreamName,
                upstreamVersion = descriptor.upstreamVersion,
                upstreamCommit = descriptor.upstreamCommit,
                capabilities = descriptor.capabilities.map { it.name }.sorted(),
                java = System.getProperty("java.version"),
                os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                startedAt = config.startedAt,
            ),
        )
    }

    private fun io.ktor.server.routing.Route.settingsRoutes() {
        get("/settings") {
            call.respond(settings.load().toDto())
        }
        put("/settings") {
            val patch = call.receive<SettingsPatchDto>()
            val current = settings.load()
            val updated = current.copy(
                downloadRoot = patch.downloadRoot?.takeIf { it.isNotBlank() } ?: current.downloadRoot,
                defaultFolder = patch.defaultFolder?.takeIf { it.isNotBlank() } ?: current.defaultFolder,
                defaultConnections = patch.defaultConnections?.let { Connections.coerce(it) } ?: current.defaultConnections,
                maxConcurrentDownloads = patch.maxConcurrentDownloads?.coerceIn(1, 32) ?: current.maxConcurrentDownloads,
                globalSpeedLimit = patch.globalSpeedLimit?.coerceAtLeast(0) ?: current.globalSpeedLimit,
                resumeOnStartup = patch.resumeOnStartup ?: current.resumeOnStartup,
                progressIntervalMs = patch.progressIntervalMs?.coerceIn(100, 5_000) ?: current.progressIntervalMs,
            )
            val root = PathGuard.resolveRoot(updated.downloadRoot)
            if (!Files.isDirectory(root)) {
                throw EngineException(
                    EngineErrorCode.DOWNLOAD_DIRECTORY_NOT_FOUND,
                    mapOf("path" to root.toString()),
                )
            }
            settings.save(updated)
            config.onSettingsChanged(updated)
            scheduler.refresh()
            call.respond(updated.toDto())
        }
    }

    private fun io.ktor.server.routing.Route.directoryRoutes() = get("/directories") {
        val current = settings.load()
        val root = PathGuard.resolveRoot(current.downloadRoot)
        val requested = call.request.queryParameters["path"]?.takeIf { it.isNotBlank() } ?: current.defaultFolder
        val path = PathGuard.resolveWithin(root, requested)
        if (!Files.isDirectory(path)) {
            throw EngineException(
                EngineErrorCode.DOWNLOAD_DIRECTORY_NOT_FOUND,
                mapOf("path" to path.toString()),
            )
        }
        val children = Files.list(path).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .sorted()
                .limit(500)
                .map { DirectoryEntryDto(name = it.fileName.toString(), path = it.toAbsolutePath().toString()) }
                .toList()
        }
        val parent = path.parent?.takeIf { it.startsWith(root) }?.toAbsolutePath()?.toString()
            ?: root.toAbsolutePath().toString().takeIf { path != root }
        call.respond(
            DirectoryListingDto(
                path = path.toAbsolutePath().toString(),
                parent = parent,
                directories = children,
            ),
        )
    }

    private fun io.ktor.server.routing.Route.downloadRoutes() {
        get("/downloads") {
            call.respond(listWithPositions())
        }
        post("/downloads") {
            val dto = call.receive<CreateDownloadDto>()
            val id = engine.create(dto.toRequest())
            if (dto.startImmediately) {
                scheduler.submit(id)
            } else {
                scheduler.hold(id)
            }
            val snapshot = engine.get(id)
                ?: throw EngineException(EngineErrorCode.TASK_NOT_FOUND, mapOf("id" to id))
            call.respond(
                HttpStatusCode.Created,
                snapshot.toDto(scheduler.positions()[id]),
            )
        }
        get("/downloads/{id}") {
            val snapshot = engine.get(call.requireId())
                ?: throw EngineException(EngineErrorCode.TASK_NOT_FOUND, mapOf("id" to call.requireId()))
            call.respond(snapshot.toDto(scheduler.positions()[snapshot.id]))
        }
        delete("/downloads/{id}") {
            val id = call.requireId()
            val deleteFile = call.request.queryParameters["deleteFile"]?.toBooleanStrictOrNull() ?: false
            engine.remove(id, deleteFile)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/downloads/{id}/start") {
            val id = call.requireId()
            scheduler.submit(id)
            call.respond(requireSnapshot(id))
        }
        post("/downloads/{id}/pause") {
            val id = call.requireId()
            engine.pause(id)
            call.respond(requireSnapshot(id))
        }
        post("/downloads/{id}/resume") {
            val id = call.requireId()
            scheduler.submit(id)
            call.respond(requireSnapshot(id))
        }
        patch("/downloads/{id}/connections") {
            val id = call.requireId()
            val body = call.receive<ConnectionsDto>()
            engine.setConnections(id, body.connections)
            call.respond(requireSnapshot(id))
        }
    }

    private fun io.ktor.server.routing.Route.historyRoutes() {
        get("/history") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            call.respond(tasks.history(limit).map { it.toDto() })
        }
        delete("/history") {
            tasks.clearHistory()
            call.respond(HttpStatusCode.NoContent)
        }
    }

    private fun io.ktor.server.routing.Route.eventStream() = webSocket("/events") {
        if (config.authMode == "token" && !webSocketAuthorized()) {
            outgoing.send(Frame.Close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized")))
            return@webSocket
        }
        val descriptor = engine.descriptor
        val hello = EventEnvelope(
            type = "hello",
            server = ServerHelloDto(version = config.version, engine = descriptor.name, apiVersion = config.apiVersion),
            tasks = listWithPositions(),
        )
        send(Frame.Text(json.encodeToString(EventEnvelope.serializer(), hello)))

        val forwarder = scope.launch {
            engine.events().collect { event ->
                val envelope = event.toEnvelope() ?: return@collect
                runCatching { send(Frame.Text(json.encodeToString(EventEnvelope.serializer(), envelope))) }
            }
        }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text && frame.readText().contains("\"ping\"")) {
                    send(Frame.Text("""{"type":"pong"}"""))
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // client went away
        } finally {
            forwarder.cancel()
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun listWithPositions(): List<TaskDto> {
        val positions = scheduler.positions()
        return engine.list().map { it.toDto(positions[it.id]) }
    }

    private suspend fun requireSnapshot(id: String): TaskDto {
        val snapshot = engine.get(id) ?: throw EngineException(EngineErrorCode.TASK_NOT_FOUND, mapOf("id" to id))
        return snapshot.toDto(scheduler.positions()[id])
    }

    private fun EngineEvent.toEnvelope(): EventEnvelope? = when (this) {
        is EngineEvent.Added -> EventEnvelope(type = "download.added", taskId = taskId, task = task.toDto())
        is EngineEvent.Progress -> EventEnvelope(type = "download.progress", taskId = taskId, progress = progress.toDto())
        is EngineEvent.PartsUpdated -> EventEnvelope(
            type = "download.parts",
            taskId = taskId,
            parts = parts.map { it.toDto() },
        )
        is EngineEvent.StateChanged -> EventEnvelope(
            type = "download.state",
            taskId = taskId,
            task = task.toDto(),
            previous = previous.name,
            state = task.state.name,
            error = error?.toDto(),
        )
        is EngineEvent.ConnectionsChanged -> EventEnvelope(
            type = "download.connections",
            taskId = taskId,
            task = task.toDto(),
            requested = requested,
            active = active,
        )
        is EngineEvent.Removed -> EventEnvelope(
            type = "download.removed",
            taskId = taskId,
            deletedFile = deletedFile,
        )
    }

    private fun ApplicationCall.requireId(): String = parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw EngineException(EngineErrorCode.TASK_NOT_FOUND, mapOf("id" to ""))

    private fun ApplicationCall.authorized(): Boolean {
        val header = request.headers["Authorization"] ?: return false
        val token = config.authToken ?: return false
        return header.equals("Bearer $token", ignoreCase = true)
    }

    private fun DefaultWebSocketServerSession.webSocketAuthorized(): Boolean {
        val token = config.authToken ?: return false
        val queryToken = call.request.queryParameters["token"]
        if (queryToken == token) return true
        val header = call.request.headers["Authorization"] ?: return false
        return header.equals("Bearer $token", ignoreCase = true)
    }

    private fun EngineError.toStatus(): HttpStatusCode = when (code) {
        EngineErrorCode.INVALID_URL,
        EngineErrorCode.UNSUPPORTED_SCHEME,
        EngineErrorCode.INVALID_CONNECTION_COUNT,
        -> HttpStatusCode.BadRequest

        EngineErrorCode.TASK_NOT_FOUND,
        EngineErrorCode.DOWNLOAD_DIRECTORY_NOT_FOUND,
        EngineErrorCode.PATH_OUTSIDE_DOWNLOAD_ROOT,
        -> HttpStatusCode.NotFound

        EngineErrorCode.TASK_ALREADY_EXISTS -> HttpStatusCode.Conflict

        EngineErrorCode.UNSUPPORTED_OPERATION,
        EngineErrorCode.ENGINE_UNAVAILABLE,
        -> HttpStatusCode.NotImplemented

        EngineErrorCode.DISK_FULL -> HttpStatusCode.InsufficientStorage

        else -> HttpStatusCode.InternalServerError
    }
}
