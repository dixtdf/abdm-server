# Architecture

`abdm-server` is a headless server and Vue web UI around the pinned AB Download
Manager runtime. ABDM is the only download engine. The server does not implement
HTTP transfers, segment allocation, HLS downloading, or resume logic itself.

```text
Vue UI ── REST/WebSocket ── server:web-api ── server:scheduler
                              │                    │
                         server:persistence        │
                              │                    ▼
                         SQLite state      server:engine-api
                                                   │
                                                   ▼
                                         server:engine-abdm
                                                   │
                                                   ▼
                                  pinned upstream ABDM runtime
```

## Module boundaries

| Module | Responsibility |
| --- | --- |
| `server:app` | Ktor startup, configuration, wiring, static UI, shutdown |
| `server:web-api` | REST/WebSocket routes, DTOs, authentication, event delivery |
| `server:scheduler` | Queue and concurrency policy; submits work to ABDM |
| `server:engine-api` | Narrow interface and DTO types between server and adapter |
| `server:engine-abdm` | Maps API operations, task snapshots and events to upstream ABDM classes |
| `server:persistence` | SQLite settings, queue and API task/history records |
| `web` | Vue UI, i18n and WebSocket consumption |

The adapter is the only module that imports upstream downloader classes. The
submodule at `third_party/ab-download-manager` is pinned to release `v1.10.4`.
`scripts/build-abdm-bridge.sh` exports its desktop runtime as Java 17 JARs to
ignored `third_party/abdm-dist/`. Gradle checks the exported commit marker
against `docs/upstream.md` and fails if the bridge is missing or stale. The
server build and Docker image always include this adapter; there is no fallback
engine or engine selection setting.

## Runtime state and restart

ABDM owns the download list, part metadata and transfer state in
`/config/abdm`. The server keeps settings, queue ordering and API records in
`/config/database.sqlite` (WAL). Download payloads live under `/downloads`.
At startup the adapter boots upstream ABDM, synchronizes its tasks with the API
repository, and returns unfinished IDs to the scheduler. The scheduler resumes
them when `resumeOnStartup` is enabled.

Older releases created 16-character hexadecimal task IDs with a separate
native downloader. Their SQLite rows and part state cannot be passed to ABDM.
Startup detects these rows and stops with a migration message so the records do
not silently disappear. Back up `/config` and `/downloads` before replacing an
older installation; migrate or remove those rows before starting this version.

The REST and WebSocket contract is in [api.md](api.md). The upstream pin,
upgrade command and compatibility checks are in [upstream.md](upstream.md).
