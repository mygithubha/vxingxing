# Spec Delta

## Purpose

Defines home-screen widget behavior: the status widget showing the running state and statistics, the toggle widget for starting/stopping the connection, and widget responsiveness to connection state changes.

## ADDED Requirements

### Requirement: Status widget
The app SHALL provide a home-screen widget that displays the connection state (running or stopped) and current traffic statistics, refreshed automatically while the connection is active.

#### Scenario: State change reflected
- **WHEN** the connection state changes while the status widget is on the home screen
- **THEN** the widget updates to show the new state without user interaction

### Requirement: Toggle widget
The app SHALL provide a one-tap toggle widget that starts the selected profile when stopped and stops the service when running.

#### Scenario: Toggle from home screen
- **WHEN** the user taps the toggle widget while stopped
- **THEN** the selected profile starts; tapping again stops it

### Requirement: Widget Tap targets
Tapping the widget body SHALL open the app's main screen, and widget actions SHALL work even when the app's UI process is not running in the foreground.

#### Scenario: Cold-start toggle
- **WHEN** the user taps the toggle widget after the app process was killed
- **THEN** the connection still starts
