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
dial_tests = root / "protocol/group/autoselector_dial_test.go"
if not source.is_file() or not tests.is_file() or not dial_tests.is_file():
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

# Update the upstream tests that previously required immediate cooldown after one dial failure.
dial_text = dial_tests.read_text()
dial_start = dial_text.index("func TestRoutedDialFailureMovesSelectionOff(t *testing.T) {")
dial_end = dial_text.index("\nfunc TestRoutedDialFailureSkipsMemberHandles", dial_start)
dial_replacement = """func TestRoutedDialFailureMovesSelectionOffAfterThreeFailures(t *testing.T) {
    now := time.Now()
    s := dialSelector("flow", "spare")
    fill(s.members["flow"], now, 80*time.Millisecond, 80*time.Millisecond)
    fill(s.members["spare"], now, 120*time.Millisecond, 120*time.Millisecond)
    s.evaluateLocked(now)
    if selected := s.selectedFor(N.NetworkTCP); selected == nil || selected.Tag() != "flow" {
        t.Fatalf("selected = %v, want flow", selected)
    }

    err := E.Cause(syscall.ECONNREFUSED, "open connection to 192.0.2.1:443 using outbound/wireguard[flow]")
    for i := 1; i <= 2; i++ {
        s.DialFailed(s.outbounds["flow"], N.NetworkTCP, err)
        if health := s.members["flow"]; health.inCooldown(time.Now()) {
            t.Fatalf("flow entered cooldown after only %d failed dial(s)", i)
        }
    }
    s.DialFailed(s.outbounds["flow"], N.NetworkTCP, err)
    if health := s.members["flow"]; !health.inCooldown(time.Now()) || health.dialFail != 3 {
        t.Fatalf("flow dialFail = %d, cooldown until %v; want three-failure penalty", health.dialFail, health.cooldownUntil)
    }
    if !roundRequested(s) {
        t.Fatal("a routed failure must pull the next round forward")
    }

    s.evaluateLocked(time.Now())
    if selected := s.selectedFor(N.NetworkTCP); selected == nil || selected.Tag() != "spare" {
        t.Fatalf("selected = %v, want spare after confirmed failure")
    }
}
"""
dial_text = dial_text[:dial_start] + dial_replacement + dial_text[dial_end:]
dial_tests.write_text(dial_text)

health_text = tests.read_text()
health_start = health_text.index("func TestCooldownBackoffAndRollback(t *testing.T) {")
health_end = health_text.index("\nfunc TestCooldownRollbackIgnoresOlderPenalties", health_start)
health_replacement = """func TestCooldownBackoffAndRollback(t *testing.T) {
    now := time.Now()
    h := newMemberHealth("a", 10, time.Hour)

    h.penalize(now, dialBackoff, errors.New("refused"))
    if h.inCooldown(now.Add(time.Second)) {
        t.Fatal("one failed dial must not cool down the incumbent")
    }
    h.penalize(now.Add(time.Second), dialBackoff, errors.New("refused"))
    if h.inCooldown(now.Add(2*time.Second)) {
        t.Fatal("two failed dials must not cool down the incumbent")
    }
    h.penalize(now.Add(2*time.Second), dialBackoff, errors.New("refused"))
    if !h.inCooldown(now.Add(3*time.Second)) {
        t.Fatal("three consecutive failures must start the first cooldown")
    }
    if h.inCooldown(now.Add(dialBackoff[0] + 4*time.Second)) {
        t.Fatal("first cooldown step must be short")
    }

    h.penalize(now.Add(dialBackoff[0]+5*time.Second), dialBackoff, errors.New("refused"))
    h.penalize(now.Add(dialBackoff[0]+6*time.Second), dialBackoff, errors.New("refused"))
    h.penalize(now.Add(dialBackoff[0]+7*time.Second), dialBackoff, errors.New("refused"))
    if !h.inCooldown(now.Add(dialBackoff[0] + 8*time.Second)) {
        t.Fatal("consecutive confirmed failures must extend the backoff")
    }

    if !h.clearCooldownSince(now.Add(-time.Second)) {
        t.Fatal("clearCooldownSince must report a rollback")
    }
    if h.inCooldown(now) || h.cooldownStep != 0 || h.dialFail != 0 {
        t.Fatal("rollback must fully reset the penalty state")
    }
}
"""
health_text = health_text[:health_start] + health_replacement + health_text[health_end:]
tests.write_text(health_text)
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
