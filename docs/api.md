# REST / WebSocket API contract (v1)

Base path: `/api/v1`

This document is the **single source of truth** for the wire format. The Kotlin
`web-api` module and the Vue frontend both follow it.

Authentication depends on `AUTH_MODE`:

| `AUTH_MODE` | Behaviour |
| --- | --- |
| `none` | Everything is reachable. The server logs a loud warning at boot. |
| `token` | Every route except `/api/v1/health` requires `Authorization: Bearer <token>`. The WebSocket additionally accepts `?token=<token>`. |

All error responses use the same envelope and a stable machine readable code.
The backend never returns a localized sentence.

```json
{
  "error": {
    "code": "DOWNLOAD_DIRECTORY_NOT_FOUND",
    "params": { "path": "/downloads/linux" },
    "detail": "optional developer detail, never shown as the primary message"
  }
}
```

| HTTP | Codes |
| --- | --- |
| 400 | `INVALID_URL`, `UNSUPPORTED_SCHEME`, `INVALID_CONNECTION_COUNT` |
| 401 | `UNAUTHORIZED` |
| 404 | `TASK_NOT_FOUND`, `DOWNLOAD_DIRECTORY_NOT_FOUND`, `PATH_OUTSIDE_DOWNLOAD_ROOT` |
| 409 | `TASK_ALREADY_EXISTS` |
| 500 | `INTERNAL` |
| 501 | `UNSUPPORTED_OPERATION`, `ENGINE_UNAVAILABLE` |

The frontend renders `errors.<CODE>` from the locale files and interpolates
`params`. Missing translations fall back to `en-US`.

## Task object

```json
{
  "id": "3f9c1a77b2e84510",
  "url": "https://releases.ubuntu.com/ubuntu-26.04.iso",
  "fileName": "ubuntu-26.04.iso",
  "folder": "/downloads",
  "path": "/downloads/ubuntu-26.04.iso",
  "state": "DOWNLOADING",
  "downloaded": 4294967296,
  "total": 8589934592,
  "progress": 0.5,
  "speed": 91234567,
  "averageSpeed": 81234567,
  "connections": 64,
  "activeConnections": 61,
  "etaSeconds": 48,
  "supportsRange": true,
  "hls": false,
  "speedLimit": 0,
  "checksum": null,
  "error": null,
  "createdAt": 1789463933506,
  "startedAt": 1789463934000,
  "completedAt": null,
  "queuePosition": null
}
```

`state` is one of:

```
QUEUED CONNECTING DOWNLOADING PAUSING PAUSED COMPLETING COMPLETED FAILED CANCELED RECOVERING
```

`total == -1` means unknown size, `etaSeconds == -1` means unknown ETA.

## Endpoints

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| GET | `/api/v1/health` | – | `{ "status": "ok", "uptimeSeconds": 42, "version": "0.1.0", "engine": "native" }` |
| GET | `/api/v1/version` | – | `{ "version", "apiVersion", "engine", "engineVersion", "abdmVersion", "abdmCommit", "java", "os", "startedAt" }` |
| GET | `/api/v1/settings` | – | `Settings` |
| PUT | `/api/v1/settings` | partial `Settings` | `Settings` |
| GET | `/api/v1/directories?path=/downloads` | – | `{ "path", "parent", "directories": [{ "name", "path" }] }` |
| GET | `/api/v1/downloads` | – | `Task[]` |
| POST | `/api/v1/downloads` | `CreateDownload` | `201 Task` |
| GET | `/api/v1/downloads/{id}` | – | `Task` |
| DELETE | `/api/v1/downloads/{id}?deleteFile=false` | – | `204` |
| POST | `/api/v1/downloads/{id}/start` | – | `Task` |
| POST | `/api/v1/downloads/{id}/pause` | – | `Task` |
| POST | `/api/v1/downloads/{id}/resume` | – | `Task` |
| PATCH | `/api/v1/downloads/{id}/connections` | `{ "connections": 64 }` | `Task` |
| GET | `/api/v1/history?limit=100` | – | `HistoryEntry[]` |
| DELETE | `/api/v1/history` | – | `204` |
| WS | `/api/v1/events` | – | event stream, see below |

### `CreateDownload`

```json
{
  "url": "https://example.com/file.iso",
  "fileName": "file.iso",
  "folder": "/downloads",
  "connections": 32,
  "headers": { "Authorization": "Bearer ..." },
  "cookies": "a=b",
  "referer": "https://example.com/",
  "userAgent": "custom/1.0",
  "proxy": "http://127.0.0.1:7890",
  "speedLimit": 0,
  "checksum": "sha256:<hex>",
  "hls": false,
  "overwrite": false,
  "startImmediately": true
}
```

Only `url` is mandatory. `fileName`, `folder` and `connections` come from
`Settings` when omitted.

### `Settings`

```json
{
  "downloadRoot": "/downloads",
  "defaultFolder": "/downloads",
  "defaultConnections": 8,
  "maxConcurrentDownloads": 3,
  "globalSpeedLimit": 0,
  "resumeOnStartup": true,
  "progressIntervalMs": 500,
  "authMode": "none",
  "maxConnections": 256
}
```

Language and theme are **web UI preferences** (`localStorage`), not server
settings: `ui.locale` and `ui.theme`.

### `HistoryEntry`

```json
{ "id": "...", "url": "...", "fileName": "...", "folder": "...", "size": 123, "checksum": null, "outcome": "COMPLETED", "finishedAt": 1789463933506 }
```

## WebSocket `/api/v1/events`

First frame after connect:

```json
{ "type": "hello", "server": { "version": "0.1.0", "engine": "native", "apiVersion": "v1" }, "tasks": [ /* Task[] */ ] }
```

Every subsequent frame:

```json
{ "type": "download.progress", "taskId": "3f9c...", "progress": { "downloaded": 1, "total": 2, "speed": 3, "averageSpeed": 3, "connections": 8, "activeConnections": 7, "etaSeconds": 12, "state": "DOWNLOADING" } }
{ "type": "download.state", "task": { /* Task */ }, "previous": "DOWNLOADING", "state": "COMPLETED", "error": null }
{ "type": "download.added", "task": { /* Task */ } }
{ "type": "download.connections", "task": { /* Task */ }, "requested": 64, "active": 61 }
{ "type": "download.removed", "taskId": "3f9c...", "deletedFile": false }
{ "type": "pong" }
```

`download.progress` is throttled by `settings.progressIntervalMs` (default
500 ms). The frontend must not poll the REST API for progress.

## Error code catalogue

```
INVALID_URL                 UNSUPPORTED_SCHEME        DOWNLOAD_DIRECTORY_NOT_FOUND
PATH_OUTSIDE_DOWNLOAD_ROOT  TASK_NOT_FOUND            TASK_ALREADY_EXISTS
INVALID_CONNECTION_COUNT    NETWORK                   SERVER_NO_RANGE_SUPPORT
DISK_FULL                   CHECKSUM_MISMATCH         ENGINE_UNAVAILABLE
UNSUPPORTED_OPERATION       INTERNAL                  UNAUTHORIZED
```

Each code must exist as `errors.<CODE>` in **every** locale file
(`web/src/locales/en-US.json`, `web/src/locales/zh-CN.json`). CI enforces this
(see `scripts/check-i18n.mjs`).
