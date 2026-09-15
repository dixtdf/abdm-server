#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Builds the AB Download Manager "bridge" (upstream desktop runtime) and exports
# it to third_party/abdm-dist, without ever modifying the submodule.
#
#   ./scripts/build-abdm-bridge.sh
#
# Requirements: a JDK 25 (upstream pins jvm.toolchain=25) and, on the first run,
# an internet connection. The Android SDK is only needed to *configure* the
# upstream build (the KMP module declares an android target); no Android
# artifacts are consumed by this project.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE_DIR="$ROOT_DIR/third_party/ab-download-manager"
DIST_DIR="${ABDM_DIST_DIR:-$ROOT_DIR/third_party/abdm-dist}"
INIT_SCRIPT="$ROOT_DIR/scripts/abdm-bridge.init.gradle.kts"

if [ ! -d "$SUBMODULE_DIR/downloader/core" ]; then
  echo "error: submodule missing. Run: git submodule update --init --recursive" >&2
  exit 1
fi

PINNED_COMMIT="$(git -C "$SUBMODULE_DIR" rev-parse HEAD)"
echo "[abdm-bridge] upstream commit $PINNED_COMMIT"
if [ -n "$(git -C "$SUBMODULE_DIR" status --porcelain)" ]; then
  echo "[abdm-bridge] error: submodule has local modifications, refusing to build" >&2
  exit 1
fi

export ABDM_DIST_DIR="$DIST_DIR"
mkdir -p "$DIST_DIR"

cd "$SUBMODULE_DIR"
if [ -x "./gradlew" ]; then
  ./gradlew --no-daemon --console=plain \
    :downloader:core:exportAbdmDist \
    --init-script "$INIT_SCRIPT"
else
  gradle --no-daemon --console=plain \
    :downloader:core:exportAbdmDist \
    --init-script "$INIT_SCRIPT"
fi

echo "[abdm-bridge] done. Enable the adapter with:"
echo "  ./gradlew :server:engine-abdm:test -Pabdm.enabled=true"
