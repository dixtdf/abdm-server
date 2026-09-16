# Upstream: AB Download Manager

`abdm-server` does not vendor, fork or patch AB Download Manager. It records one
pinned upstream commit and adapts to it from a single module
(`server:engine-abdm`).

## Pin

Machine-readable — `scripts/check-abdm.sh` and `scripts/update-abdm.sh` read these
lines, so keep the `KEY=value` shape exactly:

```
UPSTREAM_VERSION=v1.10.4
UPSTREAM_COMMIT=afc57634b3c121c6415213242b2b600cccc6fd6e
UPSTREAM_PINNED_DATE=2026-09-15
```

Human-readable:

| | |
| --- | --- |
| Project | AB Download Manager |
| URL | https://github.com/amir1376/ab-download-manager |
| Release line | `v1.10.4` |
| Pinned commit | `afc57634b3c121c6415213242b2b600cccc6fd6e` |
| Pin recorded | 2026-09-15 (date this repository last moved the pin) |
| License | Apache-2.0 |
| Submodule path | `third_party/ab-download-manager` |

The submodule is registered by the repository owner (`.gitmodules`). This
document and the scripts only reference the path; they never create it.

Build-time switch:

```
abdm.enabled=true   # compile server:engine-abdm against the exported bridge
abdm.enabled=false  # compile the stub, run the built-in native engine (default)
```

### The bridge (`third_party/abdm-dist`)

The adapter never compiles against upstream sources directly. It compiles against
an exported slice of the upstream desktop runtime — `core-desktop.jar`,
`utils-desktop.jar`, `platform-desktop.jar` plus their transitive dependencies —
dropped into `third_party/abdm-dist/` by:

```bash
scripts/build-abdm-bridge.sh
```

That script runs upstream's own `:downloader:core:exportAbdmDist` task through an
init script (`scripts/abdm-bridge.init.gradle.kts`), so the submodule is never
modified. Requirements for that step only:

- **JDK 25** — upstream pins `jvm.toolchain=25`;
- **an Android SDK** — only to *configure* upstream's KMP/android target. No
  Android artifact is consumed by this project;
- network access on the first run.

With `abdm.enabled=true` and an empty `third_party/abdm-dist`, the build fails
fast with an explicit Gradle error instead of a compile error.

CI keeps `false` everywhere except the `abdm-compat` job, which runs on GitHub's
ubuntu image (Android SDK pre-installed), builds the bridge when the jars are not
already present, and then runs the compatibility test on pushes to `main` and on
manual dispatch. The `Test` tasks of `server:engine-abdm` are disabled unless
`abdm.enabled=true`, so a default build never tries to reach upstream.

## Policy: never edit `third_party/`

1. Everything under `third_party/` is upstream code, checked out at the pinned
   commit. Do not edit, patch, reformat, move or generate files inside it.
2. All adaptation lives in `server:engine-abdm`. If upstream needs a change,
   send it upstream.
3. `git submodule update --init --recursive` must leave that tree clean;
   `scripts/check-abdm.sh` fails if it is dirty.
4. No build script writes into `third_party/`; the Gradle cache and build
   directories stay inside `server/**/build/`.
5. Because we never modify it, the entire upstream license obligation is
   satisfied by attribution in `THIRD_PARTY_NOTICES.md`.

## Updating the pin

Prerequisites: the submodule is initialised (`git submodule update --init
--recursive`), a JDK 25 plus an Android SDK for the bridge build, and a JDK 17
for the server build.

```bash
# 1. move the pin
scripts/update-abdm.sh v1.10.5      # or an explicit commit
                                    # prints old -> new commit and rewrites the
                                    # pin block above

# 2. verify the submodule state
scripts/check-abdm.sh

# 3. re-export the bridge from the new commit (deletes nothing upstream)
rm -rf third_party/abdm-dist
scripts/build-abdm-bridge.sh

# 4. prove the adapter still compiles and behaves
./gradlew -Pabdm.enabled=true :server:engine-abdm:test

# 5. review and commit
#    third_party/abdm-dist is a build output: commit it only if this repository
#    tracks the exported jars, otherwise leave it to CI to rebuild.
git diff --submodule
git add third_party/ab-download-manager docs/upstream.md
git commit -m "chore: update AB Download Manager to v1.10.5"
```

Windows: run the scripts through Git Bash or WSL (`bash scripts/update-abdm.sh
v1.10.5`) — they are Bash and rely on standard POSIX tools.

Updating is a local, deliberate step: run `scripts/update-abdm.sh <tag>` to move the
pin, then step 3 (the compatibility test) before committing. There is no scheduled
workflow for it — `update-abdm.sh` prints the upstream tag comparison for you.

## What to check: `AbdmCompatibilityTest`

`AbdmCompatibilityTest` (`server/engine-abdm`) is the guard rail for every
upstream upgrade. It compiles the adapter against the exported bridge and
exercises the capability list the server depends on, so a renamed or removed API
fails immediately instead of at runtime. It is network dependent by design (it
downloads a small file from a public mirror), which is why it runs in the
`abdm-compat` job rather than on every pull request. Checklist:

| # | Capability | Why the server needs it |
| --- | --- | --- |
| 1 | The upstream engine can be constructed and started headlessly, with a configuration directory and a download directory supplied by us | the server owns `ABDM_CONFIG_DIR` / `ABDM_DOWNLOAD_ROOT`, not the desktop app |
| 2 | A download can be queued from a URL with a target folder and file name | `POST /api/v1/downloads` |
| 3 | A download can be paused, resumed and cancelled, and the state survives a restart | `pause` / `resume` / `DELETE` and restart recovery |
| 4 | The number of connections per task can be changed while it runs | `PATCH /downloads/{id}/connections` (`DYNAMIC_CONNECTIONS`) |
| 5 | Progress can be observed as a stream of state + byte counters (downloaded, total, speed, connections, ETA) | `download.progress` frames, `GET /downloads/{id}` |
| 6 | The upstream task id can be mapped back to our own task id bidirectionally | every route by id, plus event correlation |
| 7 | A global and a per-task speed limit can be applied | `settings.globalSpeedLimit`, `CreateDownload.speedLimit` |
| 8 | Custom headers, cookies, referer, user-agent and proxy are forwarded per download | `CreateDownload` |
| 9 | Range support and HLS are reported per task | `supportsRange` / `hls` on the task object, `UNSUPPORTED_OPERATION` otherwise |
| 10 | Checksum verification can be requested and its failure reported | `CreateDownload.checksum`, `CHECKSUM_MISMATCH` |
| 11 | A queued task can be removed with and without deleting its file | `DELETE /downloads/{id}?deleteFile=` |
| 12 | Completed downloads can be read back as history | `GET /api/v1/history` |
| 13 | Upstream failures surface as typed errors we can map to our error codes | the `errors.<CODE>` catalogue in `docs/api.md` |
| 14 | The upstream engine shuts down cleanly and flushes state | graceful container stop, restart recovery |

The test also pins the mapping in both directions
(`download.status.*` ↔ upstream state enum) so a new upstream state cannot be
silently swallowed.

If a capability disappears upstream, the options are, in order: stay on the
pinned commit; contribute the capability upstream; or degrade explicitly by
dropping the `EngineCapability` and returning `UNSUPPORTED_OPERATION`. The one
thing that is never acceptable is patching `third_party/`.

## Release notes / version reporting

`GET /api/v1/version` reports `abdmVersion` and `abdmCommit`, taken from this
pin, alongside `engine` and `engineVersion`. When you move the pin, the reported
values move with it — there is no second place to update.
