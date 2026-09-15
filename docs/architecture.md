# Architecture

`abdm-server` is a single-process, self-hosted download manager: one JVM serves
the REST/WebSocket API and the built Vue frontend, and drives downloads through
an engine behind a narrow port interface.

```
+---------------------------------------------------------------------------------+
| Browser (LAN)                                                                    |
|   Vue 3 + Vite SPA  ->  REST /api/v1/*   +   WebSocket /api/v1/events            |
+--------------------------------------|------------------------------------------+
                                       | HTTP + WS on :8080
+--------------------------------------v------------------------------------------+
| server:app            Ktor (CIO) bootstrap, wiring, static files from ABDM_WEB_DIR|
+----------------------------------------------------------------------------------+
| server:web-api        routing, JSON (kotlinx.serialization), auth, WS hub         |
+----------------------------------------------------------------------------------+
| server:scheduler      queue, maxConcurrentDownloads, ordering, retry policy       |
+----------------------------------------------------------------------------------+
| server:engine-api     DownloadEngine port + TaskSnapshot/EngineEvent/PathGuard    |
+-------------------------------|--------------------------|-----------------------+
                                |                          |
+-------------------------------v-----------+  +-----------v----------------------+
| server:engine-native                      |  | server:engine-abdm               |
| built-in segmented HTTP/HTTPS + HLS       |  | adapter over the upstream engine  |
| always built                             |  | only when abdm.enabled=true       |
+-------------------------------------------+  +-----------|----------------------+
| server:persistence    SQLite (WAL) for tasks, checkpoints, history, settings      |
+-----------------------------------------------------------------------------------+
                                                          |
                              third_party/ab-download-manager (git submodule, pinned,
                              read-only, never modified)
```

The dependency edges only point downwards. Nothing in `web-api`, `scheduler`,
`persistence` or the frontend names a concrete engine.

## Module responsibilities

| Module | Owns | Must never |
| --- | --- | --- |
| `server:app` | Ktor bootstrap, DI wiring, static asset serving, graceful shutdown | contain download logic |
| `server:web-api` | Routes from `docs/api.md`, DTO mapping, auth filter, WebSocket hub | know which engine is running |
| `server:scheduler` | Queue, per-task state machine, concurrency budget, restart recovery | touch sockets or files |
| `server:engine-api` | `DownloadEngine`, `TaskSnapshot`, `EngineEvent`, `DownloadState`, `PathGuard`, storage ports | import vendor types |
| `server:engine-native` | Segmented HTTP/HTTPS, per-connection **parts** (one row per connection, like upstream), live re-partitioning, interval bookkeeping, HLS, speed limit, checksums | expose engine-internal types to the API; lose bytes when the connection count changes |
| `server:engine-abdm` | Translates the upstream AB Download Manager runtime into `DownloadEngine`; compiles a stub when `abdm.enabled=false` | leak upstream types upwards; modify `third_party/` |
| `server:persistence` | SQLite schema, migrations, checkpoint writes, history, settings | hold business rules |
| `web` | Vue 3 SPA, i18n, WebSocket consumption | poll REST for progress |

## Why Ktor/CIO and not Spring Boot

- **Startup and footprint.** The target deployment is a small always-on box or a
  Raspberry Pi. Ktor + CIO boots in well under a second and the fat jar runs in a
  fraction of the memory that a Spring context needs, which matters because the
  container is memory-limited (`MaxRAMPercentage=75`).
- **No magic.** Wiring is explicit constructor code in `server:app`, so the
  object graph is readable in one file and there is no classpath scanning or
  reflection-based proxy layer to reason about when a download stalls.
- **Streaming is native.** CIO gives us a plain HTTP client with full control
  over sockets, range headers and per-connection retries — exactly what the
  segmented engine needs — without fighting a servlet or reactive stack.
- **Coroutines everywhere.** Progress reporting, checkpointing and the
  WebSocket fan-out are all suspend/Flow based, so there is one concurrency
  model rather than threads plus an event loop.

The trade-off is that we write more glue ourselves (routing, serialization,
auth filter) and cannot rely on the Spring ecosystem for ready-made adapters.
For a single-purpose service with a documented API, that is the better deal.

## Ports and adapters

`server:engine-api` is the hexagon's port. It contains:

- `DownloadEngine` — the operations the server may perform (create, start,
  pause, resume, remove, `setConnections`, `get`, `list`, `events`, `shutdown`).
- `EngineCapability` — optional behaviours (`HTTP_RANGE`, `HLS`, `RESUME`,
  `DYNAMIC_CONNECTIONS`, `SPEED_LIMIT`, `CHECKSUM`, `PROXY`, `CUSTOM_HEADERS`)
  so the API can report `ENGINE_UNAVAILABLE`/`UNSUPPORTED_OPERATION` instead of
  pretending a capability exists.
- `DownloadState` — the single engine-independent state set. A vendor status
  enum never reaches the wire.
- `EngineEvent` — the event algebra that `web-api` maps onto the
  `download.*` WebSocket envelopes.
- Storage ports (`TaskRepository`, `SettingsRepository`,
  `HostProfileRepository`) implemented by `server:persistence`.

Two adapters fill that port, selected by `ABDM_ENGINE` (`native` | `abdm`).
Adding a third engine — or migrating from ABDM 1.x to 2.x — is a new adapter
plus a wiring branch; no route, DTO, database column or frontend component
changes.

The build-time switch is `abdm.enabled` (default `false`). With `false`,
`server:engine-abdm` compiles a stub and the server only ever constructs the
native engine, so CI and the released image need no Android SDK. With `true`
the adapter compiles against the jars exported into `third_party/abdm-dist/` by
`scripts/build-abdm-bridge.sh` (that export is the step that needs a JDK 25 and
an Android SDK — see `docs/upstream.md`).

