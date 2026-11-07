#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
THRONED="$ROOT/windows/throned"
PATCH="$ROOT/windows/patches/throned-dual-inbound.patch"

if [[ ! -d "$THRONED/.git" && ! -f "$THRONED/.git" ]]; then
  echo "ERROR: windows/throned submodule is missing. Run: git submodule update --init --recursive" >&2
  exit 2
fi

EXPECTED="fbdc7a69cb2ccddc3915dc4de7ec50313a974f13"
ACTUAL="$(git -C "$THRONED" rev-parse HEAD)"
if [[ "$ACTUAL" != "$EXPECTED" ]]; then
  echo "ERROR: unexpected Throned revision: $ACTUAL (expected $EXPECTED)" >&2
  exit 3
fi

if git -C "$THRONED" apply --check "$PATCH"; then
  git -C "$THRONED" apply "$PATCH"
  echo "Applied Auto Connect dual-listener patch."
elif git -C "$THRONED" diff --quiet; then
  echo "ERROR: patch does not apply cleanly to pinned Throned revision." >&2
  exit 4
else
  echo "ERROR: Throned worktree is already modified; refusing to apply patch twice." >&2
  exit 5
fi

git -C "$THRONED" diff --check
