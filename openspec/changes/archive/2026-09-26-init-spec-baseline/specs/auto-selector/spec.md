# Spec Delta

## Purpose

Defines the auto-selector behavior: periodically or manually testing candidate profiles within a scope and automatically switching the active connection to the best available node.

## ADDED Requirements

### Requirement: Automatic node selection
The app SHALL, when auto-selector mode is enabled for the running connection, test candidate profiles and switch the active node to the best-scoring available candidate without user intervention.

#### Scenario: Current node degrades
- **WHEN** the active node fails its test while another candidate passes
- **THEN** the app switches the connection to a passing candidate and shows the switch in the UI

### Requirement: Candidate scope
The user SHALL be able to constrain auto-selector to a group and to a minimum number of candidates, and the selector SHALL only switch among profiles within that scope.

#### Scenario: Restricted scope
- **WHEN** auto-selector is scoped to one group
- **THEN** nodes outside that group are never selected

### Requirement: Manual pinning
The user SHALL be able to pin a specific node, suspending automatic switching; the pin SHALL be releasable by the user and by a control action, after which automatic selection resumes.

#### Scenario: Pin and release
- **WHEN** the user pins a node and later releases the pin
- **THEN** automatic selection resumes on the next evaluation

### Requirement: Selector state visibility
The auto-selector SHALL expose its current choice and last test results to the UI and connection notification, so the user can see which node is active and why.

#### Scenario: Selector status screen
- **WHEN** the user opens the auto-selector status screen
- **THEN** current node, candidates, and latest test latencies are shown
