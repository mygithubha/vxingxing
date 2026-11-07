# Auto Connect for Windows 10

## Scope

A separate Windows 10 x64 desktop client. The Android application remains unchanged. Target baseline: Windows 10 19042+ and .NET 8 WPF.

## Product requirements

- Import the same subscription URL formats supported by the Android client; do not silently discard unsupported protocols.
- Refresh subscriptions on demand and show the last successful refresh time.
- Test candidates, remember the last successfully connected node by stable identity, try it first on the next start, and fail over only after a fresh failed health check.
- Show engine state, selected node, proxy listener state, and last health-check result separately. A running process is not proof of working internet access.
- Provide independent HTTP and SOCKS5 local listeners. Defaults: 127.0.0.1:7890 and 127.0.0.1:7891.
- Browser-only mode: keep Windows system proxy unchanged; the user configures a browser proxy extension with the local listener address and port.
- Windows system-proxy mode: explicitly apply and restore the user's previous Windows proxy settings.
- TUN mode: optional true device-wide routing using the supported Windows TUN/Wintun integration. It must be clearly distinguished from Windows system-proxy mode and require elevation only when necessary.
- Optional LAN sharing is disabled by default. When enabled, bind only after an explicit confirmation, show the LAN address, and require an access password; never expose an unauthenticated proxy listener by default.
- Provide a one-click stop action that terminates the core and restores system proxy settings.
- Keep secrets and subscription credentials out of logs.

## Architecture decision

- UI and settings: C# / WPF (.NET 8, Windows x64).
- Proxy engine: official sing-box Windows x64 runtime, version pinned and checksum-verified by CI.
- Windows system proxy: WinINet settings with refresh notification and reliable restoration of the prior values.
- Browser-only: local HTTP and SOCKS5 inbounds; no system proxy mutation.
- TUN: a separate mode using sing-box TUN inbound and the official Windows TUN driver. Do not claim this mode works until tested on a clean Windows 10 machine.
- Keep the Windows project isolated under `windows/`; do not modify Android source sets or Android build jobs.

## Security defaults

- Bind local listeners to loopback only.
- LAN sharing off by default and password-protected when enabled.
- No automatic proxying of banking, payment, password-manager, or authenticator applications in any future app-exclusion UI.
- Stop/failed-start paths restore the exact previous Windows proxy state.
- Never log subscription URLs, passwords, UUIDs, or full generated configuration.

## Release acceptance checklist

1. Clean Windows 10 x64 VM can install, launch, update, and uninstall.
2. Import and parse supported subscription formats with per-node error reporting.
3. HTTP and SOCKS5 listeners pass independent connectivity tests.
4. Browser extension can proxy only the selected browser while other applications remain direct.
5. System-proxy mode applies and restores prior Windows settings.
6. TUN mode passes DNS, IPv4, IPv6, route-leak, sleep/resume, and stop/recovery tests.
7. Last-node preference survives restart; an unavailable saved node is rejected and replaced with a healthy node.
8. CI publishes a versioned portable ZIP and installer only after tests pass.

## Delivery rule

A successful compile is not a successful proxy test. Do not label a build as ready until the executable, embedded runtime, listener tests, and Windows 10 smoke tests have passed.