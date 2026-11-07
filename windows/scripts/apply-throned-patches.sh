#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
THRONED="$ROOT/windows/throned"
PATCHES=(
  "$ROOT/windows/patches/throned-dual-inbound.patch"
  "$ROOT/windows/patches/throned-boot-failover.patch"
)

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

for PATCH in "${PATCHES[@]}"; do
  if (cd "$THRONED" && git apply --check "$PATCH"); then
    (cd "$THRONED" && git apply "$PATCH")
    echo "Applied $(basename "$PATCH")."
  elif (cd "$THRONED" && git apply --reverse --check "$PATCH"); then
    echo "Already applied: $(basename "$PATCH")."
  else
    echo "ERROR: patch cannot be applied cleanly: $PATCH" >&2
    exit 4
  fi
done

(cd "$THRONED" && git diff --check)

GEN="$THRONED/src/configs/generate.cpp"
SETTINGS="$THRONED/include/database/SettingsRepo.h"
UI="$THRONED/src/ui/setting/dialog_basic_settings.cpp"
CONNECTION="$THRONED/src/engine/connection/ConnectionManager.cpp"
TYPES="$THRONED/include/engine/EngineTypes.h"

grep -Fq '{"type", "socks"}' "$GEN" || { echo "Missing SOCKS5 inbound" >&2; exit 6; }
grep -Fq '{"type", "http"}' "$GEN" || { echo "Missing HTTP inbound" >&2; exit 7; }
grep -Fq '{"listen_port", 7890}' "$GEN" || { echo "HTTP port 7890 not configured" >&2; exit 8; }
grep -Fq 'int inbound_socks_port = 7891;' "$SETTINGS" || { echo "SOCKS5 default port 7891 not configured" >&2; exit 9; }
grep -Fq 'HTTP CONNECT uses 127.0.0.1:7890' "$UI" || { echo "Settings UI does not describe the HTTP endpoint" >&2; exit 10; }
grep -Fq 'bool bootFailover = false;' "$TYPES" || { echo "Boot failover request flag missing" >&2; exit 11; }
grep -Fq 'void ConnectionManager::tryBootFailover' "$CONNECTION" || { echo "Boot failover implementation missing" >&2; exit 12; }
grep -Fq 'queueUrlTests(candidates' "$CONNECTION" || { echo "Boot failover does not actively test candidates" >&2; exit 13; }

echo "PASS: dual local listeners and one-shot boot node failover patch verified."
