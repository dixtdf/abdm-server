#!/usr/bin/env bash
# =============================================================================
# check-abdm.sh — fail if the AB Download Manager submodule is dirty or is not
# at the commit recorded in docs/upstream.md.
#
# Usage:
#   scripts/check-abdm.sh
#
# Exit codes:
#   0  submodule present, clean, at the pinned commit
#   1  submodule missing / dirty / at a different commit / unreadable pin
#
# Run this before tagging a release and after any `git submodule update`.
# Windows: run through Git Bash or WSL (`bash scripts/check-abdm.sh`).
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE="third_party/ab-download-manager"
SUBMODULE_PATH="${REPO_ROOT}/${SUBMODULE}"
UPSTREAM_DOC="${REPO_ROOT}/docs/upstream.md"
EXPECTED_URL="https://github.com/amir1376/ab-download-manager"

fail=0
ok()   { printf '  [ok]   %s\n' "$*"; }
bad()  { printf '  [fail] %s\n' "$*"; fail=1; }
warn() { printf '  [warn] %s\n' "$*"; }

command -v git >/dev/null 2>&1 || { printf 'error: git is not on PATH\n' >&2; exit 1; }

# --- read the recorded pin ---------------------------------------------------
[ -f "${UPSTREAM_DOC}" ] || { printf 'error: %s not found\n' "${UPSTREAM_DOC}" >&2; exit 1; }

PIN_VERSION="$(sed -n -E 's/^UPSTREAM_VERSION=(.*)$/\1/p' "${UPSTREAM_DOC}" | head -n1)"
PIN_COMMIT="$(sed -n -E 's/^UPSTREAM_COMMIT=(.*)$/\1/p' "${UPSTREAM_DOC}" | head -n1)"

if [ -z "${PIN_VERSION}" ] || [ -z "${PIN_COMMIT}" ]; then
  printf 'error: no UPSTREAM_VERSION/UPSTREAM_COMMIT pin block in %s\n' "${UPSTREAM_DOC}" >&2
  printf '       (expected lines: UPSTREAM_VERSION=vX.Y.Z and UPSTREAM_COMMIT=<40 hex>)\n' >&2
  exit 1
fi

printf 'AB Download Manager pin: %s @ %s\n' "${PIN_VERSION}" "${PIN_COMMIT}"

# --- submodule registered? ---------------------------------------------------
STATUS_LINE="$(git -C "${REPO_ROOT}" submodule status -- "${SUBMODULE}" 2>/dev/null || true)"
if [ -z "${STATUS_LINE}" ]; then
  bad "${SUBMODULE} is not a registered submodule (.gitmodules)"
  exit 1
fi

if [ ! -d "${SUBMODULE_PATH}" ] || [ ! -e "${SUBMODULE_PATH}/.git" ]; then
  bad "${SUBMODULE} is not checked out — run: git submodule update --init --recursive"
  exit 1
fi
ok "${SUBMODULE} is checked out"

# --- recorded vs checked out -------------------------------------------------
case "${STATUS_LINE}" in
  -*) bad "submodule is not initialised (git submodule update --init --recursive)" ;;
  +*) bad "submodule is checked out at a different commit than git records" ;;
  *)  ok "git-recorded submodule pointer is in sync" ;;
esac

REMOTE_URL="$(git -C "${SUBMODULE_PATH}" config --get remote.origin.url 2>/dev/null || echo '')"
case "${REMOTE_URL}" in
  "${EXPECTED_URL}"|"${EXPECTED_URL}.git"|"git@github.com:amir1376/ab-download-manager.git")
    ok "remote origin is ${EXPECTED_URL}" ;;
  "") warn "remote origin is not set inside the submodule" ;;
  *)  bad "remote origin is '${REMOTE_URL}', expected ${EXPECTED_URL}" ;;
esac

# --- dirty working tree ------------------------------------------------------
DIRTY="$(git -C "${SUBMODULE_PATH}" status --porcelain 2>/dev/null || true)"
if [ -n "${DIRTY}" ]; then
  bad "submodule working tree is dirty — third_party/ must never be modified:"
  printf '%s\n' "${DIRTY}" | sed 's/^/         /'
else
  ok "submodule working tree is clean"
fi

# --- commit match ------------------------------------------------------------
HEAD_COMMIT="$(git -C "${SUBMODULE_PATH}" rev-parse HEAD 2>/dev/null || echo '')"
if [ "${HEAD_COMMIT}" = "${PIN_COMMIT}" ]; then
  ok "HEAD matches the recorded commit"
else
  bad "HEAD is ${HEAD_COMMIT:-<unknown>}, docs/upstream.md pins ${PIN_COMMIT}"
  printf '         fix with: git -C %s checkout --detach %s\n' "${SUBMODULE}" "${PIN_COMMIT}"
fi

# --- describe (best effort) --------------------------------------------------
DESCRIBE="$(git -C "${SUBMODULE_PATH}" describe --tags --always "${HEAD_COMMIT}" 2>/dev/null || echo 'unknown')"
printf '\ndescribe: %s\n' "${DESCRIBE}"

if [ "${fail}" -ne 0 ]; then
  printf '\nSubmodule check FAILED.\n' >&2
  exit 1
fi

cat <<EOF

Submodule check PASSED — pinned AB Download Manager ${PIN_VERSION} (${PIN_COMMIT}).

To compile the adapter against it (needs an Android SDK):
  ./gradlew -Pabdm.enabled=true :server:engine-abdm:test
EOF
