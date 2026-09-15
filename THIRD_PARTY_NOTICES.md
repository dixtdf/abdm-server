# Third-party notices

`abdm-server` is licensed under Apache-2.0 (see `LICENSE`). It builds on the
following third-party components. Full license texts ship with each component's
own source distribution and are not duplicated here.

## Upstream engine

**AB Download Manager**
- URL: https://github.com/amir1376/ab-download-manager
- License: Apache License 2.0
- Copyright: the AB Download Manager authors
- Special thanks: AB Download Manager (ABDM) and **all of its contributors**. The
download core, the part/segment model and the connections table of this project
follow their work, and `server/engine-abdm/` reuses their runtime directly.
- How it is used: consumed as a git submodule at
  `third_party/ab-download-manager`, pinned to commit
  `afc57634b3c121c6415213242b2b600cccc6fd6e` (release line `v1.10.4`). The
  adapter (`server/engine-abdm/`) compiles against an exported slice of the
  upstream desktop runtime (`core-desktop.jar`, `utils-desktop.jar`,
  `platform-desktop.jar` and their transitive dependencies) produced by
  `scripts/build-abdm-bridge.sh` into `third_party/abdm-dist/`. That export
  happens only when the build is run with `-Pabdm.enabled=true`; the default
  build and the published container image use the built-in native engine and
  contain no upstream artifact.
- Changes: none. The submodule is read-only in this repository and is never
  modified, patched or vendored. All adaptation happens in
  `server/engine-abdm/`. See `docs/upstream.md`.

## Runtime and build dependencies

| Component | URL | License | Role |
| --- | --- | --- | --- |
| Kotlin / kotlinx.coroutines / kotlinx.serialization / kotlinx-datetime | https://github.com/JetBrains/kotlin | Apache-2.0 | language and runtime, structured concurrency, JSON |
| Ktor (server, client, CIO, WebSockets, content negotiation) | https://github.com/ktorio/ktor | Apache-2.0 | HTTP server, HTTP client, WebSocket, JSON wiring |
| SLF4J | https://github.com/qos-ch/slf4j | MIT | logging facade |
| SQLite JDBC (Xerial) | https://github.com/xerial/sqlite-jdbc | Apache-2.0 | JDBC driver for the SQLite store |
| SQLite (the embedded database engine) | https://sqlite.org/ | Public Domain | bundled native library behind sqlite-jdbc |
| Gradle Shadow plugin | https://github.com/johnrengelman/shadow | Apache-2.0 | fat jar packaging (build only) |
| Vue 3 | https://github.com/vuejs/core | MIT | frontend framework |
| Vite | https://github.com/vitejs/vite | MIT | frontend build tool |
| vue-i18n | https://github.com/intlify/vue-i18n | MIT | UI localisation |
| Pinia | https://github.com/vuejs/pinia | MIT | frontend state |
| Vue Router | https://github.com/vuejs/router | MIT | frontend routing |
| Node.js (build/runtime stage only) | https://nodejs.org/ | MIT | frontend build toolchain |

The container image is built `FROM node:22-alpine` (web stage),
`gradle:8.13-jdk17` (builder stage) and `eclipse-temurin:17-jre-jammy`
(runtime stage). Those base images carry their own licenses and notices; the
runtime image contains only `eclipse-temurin`, `curl`/`ca-certificates` from the
Ubuntu archive, the fat jar and the compiled frontend.

## Attribution notes

- No AB Download Manager source file is copied into this repository, so no
  upstream copyright header is reproduced outside the submodule itself.
- If the exported runtime jars under `third_party/abdm-dist/` are tracked, they
  are upstream binary artifacts redistributed unmodified under Apache-2.0; the
  attribution above plus this notice satisfy the redistribution condition, and
  the upstream `LICENSE` ships inside the submodule.
- The project name "AB Download Manager" is the property of its authors and is
  used here only to identify the upstream project this server can optionally
  drive.

_This project is not affiliated with or endorsed by AB Download Manager._
