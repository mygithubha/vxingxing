# Auto Connect — Product Acceptance Rules

Baseline: fd79f8dcb47725a54577e3a6fc94661a7b249ec8
Target device: vivo Android 15, non-rooted, bootloader locked.
App name: Auto Connect.

## P0 — Preserve the active connection

1. Subscription refresh, node import, metadata enrichment, IP lookup, latency checks, and cache maintenance MUST NOT stop, restart, rebuild, or replace a healthy active VPN session.
2. The running VPN must consume an immutable runtime configuration snapshot. Mutable subscription/profile storage is not the source of truth for an already-running session.
3. If a subscription refresh deletes or replaces the active profile row, the active runtime snapshot remains usable until the user disconnects or the running tunnel genuinely fails.
4. A background task must never call a service toggle/stop/start/rebuild path as a side effect of refreshing sources or measuring candidates.
5. Background probes must be protected from routing through the VPN tunnel being measured; verify the actual socket/network path and avoid proxy loops.
6. Network loss, Android process death, OS network handover, and remote server failure are external failure modes. The guarantee is that this app's refresh logic does not itself interrupt a healthy connection.

## App launch

- On a fresh app process, wait for the first subscription import and automatically start the selected mode once nodes are available.
- Do not start a second connection when the VPN is already running.
- If no nodes arrive within the bounded startup wait, leave the app usable and show a clear message; the user can retry manually.

## Automatic mode — Policy A (failure-only failover)

1. Keep the active node and exit IP while the active node is healthy.
2. Discovering a faster node MUST NOT trigger a proactive switch.
3. A single latency spike or transient probe failure MUST NOT trigger a switch.
4. Fail over only after persistent unavailability / confirmed disconnection, using a bounded failure-confirmation policy.
5. Apply retry limits, cooldown, and temporary quarantine for failed candidates. Never loop indefinitely.
   The current automatic rebuild budget is five attempts, with exponential cooldown from 60 seconds up to 10 minutes; after the limit, notify the user and wait for a manual recheck or a confirmed healthy member.
6. Select only eligible nodes: successful connectivity, known egress country, and country not in CN/HK/MO/TW.
7. If no eligible fallback exists, preserve truthful failure state and notify the user; never claim connected.

## Manual mode

1. Open the node list from local cache immediately; unknown fields display as Pending verification.
2. Opening the list is read-only and MUST NOT launch a full test sweep.
3. Enrich unknown IP/country/latency asynchronously without blocking the list.
4. Interaction is two-stage: select a row, confirm selection, then ask whether to connect immediately.
5. Connect only the explicitly selected node. A failed manual connection MUST NOT silently fall back to another node.
   If a connected strict-manual node becomes unavailable, report the failure and do not auto-rebuild or switch to a different node.
6. Returning to automatic mode restores automatic pool semantics without misrepresenting the currently running configuration.

## Node sources and health

- Keep source refresh, health checks, and runtime connection as independent lifecycles.
- Fetch and parse sources defensively; deduplicate; validate supported formats and impose input size/time limits.
- On source/network/parse failure, retain the previous known-good cache.
- Cache IP, egress country, test timestamp, latency, and health status; do not repeat fresh checks unnecessarily.
- Distinguish proxy server address from the actual VPN egress IP.
- First-run public feed: free-nodes/v2rayfree via the jsDelivr mirror; target refresh interval is six hours.
- The feed is third-party public data, not operated or security-audited by this app; every imported node must pass local testing and country filtering.
- Public free nodes are untrusted and have no long-term availability guarantee.

## App routing and privacy

- Use explicit per-app proxy allowlist; unselected apps remain direct by default.
- Keep recognizable finance, payment, identity/authenticator, and password-manager apps out of the proxy allowlist. Package-name keyword checks are defense-in-depth, not a complete classifier.
- Persist routing preferences and do not change them during node switching.
- Never log pairing credentials, subscription secrets, or device tokens.
- Do not upload installed-app inventories, bank-app inventories, browsing history, or personal identifiers.

## UI

- App label: Auto Connect.
- Support system light/dark themes.
- Clearly show connection state, selected node, server IP, actual egress IP, country, latency, and routing protection state.
- Show actionable feedback for refresh, selection, connection, switching, and failure.
- Background work must not block the main thread.

## Background scheduling

- Separate source refresh and health-check jobs.
- WorkManager is best-effort scheduling, not an exact timer. A six-hour target interval may be used where appropriate, subject to OS constraints, network availability, and backoff.
- Unique work, bounded retries, exponential backoff, and network/battery constraints are required.
- Background failures must not mutate the runtime snapshot.

## Release gates

- Static audit of service lifecycle, config generation, selector planner/core, subscription update, TestEngine network path, and workers.
- Unit tests for policy A, manual pinning, candidate eligibility, retry bounds, and runtime snapshot isolation.
- GitHub Actions OSS debug build and unit tests must pass.
- Deliver APK artifact, source branch/PR, commit SHA, workflow run, and a concise test report.
- A CI build is not proof of physical-device compatibility. Real installation and regression testing on vivo Android 15 are a separate release gate.
- Never describe an unsigned debug APK as a signed production release.
