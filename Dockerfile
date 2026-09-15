# syntax=docker/dockerfile:1.7
# =============================================================================
# abdm-server — reproducible multi-stage image
#
#   Stage 1  web      node:22-alpine            -> /web/dist (Vue 3 + Vite)
#   Stage 2  builder  gradle:8.13-jdk17         -> abdm-server-0.1.0-all.jar
#   Stage 3  runtime  eclipse-temurin:17-jre-jammy -> the published image
#
# Build:
#   docker build -t abdm-server:0.1.0 .
#
# The AB Download Manager adapter stays OFF in the image
# (-Pabdm.enabled=false): the server uses its built-in native engine, no
# Android SDK is needed, and no Node/Gradle/source ever reaches the runtime
# stage. Building with the bridge enabled would additionally need
# third_party/abdm-dist (exported by scripts/build-abdm-bridge.sh) in the
# context, and both third_party/ paths are excluded below.
#
# Runtime contract: HTTP 8080, health probe GET /api/v1/health,
# SQLite (WAL) at /config/database.sqlite, writable /config and /downloads.
# =============================================================================


# ---------------------------------------------------------------------------
# Stage 1 — web: compile the frontend. Only web/dist leaves this stage.
# ---------------------------------------------------------------------------
FROM node:22-alpine AS web

WORKDIR /web

# Manifests first, so `npm ci` is cached independently of the sources.
COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ ./

# The i18n gate is `node ../scripts/check-i18n.mjs`, and that script reads the
# error-code catalogue from `docs/api.md` - both live outside web/, so copy exactly
# those two paths into the stage (.dockerignore re-includes docs/api.md for this).
COPY scripts/check-i18n.mjs /scripts/check-i18n.mjs
COPY docs/api.md /docs/api.md

# i18n:check is the gate that every error code exists in every locale file.
RUN npm run i18n:check \
 && npm run build


# ---------------------------------------------------------------------------
# Stage 2 — builder: fat jar through the Gradle wrapper, no daemon.
# ---------------------------------------------------------------------------
FROM gradle:8.13-jdk17 AS builder

# The official gradle image drops to an unprivileged `gradle` user, which cannot
# write the root-owned /build layer or its Gradle cache. This stage is throwaway
# and nothing from it ships, so stay root here.
USER root

WORKDIR /build

# -- layer 1: Gradle metadata only -----------------------------------------
# Wrapper + settings/root build script + one build script per module. These
# change far less often than the sources, so the dependency warm-up below is
# reused across normal source edits.
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY server/app/build.gradle.kts server/app/
COPY server/engine-api/build.gradle.kts server/engine-api/
COPY server/engine-native/build.gradle.kts server/engine-native/
COPY server/engine-abdm/build.gradle.kts server/engine-abdm/
COPY server/persistence/build.gradle.kts server/persistence/
COPY server/scheduler/build.gradle.kts server/scheduler/
COPY server/web-api/build.gradle.kts server/web-api/

RUN chmod +x gradlew

# Warms the dependency cache into the layer above, so source-only edits reuse it.
RUN ./gradlew --no-daemon -Pabdm.enabled=false :server:app:dependencies

# -- layer 2: sources -------------------------------------------------------
COPY server/ server/

RUN ./gradlew --no-daemon --stacktrace -Pabdm.enabled=false :server:app:shadowJar


# ---------------------------------------------------------------------------
# Stage 3 — runtime: JRE only, non-root, health-checked.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl is only for HEALTHCHECK; ca-certificates for outbound TLS downloads.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Non-root runtime identity (uid 1000, matching the compose file's host user).
RUN groupadd --gid 1000 abdm \
 && useradd --uid 1000 --gid 1000 --create-home --shell /usr/sbin/nologin abdm

# Writable state: SQLite + settings under /config, payload under /downloads.
RUN mkdir -p /config /downloads /app \
 && chown -R abdm:abdm /config /downloads /app

# The fat jar and nothing else from the builder stage.
COPY --from=builder /build/server/app/build/libs/abdm-server-0.1.0-all.jar /app/abdm-server.jar

# The built frontend, served from ABDM_WEB_DIR.
COPY --from=web /web/dist /app/web

ENV ABDM_WEB_DIR=/app/web \
    ABDM_CONFIG_DIR=/config \
    ABDM_DOWNLOAD_ROOT=/downloads \
    PORT=8080 \
    TZ=Asia/Shanghai

VOLUME ["/config", "/downloads"]

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1

USER abdm

WORKDIR /app

ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/abdm-server.jar"]
