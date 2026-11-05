# Spec Delta

## Purpose

Defines the lifecycle and system integration of the VPN/proxy connection service: state machine, foreground notification, IPC binding from the UI process, and system entry points (quick settings tile, boot receiver, broadcast actions).

## ADDED Requirements

### Requirement: Connection state machine
The connection service SHALL expose exactly the states Connecting, Connected, Stopping, Stopped, and SHALL transition through them in order when starting or stopping a profile. The service SHALL never report Idle; Idle is a UI-only state shown before any service is bound.

#### Scenario: Start a profile
- **WHEN** the user starts a profile from the UI
- **THEN** the service reports Connecting, and on success reports Connected; failures surface as an error state with a user-visible message

#### Scenario: Stop a running connection
- **WHEN** the user stops a connected service
- **THEN** the service reports Stopping and then Stopped, and the VPN interface is torn down

### Requirement: Foreground service notification
While the service runs it SHALL present a foreground notification showing the current profile and traffic statistics, and SHALL offer quick actions including stop and switch to next/previous profile.

#### Scenario: Statistics visible in notification
- **WHEN** a connection is established and traffic flows
- **THEN** the notification shows updated uplink/downlink totals

### Requirement: UI process binding
The UI process SHALL communicate with the running service over an AIDL connection, receiving state changes, traffic updates, and test results, and SHALL degrade gracefully (show Idle) when no service is running.

#### Scenario: UI reconnects to a running service
- **WHEN** the UI process starts while a service is already running
- **THEN** it binds, receives the current state, and reflects it without restarting the service

### Requirement: External control actions
The app SHALL support broadcast/Intent actions to stop the service, reload the running configuration, switch to the next or previous profile, and switch to a profile by id, invokable from the notification, widgets, and quick settings tile.

#### Scenario: Quick settings tile toggle
- **WHEN** the user taps the quick settings tile with no active connection
- **THEN** the app starts the currently selected profile; tapping again stops it

### Requirement: VPN and proxy modes
The service SHALL support VPN mode (TUN device capturing device traffic, with per-app allow/bypass lists) and proxy-only mode, selected by the user's service mode setting.

#### Scenario: VPN mode with per-app proxy
- **WHEN** the user enables per-app proxy with an allow list and starts the VPN
- **THEN** only traffic of the listed apps is routed through the tunnel
