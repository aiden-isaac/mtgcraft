## ADDED Requirements

### Requirement: Separate engine deployment and isolated types
XMage SHALL run in a separate process from Minecraft. A project-owned service extension SHALL own XMage-specific orchestration, IDs, sessions, callbacks, and types. Minecraft SHALL communicate through versioned project-owned HTTP/JSON DTOs; JBoss Remoting and Java object serialization SHALL NOT cross this control boundary. Minecraft SHALL NOT implement Magic rules or derive legal gameplay actions.

#### Scenario: Minecraft starts with the addon
- **WHEN** the addon initializes
- **THEN** it loads no XMage runtime classes and connects only through the configured project protocol

### Requirement: Authenticated and bounded control protocol
The service SHALL authenticate Minecraft control requests, bind them to a durable world namespace, validate payload sizes and schema versions, and expose health/version information. The protocol SHALL support deck validation, idempotent match preparation, seat access, start, status/result retrieval, and cancellation. A wrong world, token, protocol version, or match binding SHALL be rejected. Non-loopback control traffic SHALL require TLS.

#### Scenario: Untrusted client calls the control API
- **WHEN** a request lacks valid world-scoped control authentication or attempts to address another world
- **THEN** it cannot create, start, cancel, inspect, or report a result for a managed match

### Requirement: Idempotent human-versus-AI creation
Match preparation SHALL create exactly one managed two-seat best-of-one match for a project-generated match ID, one authenticated human seat, and one pinned AI deck/profile. Matching retries SHALL return the existing preparation. Reusing the ID with different snapshot or seat data SHALL fail. If engine creation is ambiguous after a crash, the service SHALL reconcile or fence the record rather than blindly creating a second match. The initial service SHALL support a configurable small concurrent-match limit without clustering.

#### Scenario: Creation response is lost
- **WHEN** Minecraft retries preparation with the same match ID and identical request
- **THEN** the service returns the existing match association rather than adding another table or AI

#### Scenario: Service is at capacity
- **WHEN** a new request exceeds the configured match limit
- **THEN** the service returns a retryable capacity error without creating a partial match

### Requirement: External client seat binding and exact-deck enforcement
Managed native requests SHALL be guarded before dispatch and before any table-state
mutation; service-owned manager operations SHALL retain responsibility for deck
installation and start. The completed planning gate SHALL NOT imply guard coverage.

The service SHALL bind a project-owned seat ID to the Minecraft player and to a service-provisioned XMage account/session internally. Access material SHALL be delivered privately to that player, redacted from logs, and revoked at termination. The external client SHALL NOT replace the admitted deck, occupy another managed seat, change match options, enable cheats, or initiate an alternate managed match. The service SHALL enforce this through native-client entry points as well as the project control API. Start SHALL require authenticated seat readiness and the validated immutable deck.

#### Scenario: Player joins from the external XMage client
- **WHEN** the player authenticates using their managed seat credentials
- **THEN** the service associates that native session with only the intended seat and installs the submitted deck without accepting a replacement client deck

#### Scenario: Client attempts to substitute a deck
- **WHEN** a native XMage request submits another deck or modifies managed match settings
- **THEN** the service rejects the request and the engine deck remains the validated snapshot

#### Scenario: Another user attempts to take the human seat
- **WHEN** a user without the bound seat credentials attempts to join that managed match
- **THEN** the join is denied even if the user knows the project match ID or visible table name

#### Scenario: Bound client leaves and rejoins with a different deck
- **WHEN** a native client attempts a pregame leave/rejoin sequence on its managed table
- **THEN** the service preserves the bound seat and immutable submitted deck, or confirms cancellation through the lifecycle; the sequence SHALL NOT reopen a seat for replacement deck submission

#### Scenario: Non-owner attempts to start a managed table
- **WHEN** an unauthorized native start request addresses a prepared managed table
- **THEN** it is rejected before any table-state transition, and the table remains available for authorized service start or cancellation

### Requirement: Authoritative terminal status
The service SHALL derive outcomes from XMage's completed game/match state and persist a project-owned terminal record before exposing completion. The record SHALL include world ID, match ID, snapshot digest, stable result ID, terminal disposition, project seat outcomes, service incarnation, and monotonic revision. Completion, cancellation, interruption, and preparation failure SHALL be distinct. Minecraft-client assertions and free-text result messages SHALL NOT establish a winner.

#### Scenario: Human completes a match against AI
- **WHEN** XMage reaches the configured best-of-one match end
- **THEN** authenticated status retrieval exposes the durable outcome mapped to project seat IDs

#### Scenario: A game draws before the match has ended
- **WHEN** XMage records a drawn game and keeps the configured match active
- **THEN** the service retains active match status and SHALL NOT publish a terminal match result merely because a game completion callback ran

#### Scenario: Cancellation races with completion
- **WHEN** cancellation and natural completion occur concurrently
- **THEN** the service commits one terminal disposition and all subsequent reads or retries return that same disposition

### Requirement: Independent frontend boundary
The lifecycle protocol SHALL contain no Swing component references or UI object serialization. Gameplay traffic for this milestone SHALL remain between the existing external client and XMage. Future recipient-scoped view/decision APIs SHALL be additive and are not required by this change.

#### Scenario: Seat access is requested
- **WHEN** Minecraft requests access instructions for the authenticated owner
- **THEN** it receives project-owned connection/access data without a Swing dependency or an unrestricted serialized game view
