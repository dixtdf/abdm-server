# abdm-server

[简体中文](README.md) | **English**

A self-hosted download manager: one JVM process that serves both the REST/WebSocket API
and a bundled web UI. It is built for LAN-first deployments, uses the built-in segmented
download engine by default, and can switch to AB Download Manager as its backend engine.

- Version: `0.1.0`
- Repository: <https://github.com/dixtdf/abdm-server>
- License: [Apache-2.0](LICENSE)
- Upstream engine: [AB Download Manager](https://github.com/amir1376/ab-download-manager)
  `v1.10.4` (git submodule, pinned commit — see [docs/upstream.md](docs/upstream.md))

---

## Features

- **Segmented downloads with 1–256 connections per task**: range planning plus an
  interval set of completed bytes, so changing the connection count mid-download,
  resuming and restarting all take the same code path.
- **Queue and concurrency control**: `maxConcurrentDownloads` caps parallel downloads;
  the rest are queued and report their `queuePosition`.
- **Live progress over WebSocket** (`download.progress` / `download.state` / …) throttled
  by `progressIntervalMs`. The frontend never polls the REST API for progress.
- **Per connection parts view**: the same "connections / parts" table the ABDM desktop
  client shows (`#` / state / downloaded / total / speed), pushed as `download.parts`.
  Changing the connection count re-partitions the ranges without losing downloaded bytes
  or resetting the numbers.
- **Speed limits**: global `globalSpeedLimit` and per task `speedLimit`.
- **HLS, checksums, proxy, custom headers / cookies / referer / user-agent** per task.
- **Persistence**: SQLite (WAL) stores tasks, progress checkpoints, history and settings;
  unfinished downloads resume after a restart (configurable).
- **Pluggable engine**: built-in `engine-native`, or compile against the `engine-abdm`
  adapter (`-Pabdm.enabled=true`). The API, the database and the frontend never notice
  which engine is running.
- **Bilingual UI**: `en-US` / `zh-CN`, with error codes separated from copy
  (see [docs/i18n.md](docs/i18n.md)).
- **Multi-arch images**: `linux/amd64` + `linux/arm64`.

## Interface

The repository deliberately **ships no screenshots**: the UI is built from `web/`
(Vue 3 + Vite) and consists of the task list, live progress bars, history and settings.
Every string comes from `web/src/locales/*.json`, so you can preview it locally with
`npm run dev` instead of trusting pictures in the docs.

---

## Quick start

### Option 1: Docker Compose (recommended)

1. Create `.env` in the repository root:

   ```dotenv
   IMAGE_OWNER=dixtdf        # or your own fork
   IMAGE_TAG=latest          # or edge / 0.1.0
   DOWNLOAD_HOST_PATH=/mnt/downloads
   AUTH_MODE=none            # or token
   AUTH_TOKEN=               # required when AUTH_MODE=token
   TZ=Asia/Shanghai
   PORT=6868
   ```

2. Prepare the directories once (the image runs as the non-root uid 1000, so
   **no `privileged` is needed**; the host directories must be writable by uid 1000):

   ```bash
   mkdir -p config
   sudo chown -R 1000:1000 config       # same for /downloads
   docker compose up -d
   docker compose logs -f downloader
   ```

   If the permissions are wrong the server refuses to start and prints that command.

3. Verify (the image ships the same health check):

   ```bash
   curl -fsS http://127.0.0.1:6868/api/v1/health
   # {"status":"ok","uptimeSeconds":3,"version":"0.1.0","engine":"native"}
   ```

4. Open `http://<LAN IP>:6868`.

To use a locally built image: `docker build -t abdm-server:local .`, then point `image`
at `abdm-server:local` using the override shown in the comments at the top of
`docker-compose.yml`.

### Option 2: Local development (frontend and backend separately)

Requirements: JDK 17 and Node 22+. The default (`native`) engine needs nothing else. To
build the `engine-abdm` adapter you additionally need JDK 25 (upstream pins
`jvm.toolchain=25`) and an Android SDK, and you must export the upstream runtime first
with `bash scripts/build-abdm-bridge.sh` (see below).

Backend (default `native` engine, port 6868):

```bash
# Linux / macOS
./gradlew :server:app:run

# Windows
.\gradlew.bat :server:app:run
```

Frontend dev server (Vite, port 5173, proxying to 6868):

```bash
cd web
npm ci
npm run dev
```

Build the single fat jar and run it directly:

```bash
./gradlew :server:app:shadowJar
java -jar server/app/build/libs/abdm-server-*-all.jar
```

When running locally you can relocate the directories with environment variables:

```bash
ABDM_CONFIG_DIR=./.local/config ABDM_DOWNLOAD_ROOT=./.local/downloads ./gradlew :server:app:run
```

---

## Configuration

### Environment variables

| Variable | Default | Description |
| --- | --- | --- |
| `PORT` | `6868` | HTTP/WebSocket port |
| `ABDM_CONFIG_DIR` | `/config` | SQLite database and runtime state (must be writable) |
| `ABDM_DOWNLOAD_ROOT` | `/downloads` | Download root; also the path-safety boundary (must be writable) |
| `ABDM_AUTH_MODE` | `none` | `none` or `token`; `none` prints a prominent warning at boot |
| `ABDM_AUTH_TOKEN` | empty | Required with `AUTH_MODE=token`. Every route except `/api/v1/health` needs `Authorization: Bearer <token>`; the WebSocket also accepts `?token=<token>` |
| `ABDM_WEB_DIR` | `/app/web` | Directory holding the built frontend; `/app/web` inside the image |
| `ABDM_ENGINE` | `native` | `native` (built-in engine) or `abdm` (requires a `-Pabdm.enabled=true` build) |
| `ABDM_LOG_LEVEL` | `info` | Log level (`debug` / `info` / `warn` / `error`) |
| `TZ` | `Asia/Shanghai` | Container timezone (logs and display only) |

### Server settings

These are read and written through `GET/PUT /api/v1/settings` and stored in SQLite:

| Setting | Default | Description |
| --- | --- | --- |
| `downloadRoot` | `/downloads` | The only writable root; escaping it returns `PATH_OUTSIDE_DOWNLOAD_ROOT` |
| `defaultFolder` | `/downloads` | Target directory when a new task omits `folder` |
| `defaultConnections` | `8` | Connection count when a new task omits `connections` (1–256) |
| `maxConcurrentDownloads` | `3` | Parallel download limit; the rest wait in the queue |
| `globalSpeedLimit` | `0` | Global limit in bytes/second; `0` means unlimited |
| `resumeOnStartup` | `true` | Resume unfinished tasks at boot (otherwise they stay `PAUSED`) |
| `progressIntervalMs` | `500` | WebSocket progress throttle interval |
| `authMode` | `none` | Mirrors `ABDM_AUTH_MODE` |
| `maxConnections` | `256` | Hard upper bound for a task's connections |

UI language and theme are browser preferences (`ui.locale`, `ui.theme` in `localStorage`),
not server settings.

---

## API summary

The complete contract (endpoints, bodies, state machine, error codes, WebSocket frames)
lives in **[docs/api.md](docs/api.md)** and is the single source of truth followed by both
the frontend and `server:web-api`. Base path `/api/v1`; every error is
`{"error":{"code":"...","params":{...}}}` — the backend never returns localized sentences.

| Group | Endpoints |
| --- | --- |
| Health & version | `GET /health`, `GET /version` |
| Settings | `GET /settings`, `PUT /settings` |
| Directory browsing | `GET /directories?path=/downloads` |
| Tasks | `GET /downloads`, `POST /downloads`, `GET /downloads/{id}`, `DELETE /downloads/{id}?deleteFile=` |
| Task control | `POST /downloads/{id}/start`, `/pause`, `/resume`, `PATCH /downloads/{id}/connections` |
| History | `GET /history?limit=100`, `DELETE /history` |
| Live events | `WS /events` (the first `hello` frame carries every task, then incremental events) |

---

## Security model

This project targets **self-hosting on a LAN**, not public internet exposure.

- **`AUTH_MODE=none` by default**: anyone who can reach port 6868 can control downloads.
  The server prints a warning at boot. Unless the network is fully trusted, set
  `AUTH_MODE=token` together with a long random `ABDM_AUTH_TOKEN`.
- **Do not expose it directly to the internet.** For remote access use either:
  - a private network such as Tailscale / WireGuard (recommended, no open ports), or
  - a reverse proxy (Caddy / Nginx) providing TLS plus HTTP Basic auth or
    `AUTH_MODE=token`, bound to an internal address only.
- **Path safety**: every writable path is normalized by `PathGuard` and must stay inside
  `downloadRoot`; `../` traversal and absolute escapes return `PATH_OUTSIDE_DOWNLOAD_ROOT`.
  `/config` is never reachable through the API.
- **Least privilege container**: the image runs as the non-root user `abdm` (uid 1000),
  exposes only 6868, and ships no Node/npm/Gradle/source at runtime.
- **Single writer**: SQLite runs in WAL mode; the `/config` directory must belong to one
  service instance at a time.
- **Tokens never reach logs**: `ABDM_AUTH_TOKEN` is injected through the environment; do not
  commit it into `docker-compose.yml`.

---

## Repository layout

```
server/app          Ktor bootstrap, dependency wiring, static assets
server/web-api      REST + WebSocket routes and DTO mapping (contract in docs/api.md)
server/scheduler    Queue, concurrency, state machine, restart recovery
server/engine-api   Engine ports: DownloadEngine / EngineEvent / PathGuard / storage
server/engine-native Built-in segmented engine (default, always built)
server/engine-abdm   AB Download Manager adapter (optional, abdm.enabled=true)
server/persistence  SQLite (WAL) storage
web/                Vue 3 + Vite frontend
third_party/ab-download-manager   Upstream submodule (read-only, never modified)
scripts/            update-abdm.sh / check-abdm.sh / build-release.sh
docs/               api.md / architecture.md / i18n.md / upstream.md
```

Layering, the threading/coroutine model and the restart-recovery flow are described in
[docs/architecture.md](docs/architecture.md).

---

## Upstream engine and submodule

- Pinned to `AB Download Manager v1.10.4`, commit
  `afc57634b3c121c6415213242b2b600cccc6fd6e`.
- The default build (`-Pabdm.enabled=false`) compiles the adapter as a stub and serves
  everything with the built-in engine — **no Android SDK required**.
- Only `-Pabdm.enabled=true` compiles the real adapter, and it compiles against the
  upstream runtime jars exported to `third_party/abdm-dist/` by
  `scripts/build-abdm-bridge.sh` (that export needs JDK 25 + an Android SDK).
- `third_party/ab-download-manager` is **never modified**; all adaptation lives in
  `server/engine-abdm/`.
- Upgrade procedure and the compatibility checklist: [docs/upstream.md](docs/upstream.md).

Scripts (Bash with `set -euo pipefail`; make them executable first, and run them through
Git Bash or WSL on Windows):

```bash
chmod +x scripts/*.sh          # Linux / macOS / Git Bash

scripts/check-abdm.sh          # verify the submodule is clean and at the pinned commit
scripts/update-abdm.sh v1.10.5 # move the pin, printing old -> new commit
scripts/build-abdm-bridge.sh   # export the upstream runtime jars into third_party/abdm-dist
scripts/build-release.sh       # build frontend + fat jar, write dist/SHA256SUMS
```

---

## Build and verification (also what CI runs)

Backend:

```bash
./gradlew build -Pabdm.enabled=false                                     # compile + unit tests
bash scripts/build-abdm-bridge.sh                                        # export upstream runtime (JDK 25 + Android SDK)
./gradlew -Pabdm.enabled=true :server:engine-abdm:test                   # AbdmCompatibilityTest
./gradlew --no-daemon -Pabdm.enabled=false :server:app:shadowJar         # build the fat jar
```

Frontend (inside `web/`):

```bash
npm ci
npm run i18n:check     # copy and error-code completeness gate
npm run lint
npm run typecheck
npm run test
npm run build          # writes web/dist
```

Container:

```bash
docker build -t abdm-server:local .
docker run --rm -p 6868:6868 -v "$PWD/config:/config" -v /mnt/downloads:/downloads abdm-server:local
curl -fsS http://127.0.0.1:6868/api/v1/health
```

CI workflows: `.github/workflows/ci.yml` (backend / frontend / docker / abdm-compat),
`docker.yml` (publishes `:edge` on every push to main and the release images on tags),
`release-manual.yml` (manual release with a version input, see "Releasing"),
`release.yml` (release when a tag is pushed), `upstream-check.yml` (weekly upstream check
that opens a PR) and `codeql.yml`.

### Local end-to-end acceptance

The repository ships a repeatable acceptance script that covers the core items of the V1
checklist: segmented download, **changing the connection count mid-download**
(8 → 64 → 256 → 16), pause → process restart → resume, and identical results with
1 / 64 / 256 connections — every step verified by SHA-256.

```bash
# 1) start a Range-capable local file server (--throttle slows it down for observation)
node scripts/test-http-server.mjs ./big.bin 9100 --throttle 262144

# 2) start the service (port 6868 by default)
./gradlew :server:app:fatJar
java -jar server/app/build/libs/abdm-server-*-all.jar

# 3) run the full acceptance suite (Windows PowerShell; it starts its own servers)
powershell -ExecutionPolicy Bypass -File scripts/acceptance-test.ps1 -FileSizeMb 256
```

On the unit-test side, `NativeDownloadEngineTest` in `server/engine-native` follows the same
approach in-process (with its own Range server); its
`a restart resumes from the sqlite checkpoint instead of starting over` test is the
regression test for "resume after restart", and `AbdmCompatibilityTest` runs the same
scenarios against the real ABDM engine.

---

## Releasing

Releases are cut by a **manual GitHub Actions workflow**
(`.github/workflows/release-manual.yml`): type a version, and it tags the chosen ref
(`main` by default), publishes the image to GHCR and attaches the binaries to a GitHub
Release.

1. Actions → **Release (manual)** → Run workflow
2. Fill in the inputs:

| Input | Required | Default | Meaning |
| --- | --- | --- | --- |
| `version` | yes | – | Semantic version such as `0.2.0` (a leading `v` is accepted) |
| `ref` | yes | `main` | Branch, tag or commit to build and tag |
| `prerelease` | no | `false` | Mark the GitHub Release as a pre-release |
| `push_latest` | no | `true` | Also move the `:latest` image tag |
| `dry_run` | no | `false` | Verify and build only: no image push, no tag, no release |

3. The job then: validates the version and refuses an existing tag → checks out `ref`
   (with submodules) → runs the backend `build` and the frontend gates
   (`i18n:check / lint / typecheck / test`) → builds the fat jar with the version baked in
   (`-Pproject.version=`, re-checked with `java -jar server.jar --print-version`) → writes
   `SHA256SUMS` → pushes the multi-arch image (`linux/amd64` + `linux/arm64`) → creates the
   tag → creates the GitHub Release with `server.jar`, `docker-compose.yml` and
   `SHA256SUMS` attached.

Produced artifacts:

```text
ghcr.io/dixtdf/abdm-server:0.2.0     # version
ghcr.io/dixtdf/abdm-server:0.2       # major.minor
ghcr.io/dixtdf/abdm-server:latest    # when push_latest=true
ghcr.io/dixtdf/abdm-server:edge      # every push to main (docker.yml)
tag: v0.2.0 (on the commit ref points at)
```

Two notes:

1. The tag is created **last**, so a failed verification or image build never leaves a tag
   behind.
2. A tag pushed with `GITHUB_TOKEN` does not trigger other workflows, which is why this
   workflow also creates the release itself. Tagging by hand stays equivalent:

   ```bash
   git tag v0.2.0 && git push origin v0.2.0   # triggers release.yml
   ```

To reproduce the version stamping locally:

```bash
./gradlew -Pabdm.enabled=false -Pproject.version=0.2.0 :server:app:fatJar
java -jar server/app/build/libs/abdm-server-*-all.jar --print-version   # -> 0.2.0
```

Bump the version (one command updates the Gradle catalog, the frontend
`package.json`/`package-lock.json`, the server and engine fallbacks and both
READMEs; everything else derives from those):

```powershell
# show what would change, write nothing
powershell -ExecutionPolicy Bypass -File scripts/set-version.ps1 0.2.0 -DryRun

# apply, optionally commit / tag / push
powershell -ExecutionPolicy Bypass -File scripts/set-version.ps1 v0.2.0 -Commit -Tag
```

---

## Acknowledgements

Special thanks to **AB Download Manager (ABDM) and all of its contributors**. This
project's download core, its part (segment) model and the interaction of the
"connections / parts" view all build on their work, and `engine-abdm` reuses their runtime
directly — without ABDM this project would not exist. Thanks as well to the Kotlin, Ktor,
SQLite JDBC, Vue and Vite communities.

---

## License

[Apache License 2.0](LICENSE). Third-party components and the upstream engine are
attributed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

**This project is not affiliated with or endorsed by AB Download Manager.**
