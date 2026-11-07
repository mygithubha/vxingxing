# Auto Connect for Windows 10

A separate Windows desktop client for the Auto Connect project. The Android app remains unchanged.

## Target

- Windows 10 x64 (19041+), .NET 8 Desktop Runtime is not required when using the self-contained release.
- sing-box Windows amd64 core, downloaded from the official SagerNet release.
- Local HTTP and SOCKS5 proxy listeners bound to 127.0.0.1 by default.

## Planned operating modes

1. Browser-only: configure a browser proxy extension to use HTTP `127.0.0.1:2081` or SOCKS5 `127.0.0.1:2082`.
2. Windows system proxy: set the Windows user proxy to the local HTTP listener. Only applications that honor the Windows system proxy are covered; this is not packet-level global routing.
3. Full-device TUN: use sing-box TUN with the official Wintun driver and administrator approval. This is the actual full-device routing mode and will be implemented separately from system-proxy mode.

## Safety defaults

- Local listeners bind to loopback only; no LAN exposure by default.
- System proxy changes are restored when the app disconnects or exits.
- No third-party node subscriptions are silently added.
- Do not route banking/payment/password-manager applications through the proxy by default.
- A failed core startup must not leave Windows proxy settings enabled.

## Current implementation status

The Windows client is being developed independently under this directory. Do not treat this README or a successful compile as proof of runtime proxy functionality. Each release must pass build, unit/integration tests, and a Windows 10 smoke test.
