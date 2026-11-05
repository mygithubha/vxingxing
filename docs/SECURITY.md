# Security model and release checklist

## Threat model

Public proxy endpoints are operated by unknown parties. They can observe connection metadata and may manipulate or log traffic. TLS protects some application traffic end-to-end, but does not make an unknown proxy trustworthy.

## Required safeguards

- Sensitive-app bypass enabled by default and persisted locally.
- Unknown apps default to direct routing.
- Per-app rules are applied before the VPN tunnel starts and re-applied after reconnect.
- No logging of subscription URLs, credentials, tokens, or full sensitive destination URLs.
- Validate downloaded subscription data; enforce response-size and timeout limits.
- Do not execute scripts or binaries from node sources.
- Show source URL and last refresh time.
- Provide a clear warning that latency/availability checks are not security checks.

## Verification before APK release

- Confirm the app list and package-name rules on Android 15.
- Verify a banking/payment app's traffic is not sent through the proxy tunnel.
- Verify general apps selected for proxy do use the tunnel.
- Verify rules persist after force-stop, reboot, node switch, and source update.
- Verify behavior with Android VPN permission revoked and with always-on VPN / lockdown enabled.
- Verify no-node, timeout, malformed-subscription, and network-loss states.
- Publish corresponding source and license notices for all distributed GPL-derived code.
