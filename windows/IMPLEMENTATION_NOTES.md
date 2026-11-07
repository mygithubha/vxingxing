# Windows implementation notes

Last source review: 2026-11-07

## Confirmed in the pinned Throned desktop base

- The Windows build is a native Qt/C++ desktop application with a Go-based proxy core.
- The upstream CI builds the Windows x64 core on Ubuntu, then builds the Qt desktop application on a Windows runner, deploys a portable directory, and creates an NSIS installer.
- The core config generator currently emits one public `mixed` inbound when `disable_mixed_inbound` is false. It binds to `settings.inbound_address` and `settings.inbound_socks_port`. A sing-box `mixed` inbound accepts both HTTP CONNECT and SOCKS5 on that same port.
- The core also creates a separate random-authenticated loopback mixed inbound for the app's internal service proxy.
- TUN is a separate inbound and is only emitted when `settings.spmode_vpn` is enabled.
- Upstream documents reconnect-on-start policies: last profile, fastest measured profile, or random profile.
- The upstream Windows system-proxy implementation is in `src/sys/windows/SystemProxy.cpp`; it has apply and clear operations. Auto Connect must still verify exact restoration of pre-existing Windows settings across failed startup and shutdown.
- The upstream desktop client is GPL-3.0. Preserve license and attribution when redistributing or modifying it.

## Auto Connect changes still required

1. Separate public listeners: HTTP at `127.0.0.1:7890`, SOCKS5 at `127.0.0.1:7891`. Do not describe the existing single mixed listener as two independently configurable listeners.
2. Persist the last successfully working node by stable profile identity, not just a transient row index. On startup, test it first; if it fails, health-check the remaining candidates and select a reachable replacement.
3. Add a compact Auto Connect identity and a simplified first-run flow without removing upstream features or attribution.
4. Make browser-only mode leave Windows system proxy untouched; expose loopback endpoints for browser extensions.
5. Keep system-proxy mode distinct from TUN mode. TUN requires elevated privileges and separate Windows 10 route/DNS validation.
6. Keep LAN sharing disabled by default; if enabled later, bind only after explicit user action and require authentication.

## Build and release status

The upstream workflow is a detailed multi-job pipeline, not a single CMake command. It builds the Go core, downloads pinned Qt/OpenSSL toolchains, compiles and tests the Windows app, deploys the portable folder, then builds the NSIS installer. The Auto Connect branch does not yet contain a validated end-to-end Windows workflow or a tested Windows 10 artifact.

## Pinned upstream revision

`windows/throned` is pinned to `fbdc7a69cb2ccddc3915dc4de7ec50313a974f13` in the Auto Connect branch. Changes inside the submodule require a maintained fork or an explicit patch/build overlay; editing the submodule working tree alone will not create a durable change in the parent repository.
