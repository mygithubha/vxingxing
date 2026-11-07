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

if (cd "$THRONED" && git apply --check "$PATCH"); then
  (cd "$THRONED" && git apply "$PATCH")
  echo "Applied Auto Connect dual-listener patch."
elif (cd "$THRONED" && git diff --quiet); then
  echo "ERROR: patch does not apply cleanly to pinned Throned revision." >&2
  exit 4
else
  echo "ERROR: Throned worktree is already modified; refusing to apply patch twice." >&2
  exit 5
fi

(cd "$THRONED" && git diff --check)

GEN="$THRONED/src/configs/generate.cpp"
SETTINGS="$THRONED/include/database/SettingsRepo.h"
UI="$THRONED/src/ui/setting/dialog_basic_settings.cpp"

grep -Fq '{"type", "socks"}' "$GEN" || { echo "Missing SOCKS5 inbound" >&2; exit 6; }
grep -Fq '{"type", "http"}' "$GEN" || { echo "Missing HTTP inbound" >&2; exit 7; }
grep -Fq '{"listen_port", 7890}' "$GEN" || { echo "HTTP port 7890 not configured" >&2; exit 8; }
grep -Fq 'int inbound_socks_port = 7891;' "$SETTINGS" || { echo "SOCKS5 default port 7891 not configured" >&2; exit 9; }
grep -Fq 'HTTP CONNECT uses 127.0.0.1:7890' "$UI" || { echo "Settings UI does not describe the HTTP endpoint" >&2; exit 10; }

echo "PASS: pinned source, separate HTTP/SOCKS5 listeners, ports, and settings label verified."
