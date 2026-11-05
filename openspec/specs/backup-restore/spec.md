# Backup Restore Specification

## Purpose

Defines configuration backup and restore: the `.thrbackup` container format shared with the desktop client, partial backups by content part, validation of foreign containers, and restore behavior including conflict handling.

## Requirements

### Requirement: Backup container format
The app SHALL write backups as `.thrbackup` containers compatible with the desktop client: a little-endian Qt_6_0 data stream with the "THRN" magic, format version 2, metadata JSON, and file entries including the SQLite database; the app SHALL refuse to write any format version the desktop cannot read.

#### Scenario: Write a backup
- **WHEN** the user exports a backup
- **THEN** the produced `.thrbackup` file declares format version 2 and contains the database entry

### Requirement: Partial backup selection
The user SHALL be able to choose which content parts to back up (profiles, routes, settings, OTP secrets, icons); a backup SHALL contain only the selected parts.

#### Scenario: Backup only profiles
- **WHEN** the user exports a backup selecting profiles only
- **THEN** restoring that backup changes profiles but leaves routes and settings untouched

### Requirement: Restore validation
On restore, the app SHALL validate the container magic, format version, and entry sizes, and SHALL reject containers that are not backups, use an unsupported version, or are corrupt, with distinct error messages and without modifying existing data.

#### Scenario: Restore an unsupported version
- **WHEN** the user selects a `.thrbackup` written by a newer format
- **THEN** the restore aborts with an unsupported-version error and no data changes

#### Scenario: Restore a non-backup file
- **WHEN** the user selects a file without the THRN magic
- **THEN** the app reports the file is not a valid backup

### Requirement: Cross-platform restore
The app SHALL restore databases produced by the desktop client, applying only the database entry and skipping unknown file entries such as desktop icons.

#### Scenario: Restore a desktop backup
- **WHEN** the user restores a `.thrbackup` exported on desktop
- **THEN** profiles, routes, and settings from the desktop database appear in the app

### Requirement: Auto-backup scheduling
The app SHALL support scheduled automatic backups to local storage according to the user's configured interval, and SHALL keep the most recent backup accessible from the UI.

#### Scenario: Scheduled backup runs
- **WHEN** the configured auto-backup interval elapses
- **THEN** a new backup file is written without user interaction
