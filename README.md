# Auto Connect

An Android proxy client focused on automatic node discovery, health checks, fast-node selection, failover, and per-app traffic isolation.

## Product goals

- Fetch configurable public node sources and refresh them on demand or on a schedule.
- Parse supported subscription formats and de-duplicate imported nodes.
- Run concurrent URL-based connectivity tests; rank only nodes that pass the usability check.
- Connect to the best eligible node and re-test/switch when the active node fails.
- Keep banking, payment, password-manager, and authenticator apps outside the proxy by default, with explicit user-controlled per-app rules.
- Show clear connection, test, source, and protection status.

## Safety

Public proxy nodes are untrusted third-party infrastructure. A successful latency test does not establish operator trust, confidentiality, or safety. Do not use untrusted nodes for banking, payments, account recovery, or sensitive communications.

Per-app bypass means the selected app is excluded from this app's VPN routing. It is not a guarantee against device compromise, other VPNs, Android system-level routing, or DNS behavior outside this app's control.

## Development base

The planned implementation is based on [Throne for Android](https://github.com/throneproj/ThroneForAndroid), subject to a source/license audit and preservation of all required notices and GPL-3.0 obligations. This repository is the product repository; upstream source integration is a separate first milestone.

## Status

- [x] Public project repository created
- [x] Initial product and security requirements documented
- [ ] Import and pin the audited Android codebase
- [ ] Implement startup source refresh and node selection policy
- [ ] Implement protected-app defaults and per-app rule UI
- [ ] Add Android CI build and tests
- [ ] Verify APK on a physical Android 15 device
