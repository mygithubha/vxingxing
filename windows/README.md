# Auto Connect for Windows 10

## Scope

A separate Windows 10 x64 desktop client. The Android application remains unchanged. Target baseline: Windows 10 19042+.

## Selected desktop base

The repository now pins the public **Throned** Qt desktop client as a Git submodule at `windows/throned`:

- Upstream: https://github.com/troshkindm/throned
- Pinned revision: `fbdc7a69cb2ccddc3915dc4de7ec50313a974f13`
- Throned is an unofficial Throne desktop fork, GPL-3.0, with Windows and Linux support.

This is a better foundation than rewriting the Android application as a Windows app. Throned already contains a Windows build pipeline, native Windows system-proxy integration, subscription/profile parsing, sing-box configuration generation, mixed HTTP/SOCKS inbound support, and TUN mode. Its README also documents reconnect-on-start choices including the last profile and fastest measured profile.

## Auto Connect requirements to implement on top of the desktop base

- Rebrand the Windows desktop build as Auto Connect without removing upstream license/copyright notices.
- Import the same subscription URL formats supported by the Android client; report unsupported formats rather than silently dropping them.
- Remember the last successfully connected node by stable identity. Try it first on the next start; if a fresh health check fails, probe the pool and select a healthy replacement.
- Show connection state, current node, HTTP listener state, SOCKS5 listener state, and last health-check result separately. A running core is not proof of working internet access.
- Provide independent HTTP and SOCKS5 local listeners. Defaults: `127.0.0.1:7890` and `127.0.0.1:7891`.
- Browser-only mode: leave Windows system proxy unchanged; browser extensions can use HTTP or SOCKS5 loopback listeners.
- Windows system-proxy mode: apply the HTTP listener to Windows and restore the user's previous settings on stop or failed startup.
- TUN mode: optional true device-wide routing, clearly distinguished from Windows system-proxy mode.
- Optional LAN sharing is disabled by default. When enabled, require explicit confirmation and authentication; never expose an unauthenticated proxy listener by default.
- Keep banking, payment, password-manager, and authenticator apps outside any future automatic proxy allowlist.
- Keep subscription credentials and node secrets out of logs.

## Proxy modes

| Mode | Behavior |
| --- | --- |
| Browser only | Local HTTP/SOCKS5 listener; no Windows proxy changes |
| Windows system proxy | Windows applications that honor WinINet/system proxy use the HTTP listener |
| TUN global | Route device traffic through the TUN interface; requires Windows-specific driver/permission validation |
| Optional LAN share | Explicitly enabled, authenticated listener for other devices on the LAN |

A mixed inbound can accept both HTTP CONNECT and SOCKS5, but the product should expose independently configurable HTTP and SOCKS5 endpoints for easier browser-extension setup.

## Security defaults

- Loopback binding only (`127.0.0.1`); LAN sharing off by default.
- If LAN sharing is enabled, show the bind address and require credentials.
- Restore the exact prior Windows system-proxy settings on stop and all failed-start paths.
- Do not log subscription URLs, passwords, UUIDs, or full generated configurations.
- A public node passing a latency test does not establish operator trust or protect sensitive accounts.

## Release acceptance checklist

1. Clean Windows 10 x64 VM can launch, update, and uninstall the packaged application.
2. Import and parse supported subscription formats with per-node error reporting.
3. HTTP and SOCKS5 listeners pass independent connectivity tests.
4. Browser extension mode proxies only the selected browser while other applications remain direct.
5. System-proxy mode applies and restores prior Windows settings.
6. TUN mode passes DNS, IPv4, IPv6, route-leak, sleep/resume, and stop/recovery tests.
7. Last-node preference survives restart; an unavailable saved node is rejected and replaced by a healthy node.
8. CI publishes a versioned portable package and installer only after tests pass.

## Delivery rule

A successful compile is not a successful proxy test. Do not label a build as ready until the executable, proxy listeners, system-proxy restoration, and Windows 10 smoke tests have passed.
