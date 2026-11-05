# Product requirements

## Target platform

- Android 7.0+ baseline inherited from the audited upstream project; verify compatibility on Android 15 / vivo before release.
- No root or bootloader unlock required.
- Use Android VpnService and its supported per-app allow/disallow APIs.

## Startup flow

1. Load persisted safety rules before starting a VPN session.
2. Load configured node sources and fetch updates with bounded timeouts.
3. Parse supported subscription formats; reject malformed entries and de-duplicate.
4. Run concurrent URL connectivity tests with configurable concurrency and timeout.
5. Rank eligible nodes by successful reachability first, then latency; never rank a failed node as usable.
6. Start the best eligible node after the user has granted VPN permission.
7. If connection or health checks fail, retry with backoff and switch to the next eligible node.
8. Expose current node, last test time, source status, and failover reason.

## Node source policy

- Sources are configurable and removable.
- Do not silently trust arbitrary remote configuration or execute downloaded code.
- Keep source attribution and last-update status visible.
- Public sources are best-effort and may disappear or contain malicious endpoints.
- Never embed credentials or private subscription URLs in source control or logs.

## Per-app isolation

- Default policy: protected/sensitive apps bypass the proxy; unknown apps use direct routing until the user explicitly changes the policy.
- Provide explicit modes: selected apps bypass, only selected apps proxy, and user-enabled global proxy.
- Keep protected-app exclusions effective across node switches, source refreshes, and app restarts.
- Let users inspect and edit package-level rules. Do not rely only on guessed app categories.
- If rules cannot be loaded or applied, show a prominent warning and do not claim isolation is active.
- Explain Android limitations, including always-on VPN / lockdown interactions.

## User experience

- One primary connect state.
- Clear states: fetching, testing, connecting, connected, switching, no usable nodes, permission required, and error.
- Manual stop and manual node pinning must override automatic switching until released.