## The upstream submodule is never modified

`third_party/ab-download-manager` is a pinned git submodule at commit
`afc57634b3c121c6415213242b2b600cccc6fd6e` (tag line `v1.10.4`). Rules:

1. No file inside `third_party/` is ever edited, patched or generated by this
   repository. `scripts/check-abdm.sh` fails the build if the submodule
   working tree is dirty.
2. Adaptation happens in `server:engine-abdm` only — translation layer,
   not vendored patches.
3. Moving the pin is a deliberate, reviewed act: `scripts/update-abdm.sh`,
   a re-export through `scripts/build-abdm-bridge.sh`, then
   `AbdmCompatibilityTest`. See `docs/upstream.md`.
4. The bridge export (`third_party/abdm-dist/`) is a build output of that
   script; the script itself is the only thing that writes there, and upstream
   stays untouched.

This keeps license obligations simple and makes an upstream upgrade a
mechanical, testable operation instead of a merge.

## Threading and coroutine model

- **Ktor CIO** runs on a small event-loop worker pool; every request handler is
  a suspend function and never blocks a carrier thread.
- **One structured scope per task.** Each running download lives in a
  `CoroutineScope` owned by the scheduler. Cancelling the scope is how a pause
  is implemented: the in-flight workers stop, the last confirmed interval set is
  checkpointed, and the task moves to `PAUSED`.
- **N workers per task** (`connections`, 1..256) are children of that scope.
  Each worker owns one byte range and writes into a preallocated file region via
  a positioned write, so workers never need a lock on the file handle.
- **Progress is pushed, not polled.** Workers feed a conflated progress channel;
  a single collector throttles by `settings.progressIntervalMs` (default 500 ms)
  before emitting `EngineEvent.Progress`, which `web-api` fans out to WebSocket
  subscribers. The frontend never polls for progress.
- **Blocking I/O is confined.** SQLite JDBC is blocking, so persistence calls
  run on `Dispatchers.IO`; network reads/writes use CIO's own dispatcher.
- **Bounded queues.** The scheduler holds at most `maxConcurrentDownloads`
  active tasks; the rest stay `QUEUED` with a `queuePosition`. A single global
  speed limiter backs `globalSpeedLimit` and per-task `speedLimit`.
- **No shared mutable vendor state.** The ABDM adapter marshals calls onto
  upstream's own dispatchers and subscribes to its flows; the engine's types do
  not escape the adapter.

## Restart recovery

State on disk: the SQLite database at `/config/database.sqlite` (WAL mode) and
the partially written payload files under `/downloads`.

1. `server:persistence` opens the database and loads all tasks
   (`TaskRepository.loadAll`), including the checkpointed completed intervals.
2. For every task the native engine can describe (`supportsRange`), the set of
   bytes already present is recomputed from the checkpointed interval set.
3. Tasks that were `DOWNLOADING`/`CONNECTING`/`COMPLETING` at shutdown are moved
   through `RECOVERING`, then either resumed (when `settings.resumeOnStartup` is
   `true`) or parked in `PAUSED`.
4. The missing intervals are recomputed and handed to N fresh workers — the same
   code path as changing `connections` on a live task, because both reduce to
   "recompute the missing ranges and redistribute them".
5. If the server cannot validate the on-disk file (size mismatch, ETag change
   when the server does not support ranges), the task is restarted from zero and
   the event stream reports the state transition.

Checkpoints are cheap and frequent: only the progress columns (downloaded bytes
plus the encoded interval set) are written, not the whole row. WAL mode keeps
those writes from blocking readers, so a crash loses at most one checkpoint
interval of work.

Recovery is deliberately single-writer: two servers must never share a
`/config` directory.

## Path safety

All filesystem access through the API goes through `PathGuard`:

- `downloadRoot` from `Settings` is the only writable root
  (`ABDM_DOWNLOAD_ROOT`, `/downloads` in the image).
- `resolveWithin(root, candidate)` normalizes the candidate (`toAbsolutePath()`
  + `normalize()`) and rejects anything that does not `startsWith` the
  normalized root, raising `PATH_OUTSIDE_DOWNLOAD_ROOT`. `../` traversal,
  absolute escapes (`/etc/passwd`) and symlink-free normalization tricks are
  all covered by that single check.
- `resolveFileWithin(root, folder, fileName)` applies the same rule to the
  folder + file pair, and file names are sanitized separately
  (`FileNameResolver`) so a server-supplied `Content-Disposition` cannot escape
  the folder.
- `GET /api/v1/directories` only lists below the root and returns
  `DOWNLOAD_DIRECTORY_NOT_FOUND` otherwise.
- Nothing outside `downloadRoot` and `/config` is ever written; `/config` is not
  addressable through the API at all.

These rules hold even with `AUTH_MODE=none`, because LAN-first is not a
guarantee about who is on the LAN.

## Multi-arch story

- The image is built for `linux/amd64` and `linux/arm64` from the same
  Dockerfile via buildx plus QEMU (`.github/workflows/docker.yml`,
  `release.yml`).
- Nothing in the JVM stack is architecture specific: the fat jar is pure JVM
  bytecode, and `sqlite-jdbc` ships both the amd64 and aarch64 native bundles,
  so the same jar runs on a NAS, an x86 server or an ARM SBC.
- The frontend is static output copied into the image at `/app/web`; there is no
  per-arch web build.
- Only one Dockerfile and one jar exist, so there is no risk of the two
  architectures drifting.
