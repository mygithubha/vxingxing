# Spec Delta

## Purpose

Defines diagnostics behavior: in-app log viewing, core log capture, log export and sharing, and redaction of sensitive information before logs leave the device.

## ADDED Requirements

### Requirement: In-app log viewer
The app SHALL provide a log viewer that displays the running core's log output in real time, with actions to pause, clear, and filter the view.

#### Scenario: View live logs
- **WHEN** the user opens the log viewer while connected
- **THEN** new core log lines appear as they are produced

### Requirement: Log export and sharing
The user SHALL be able to export the full log to a file and share it via the system share sheet, including core logs from the current and previous sessions as available.

#### Scenario: Export a log file
- **WHEN** the user taps export in the log viewer
- **THEN** a log file is created and offered for sharing

### Requirement: Sensitive data redaction
Before a log is exported or shared, the app SHALL redact sensitive fields — server addresses, credentials, UUIDs, passwords, and private keys — so that shared logs do not leak connection secrets.

#### Scenario: Redacted export
- **WHEN** the user shares an exported log that contains a profile's password
- **THEN** the password is replaced by a placeholder in the shared content

### Requirement: Crash and error reporting path
Unexpected service errors SHALL be captured with stack traces in the log so the user can include them in exported diagnostics, without sending any data off-device automatically.

#### Scenario: Service error captured
- **WHEN** the connection service hits an unexpected error
- **THEN** the error and stack trace appear in the log and no data is transmitted automatically
