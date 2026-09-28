#!/usr/bin/env bash
# =============================================================================
# update-abdm.sh — bump the AB Download Manager submodule to a release tag.
#
# Usage:
#   scripts/update-abdm.sh v1.10.4
#
# What it does:
#   1. fetches tags inside third_party/ab-download-manager
#   2. checks the requested release tag out in detached HEAD
#   3. prints the old -> new submodule commit
#   4. updates the pin and adapter version metadata
#
# It never stages, commits or pushes anything: review the diff yourself.
# Windows: run through Git Bash or WSL (`bash scripts/update-abdm.sh ...`).
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE="third_party/ab-download-manager"
SUBMODULE_PATH="${REPO_ROOT}/${SUBMODULE}"
UPSTREAM_DOC="${REPO_ROOT}/docs/upstream.md"
ENGINE_INFO="${REPO_ROOT}/server/engine-abdm/src/main/kotlin/dev/abdm/server/engine/abdm/AbdmEngineInfo.kt"
COMPAT_TEST="${REPO_ROOT}/server/engine-abdm/src/test/kotlin/dev/abdm/server/engine/abdm/AbdmCompatibilityTest.kt"
UPSTREAM_URL="https://github.com/amir1376/ab-download-manager"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '  %s\n' "$*"; }

TARGET="${1:-}"
if [ -z "${TARGET}" ] || [ "${TARGET}" = "-h" ] || [ "${TARGET}" = "--help" ]; then
  cat <<'USAGE'
usage: scripts/update-abdm.sh <release-tag>

  v1.10.4                                       a released tag

After running this:
  scripts/check-abdm.sh                      verify the pin
  ./gradlew :server:engine-abdm:test
                                             run AbdmCompatibilityTest
  git add third_party/ab-download-manager docs/upstream.md server/engine-abdm
USAGE
  [ -n "${TARGET}" ] || exit 2
  exit 0
fi

[[ "${TARGET}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  die "expected a release tag such as v1.10.4; branches and raw commits are not allowed"

command -v git >/dev/null 2>&1 || die "git is not on PATH"
[ -d "${SUBMODULE_PATH}" ] || die "${SUBMODULE} is missing — the repo owner must register the submodule first"

# `git submodule status` gives us the recorded commit and a leading marker:
#   '-'  not initialised, '+' checked out at a different commit.
STATUS_LINE="$(git -C "${REPO_ROOT}" submodule status -- "${SUBMODULE}" 2>/dev/null || true)"
[ -n "${STATUS_LINE}" ] || die "${SUBMODULE} is not a registered submodule"

OLD_COMMIT="$(git -C "${SUBMODULE_PATH}" rev-parse HEAD 2>/dev/null || echo "<not checked out>")"
OLD_PIN_VERSION="$(sed -n 's/^UPSTREAM_VERSION=//p' "${UPSTREAM_DOC}" | head -n 1)"
OLD_PIN_COMMIT="$(sed -n 's/^UPSTREAM_COMMIT=//p' "${UPSTREAM_DOC}" | head -n 1)"
[ -n "${OLD_PIN_VERSION}" ] && [ -n "${OLD_PIN_COMMIT}" ] || die "pin is missing in docs/upstream.md"
[ -z "$(git -C "${SUBMODULE_PATH}" status --porcelain)" ] || die "submodule has local changes"

info "fetching release tag ${TARGET} in ${SUBMODULE}"
git -C "${SUBMODULE_PATH}" fetch --no-tags origin tag "${TARGET}"

TAG_COMMIT="$(git -C "${SUBMODULE_PATH}" rev-parse --verify "refs/tags/${TARGET}^{commit}")" ||
  die "release tag ${TARGET} was not found upstream"
info "checking out ${TARGET} (${TAG_COMMIT})"
git -C "${SUBMODULE_PATH}" checkout --detach "${TAG_COMMIT}"

NEW_COMMIT="$(git -C "${SUBMODULE_PATH}" rev-parse HEAD)"

NEW_VERSION="${TARGET}"
PLAIN_VERSION="${TARGET#v}"
SHORT_COMMIT="${NEW_COMMIT:0:7}"

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

# Keep the adapter's reported version and the documented pin in sync. A tag
# upgrade is one command, followed by the compatibility test and a diff review.
for file in \
  "${UPSTREAM_DOC}" \
  "${REPO_ROOT}/docs/architecture.md" \
  "${REPO_ROOT}/README.md" \
  "${REPO_ROOT}/README_en.md" \
  "${REPO_ROOT}/THIRD_PARTY_NOTICES.md" \
  "${REPO_ROOT}/Dockerfile" \
  "${REPO_ROOT}/scripts/build-release.sh" \
  "${REPO_ROOT}/.github/workflows/release-manual.yml" \
  "${ENGINE_INFO}" \
  "${COMPAT_TEST}"; do
  sed -i.bak \
    -e "s|${OLD_PIN_VERSION#v}|${PLAIN_VERSION}|g" \
    -e "s|${OLD_PIN_COMMIT}|${NEW_COMMIT}|g" \
    -e "s|${OLD_PIN_COMMIT:0:7}|${SHORT_COMMIT}|g" \
    "${file}"
  rm -f "${file}.bak"
done

cat <<EOF

AB Download Manager submodule
  repo    ${UPSTREAM_URL}
  version ${NEW_VERSION}
  old     ${OLD_COMMIT}
  new     ${NEW_COMMIT}
  date    ${COMMIT_DATE}

Next steps
  1. scripts/check-abdm.sh
  2. ./gradlew :server:engine-abdm:test   # AbdmCompatibilityTest
     This test asserts every capability the adapter relies on (see
     docs/upstream.md for the checklist). A newer upstream tag that drops or
     renames one of those APIs must fail here before it can be pinned.
  3. review the changed pin, adapter metadata and documentation
     git add ${SUBMODULE} docs README.md README_en.md THIRD_PARTY_NOTICES.md Dockerfile scripts .github server/engine-abdm

Never edit tracked files inside ${SUBMODULE}/ — it is an upstream submodule.
EOF
