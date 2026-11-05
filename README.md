# Auto Connect

Auto Connect is an Android proxy-client fork in active development. The current codebase is based on [Throne for Android](https://github.com/throneproj/ThroneForAndroid); upstream copyright and GPL-3.0 notices are retained.

## Intended product behavior

- Fetch configurable node sources, refresh them on demand or on a schedule.
- Parse supported subscription formats and de-duplicate imported nodes.
- Test candidate nodes concurrently and rank usable nodes by reachability and latency.
- Connect to the best eligible node and recover or fail over when a connection degrades.
- Keep banking, payment, password-manager, and authenticator apps outside the proxy by default, with explicit per-app rules.
- Show source freshness, test results, connection state, and protection state clearly.

These are product requirements, **not a claim that every feature is already implemented**. The current upstream base provides the proxy engine, VPN service, profile management, connection testing, and per-app routing primitives. Auto Connect-specific startup automation, trusted defaults, and end-to-end safety verification remain in development.

## Security notice

Public proxy nodes are untrusted third-party infrastructure. A successful latency or connectivity test does not establish operator trust, confidentiality, or safety. Do not use untrusted nodes for banking, payments, account recovery, passwords, or sensitive communications.

Per-app bypass only excludes selected apps from this app's VPN routing. It is not a guarantee against device compromise, other VPNs, Android system-level routing, or DNS behavior outside this app's control. The current development branch now defaults to proxying only explicitly selected apps; with an empty selection, Android routes only Auto Connect itself through the VPN. This reduces accidental exposure, but it is not a substitute for verifying package rules on a physical Android 15 device. Do not assume device-wide protection against other VPNs or system-level routing.

## Build status

- [x] Public repository and initial product/security requirements
- [x] Audited upstream Android code imported to the `upstream-import` branch
- [x] Launcher app label changed to Auto Connect
- [ ] First complete Android build
- [ ] OSS debug unit tests
- [ ] Automatic source refresh and best-node startup flow
- [x] Selected-app proxy mode defaults to allowlist; empty allowlist fails closed to Auto Connect only
- [ ] Verify sensitive-app bypass on Android 15 and show a user-visible protection status
- [ ] Physical-device verification on vivo Android 15 (no root)
- [ ] Installable APK artifact and release

Build progress: [GitHub Actions](https://github.com/mygithubha/vxingxing/actions/workflows/android-ci.yml?query=branch%3Aupstream-import).

## License

This project is derived from Throne for Android and is subject to GPL-3.0. See [LICENSE](LICENSE), [AUTHORS](AUTHORS), and [AUTO_CONNECT_FORK_NOTICE.md](AUTO_CONNECT_FORK_NOTICE.md).