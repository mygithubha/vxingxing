# Auto Connect for iOS

Target device: iPhone 13 / iOS 18. Minimum deployment target: iOS 15.

This is a separate iOS implementation. The Android app and its build pipeline are not modified.

## Product requirements

- Minimal SwiftUI screen matching the Android Auto Connect layout.
- Subscription URL import and profile parsing.
- Connect/disconnect using Apple's NetworkExtension Packet Tunnel.
- Reuse the last successfully connected outbound before probing alternatives.
- Persist a stable outbound identifier, not only its display name.
- Show connection and selected-outbound health states.
- Keep automatic fallback and reconnection conservative to avoid unnecessary probes.

## Core and platform

Use the official sing-box Apple platform implementation as the reference for the Apple TUN and Packet Tunnel integration. Do not attempt to call Android VpnService APIs on iOS.

The Packet Tunnel extension must host the supported sing-box Apple core (libbox) and receive its configuration through an App Group shared container. The app target manages VPN profiles with NETunnelProviderManager.

## iOS limitations

- iOS does not expose Android-style per-installed-app VPN selection to ordinary consumer apps. Per-app VPN is generally an MDM-managed capability. The first iOS release will use supported system-wide VPN routing and explicit network/domain routing rules where appropriate; it will not present a fake app picker.
- A real-device VPN build requires the Network Extension entitlement and correctly provisioned app + Packet Tunnel extension identifiers.
- The simulator can validate UI only; VPN tunnel operation must be tested on a physical iPhone.

## Signing and distribution

A signed installable IPA for a personal iPhone requires an Apple signing team and matching provisioning for both the app and Packet Tunnel extension. Network Extension capability availability must be confirmed before promising an installable VPN build.

## Initial implementation sequence

1. Confirm Apple Developer signing capability and bundle identifiers.
2. Add the SwiftUI UI shell matching the Android screenshot.
3. Integrate the Apple sing-box core and Packet Tunnel extension.
4. Implement subscription parsing, last-successful-node persistence, health checks, and fallback.
5. Build on macOS CI, validate simulator UI, then test the tunnel on iPhone 13 / iOS 18.
