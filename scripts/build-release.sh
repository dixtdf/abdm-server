#!/usr/bin/env bash
# =============================================================================
# build-release.sh — build everything a release needs and stage it in dist/.
#
# Usage:
#   scripts/build-release.sh [version]
#
# Produces:
#   dist/server.jar            fat jar built with -Pabdm.enabled=false
#   dist/docker-compose.yml    the compose file shipped with the release
#   dist/SHA256SUMS            checksums of both files above
#
# The ABDM adapter is intentionally off: release artifacts are the built-in
# native engine only. Use `-Pabdm.enabled=true` locally if you need the bridge.
# Windows: run through Git Bash or WSL (`bash scripts/build-release.sh`).
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

DIST="${REPO_ROOT}/dist"


die() { printf 'error: %s\n' "$*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

# Version: explicit argument, else gradle/libs.versions.toml (`project = "x"`).
VERSION="${1:-}"
if [ -z "${VERSION}" ]; then
  VERSION="$(sed -n -E 's/^project[[:space:]]*=[[:space:]]*"(.*)"/\1/p' gradle/libs.versions.toml | head -n1)"
fi
VERSION="${VERSION#v}"
[ -n "${VERSION}" ] || die "could not determine the version (pass it explicitly)"
printf 'building abdm-server %s\n' "${VERSION}"

# Derived from the version, so bumping the version in one place is enough.
APP_JAR_NAME="abdm-server-${VERSION}-all.jar"
APP_JAR_PATH="server/app/build/libs/${APP_JAR_NAME}"

# --- 1. frontend -------------------------------------------------------------
step "frontend: npm ci"
( cd web && npm ci )

step "frontend: npm run i18n:check"
( cd web && npm run i18n:check )

step "frontend: npm run build"
( cd web && npm run build )
[ -d web/dist ] || die "web/dist was not produced"

# --- 2. backend --------------------------------------------------------------
step "backend: :server:app:shadowJar"
./gradlew --no-daemon -Pabdm.enabled=false -Pproject.version="${VERSION}" :server:app:shadowJar

[ -f "${APP_JAR_PATH}" ] || die "fat jar not found at ${APP_JAR_PATH}"

# --- 3. stage dist/ ----------------------------------------------------------
step "staging ${DIST#"${REPO_ROOT}/"}/"
rm -rf "${DIST}"
mkdir -p "${DIST}"
cp "${APP_JAR_PATH}" "${DIST}/server.jar"
cp docker-compose.yml "${DIST}/docker-compose.yml"

# --- 4. checksums ------------------------------------------------------------
step "checksums"
(
  cd "${DIST}"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum server.jar docker-compose.yml > SHA256SUMS
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 server.jar docker-compose.yml > SHA256SUMS
  else
    die "neither sha256sum nor shasum is available"
  fi
)

cat <<EOF

Artifacts in dist/:
$(ls -1 "${DIST}" | sed 's/^/  /')

SHA256SUMS:
$(sed 's/^/  /' "${DIST}/SHA256SUMS")

Next: publish it. Either run the manual release workflow
(GitHub -> Actions -> Release (manual), version ${VERSION}), which tags the commit,
builds the multi-arch image and creates the GitHub Release - or push the tag
yourself and create the release by hand (pushing a tag alone publishes nothing):
  git tag v${VERSION} && git push origin v${VERSION}
EOF
