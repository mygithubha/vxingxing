#!/usr/bin/env python3
"""Apply Auto Connect's failure-only dial policy to the pinned Throne sing-box module.

The Android app pins ThroneCore in nb4a.properties. ThroneCore in turn replaces
sing-box with a forked module. This patch is applied only in the reproducible
GitHub Actions build workspace; the script and the exact policy are versioned
in this repository.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_autoselector_failure_only.py <sing-box-module-dir>")

root = Path(sys.argv[1])
source = root / "protocol/group/autoselector_health.go"
tests = root / "protocol/group/autoselector_health_test.go"
if not source.is_file() or not tests.is_file():
    raise SystemExit(f"unexpected sing-box source layout: {root}")

text = source.read_text()
old = """\tstep := h.cooldownStep
\tif step >= len(backoff) {
\t\tstep = len(backoff) - 1
\t}
\th.cooldownUntil = now.Add(backoff[step])
\th.cooldownAt = now
\tif h.cooldownStep < len(backoff)-1 {
\t\th.cooldownStep++
\t}
"""
new = """\t// Auto Connect policy A: one or two destination-specific dial failures are
\t// not enough to mark the selected exit unhealthy. reward() resets dialFail
\t// on any successful dial, so this is three consecutive failures.
\tif h.dialFail < 3 {
\t\treturn
\t}
\tstep := h.cooldownStep
\tif step >= len(backoff) {
\t\tstep = len(backoff) - 1
\t}
\th.cooldownUntil = now.Add(backoff[step])
\th.cooldownAt = now
\tif h.cooldownStep < len(backoff)-1 {
\t\th.cooldownStep++
\t}
"""
if "Auto Connect policy A: one or two destination-specific dial failures" not in text:
    if old not in text:
        raise SystemExit("unexpected penalize() implementation; refusing an unsafe partial patch")
    source.write_text(text.replace(old, new, 1))

test_text = tests.read_text()
test = """
func TestDialFailureOnlyAppliesCooldownAfterThreeConsecutiveFailures(t *testing.T) {
    now := time.Now()
    h := newMemberHealth("a", 10, time.Hour)

    h.penalize(now, dialBackoff, errors.New("transient destination failure"))
    if h.inCooldown(now) {
        t.Fatal("one failed dial must not move traffic away from a healthy incumbent")
    }

    h.penalize(now.Add(time.Second), dialBackoff, errors.New("transient destination failure"))
    if h.inCooldown(now.Add(time.Second)) {
        t.Fatal("two failed dials must not move traffic away from a healthy incumbent")
    }

    h.penalize(now.Add(2*time.Second), dialBackoff, errors.New("persistent node failure"))
    if !h.inCooldown(now.Add(2*time.Second)) {
        t.Fatal("three consecutive failed dials must quarantine the failed member")
    }

    h.reward(now.Add(3 * time.Second))
    if h.inCooldown(now.Add(3*time.Second)) || h.dialFail != 0 {
        t.Fatal("a successful dial must clear the consecutive-failure state")
    }
}
"""
if "TestDialFailureOnlyAppliesCooldownAfterThreeConsecutiveFailures" not in test_text:
    tests.write_text(test_text.rstrip() + "\n" + test)

print(f"Patched failure-only dial confirmation in {root}")
