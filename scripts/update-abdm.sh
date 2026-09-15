#!/usr/bin/env bash
# =============================================================================
# update-abdm.sh — bump the AB Download Manager submodule to a tag or commit.
#
# Usage:
#   scripts/update-abdm.sh v1.10.4
#   scripts/update-abdm.sh afc57634b3c121c6415213242b2b600cccc6fd6e
#
# What it does:
#   1. fetches tags inside third_party/ab-download-manager
#   2. checks the requested tag/commit out in detached HEAD
#   3. prints the old -> new submodule commit
#   4. rewrites the machine-readable pin block in docs/upstream.md
#
# It never stages, commits or pushes anything: review the diff yourself.
# Windows: run through Git Bash or WSL (`bash scripts/update-abdm.sh ...`).
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE="third_party/ab-download-manager"
SUBMODULE_PATH="${REPO_ROOT}/${SUBMODULE}"
UPSTREAM_DOC="${REPO_ROOT}/docs/upstream.md"
UPSTREAM_URL="https://github.com/amir1376/ab-download-manager"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '  %s\n' "$*"; }

TARGET="${1:-}"
if [ -z "${TARGET}" ] || [ "${TARGET}" = "-h" ] || [ "${TARGET}" = "--help" ]; then
  cat <<'USAGE'
usage: scripts/update-abdm.sh <tag|commit>

  v1.10.4                                       a released tag
  afc57634b3c121c6415213242b2b600cccc6fd6e      an explicit commit

After running this:
  scripts/check-abdm.sh                      verify the pin
  ./gradlew -Pabdm.enabled=true :server:engine-abdm:test
                                             run AbdmCompatibilityTest
  git add third_party/ab-download-manager docs/upstream.md
USAGE
  [ -n "${TARGET}" ] || exit 2
  exit 0
fi

command -v git >/dev/null 2>&1 || die "git is not on PATH"
[ -d "${SUBMODULE_PATH}" ] || die "${SUBMODULE} is missing — the repo owner must register the submodule first"

# `git submodule status` gives us the recorded commit and a leading marker:
#   '-'  not initialised, '+' checked out at a different commit.
STATUS_LINE="$(git -C "${REPO_ROOT}" submodule status -- "${SUBMODULE}" 2>/dev/null || true)"
[ -n "${STATUS_LINE}" ] || die "${SUBMODULE} is not a registered submodule"

OLD_COMMIT="$(git -C "${SUBMODULE_PATH}" rev-parse HEAD 2>/dev/null || echo "<not checked out>")"

info "fetching tags in ${SUBMODULE}"
git -C "${SUBMODULE_PATH}" fetch --tags --prune origin

info "checking out ${TARGET}"
git -C "${SUBMODULE_PATH}" checkout --detach "${TARGET}"

NEW_COMMIT="$(git -C "${SUBMODULE_PATH}" rev-parse HEAD)"

if git -C "${SUBMODULE_PATH}" rev-parse -q --verify "refs/tags/${TARGET}" >/dev/null 2>&1; then
  NEW_VERSION="${TARGET}"
else
  NEW_VERSION="$(git -C "${SUBMODULE_PATH}" describe --tags --abbrev=0 "${NEW_COMMIT}" 2>/dev/null || echo "unknown")"
fi

COMMIT_DATE="$(git -C "${SUBMODULE_PATH}" show -s --format=%cs "${NEW_COMMIT}")"

# --- update the machine-readable pin in docs/upstream.md ---------------------
if [ -f "${UPSTREAM_DOC}" ]; then
  sed -i.bak -E "s|^UPSTREAM_VERSION=.*|UPSTREAM_VERSION=${NEW_VERSION}|" "${UPSTREAM_DOC}"
  sed -i.bak -E "s|^UPSTREAM_COMMIT=.*|UPSTREAM_COMMIT=${NEW_COMMIT}|" "${UPSTREAM_DOC}"
  rm -f "${UPSTREAM_DOC}.bak"
  # Keep the human-readable pin date honest.
  sed -i.bak -E "s|^UPSTREAM_PINNED_DATE=.*|UPSTREAM_PINNED_DATE=${COMMIT_DATE}|" "${UPSTREAM_DOC}"
  rm -f "${UPSTREAM_DOC}.bak"
  info "updated ${UPSTREAM_DOC#"${REPO_ROOT}/"} -> ${NEW_VERSION} (${COMMIT_DATE})"
else
  printf 'warning: %s not found, pin not updated\n' "${UPSTREAM_DOC}" >&2
fi

cat <<EOF

AB Download Manager submodule
  repo    ${UPSTREAM_URL}
  version ${NEW_VERSION}
  old     ${OLD_COMMIT}
  new     ${NEW_COMMIT}
  date    ${COMMIT_DATE}

Next steps
  1. scripts/check-abdm.sh
  2. ./gradlew -Pabdm.enabled=true :server:engine-abdm:test   # AbdmCompatibilityTest
     This test asserts every capability the adapter relies on (see
     docs/upstream.md for the checklist). A newer upstream tag that drops or
     renames one of those APIs must fail here before it can be pinned.
  3. git add ${SUBMODULE} docs/upstream.md
     git commit -m "chore: update AB Download Manager to ${NEW_VERSION}"

Never edit files inside ${SUBMODULE}/ — it is a vendored upstream checkout.
EOF
