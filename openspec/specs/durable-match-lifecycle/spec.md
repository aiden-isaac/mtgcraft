# Durable Match Lifecycle

## Purpose

Define durable match tracking, conservative recovery, exactly-once terminal effects, and reproducible lifecycle diagnostics.

## Requirements

### Requirement: Durable admission and lifecycle records
Minecraft SHALL persist a stable world ID, project match/seat IDs, player UUID, immutable snapshot/digest, reservation reference, engine/mapping versions, service incarnation, operation progress, and terminal/release records. Admission intent and successful reservation SHALL be durable before remote creation. Lifecycle operations SHALL tolerate retries and SHALL NOT block the Minecraft tick thread on network or engine work.

#### Scenario: Minecraft stops after reservation but before creation acknowledgement
- **WHEN** the server restarts with an unfinished create operation
- **THEN** it queries/retries using the same durable match ID and retains the reservation until creation or safe cancellation is resolved

### Requirement: Exactly-once local terminal effect
Result delivery SHALL be treated as repeatable. Minecraft SHALL validate each terminal record against its world, match, seats, snapshot, service identity, and expected lifecycle; commit the first valid result and a release intent atomically; and deduplicate by match and result identity. Repeated identical results SHALL have no additional domain effect. Conflicting terminal records SHALL be quarantined for investigation and SHALL NOT overwrite an accepted result.

#### Scenario: Completion is received repeatedly
- **WHEN** polling or a restart retrieves the same terminal result more than once
- **THEN** one result is recorded and one recoverable reservation-release operation exists

#### Scenario: Result identifies another submitted deck
- **WHEN** a terminal record contains the wrong snapshot digest, player seat, or world
- **THEN** it is rejected without releasing that match's reservation or recording a winner

### Requirement: Disconnect and surviving-engine reconciliation
A Minecraft client disconnect SHALL NOT discard its match/seat association or release the reservation. On Minecraft server restart, pending matches SHALL be reconciled against the surviving service's durable records. External-client reconnection SHALL reuse the intended seat subject to the pinned XMage timeout behavior; the implementation SHALL record experimentally observed limitations.

#### Scenario: Minecraft player disconnects during external play
- **WHEN** the player leaves Minecraft while XMage remains active
- **THEN** the match association and reservation persist and completion can be processed without the player online

#### Scenario: Minecraft restarts while the engine survives
- **WHEN** Minecraft recovers a running match and the authenticated service reports it still active
- **THEN** Minecraft reattaches lifecycle tracking without creating a new match or changing the submitted snapshot

### Requirement: Conservative interruption and cancellation policy
Service unreachability or an HTTP timeout SHALL mean unknown status, not confirmed engine death. Minecraft SHALL retain reservations and retry with bounded backoff. Explicit cancellation SHALL release custody only after authenticated terminal confirmation or verified fencing of the old service/match. Confirmed loss of an engine incarnation SHALL produce an interrupted record with no invented winner or prizes; no automatic replay or mid-decision restoration is required. A late message from a fenced incarnation SHALL NOT reverse interruption.

#### Scenario: Service temporarily disappears from the network
- **WHEN** status requests time out but process loss is not established
- **THEN** Minecraft reports reconciliation pending and retains the deck reservation

#### Scenario: Engine loss is confirmed
- **WHEN** a new authenticated service incarnation or a verified operator fencing procedure establishes that the old engine can no longer run the match
- **THEN** the lifecycle records interruption once and schedules return of the original reserved deck

#### Scenario: Cancellation acknowledgement is lost
- **WHEN** Minecraft cannot confirm whether cancellation stopped the match
- **THEN** it retains the reservation and retries/reconciles the same cancellation instead of assuming success

### Requirement: Cleanup without loss of reconciliation evidence
Terminal matches SHALL release active engine controllers, AI work, managed seat access, and reservation exclusivity. Terminal records and idempotency tombstones SHALL survive resource cleanup and service restart. Unclaimed physical returns SHALL remain durable and SHALL NOT be deleted by engine cleanup.

#### Scenario: Service cleans up a completed game
- **WHEN** active game resources are removed after terminal persistence
- **THEN** later Minecraft reconciliation still retrieves the same terminal result and cannot recreate the match ID

### Requirement: Reproducible spike diagnostics
The spike SHALL produce a report with pinned build/catalog/configuration identities and measured startup-to-ready time, process RSS and JVM heap, per-match memory growth, AI decision/turn latency, adapter round-trip latency, cleanup behavior, and disconnect/service-loss behavior. Measurements SHALL distinguish observed values from unknowns, include sample counts and methodology, and redact credentials and private deck contents from routine logs.

#### Scenario: Acceptance run is recorded
- **WHEN** the spike is evaluated
- **THEN** the report includes a completed human-versus-AI run, repeated lifecycle runs, a two-match concurrency run, duplicate-message tests, restart/disconnect/crash cases, resource measurements, and unresolved limitations
