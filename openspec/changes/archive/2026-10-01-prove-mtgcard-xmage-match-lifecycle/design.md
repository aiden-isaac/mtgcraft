## Context

This repository has no application implementation or main OpenSpec capabilities yet. The change introduces the first integration slice. MTGCard supplies Minecraft card items, metadata, rendering, and UUID-backed deckbox storage. XMage has a server, human/AI player implementations, table and game controllers, a native client protocol, and player-specific game views. Those source features do not yet prove that managed tables can enforce a Minecraft-submitted deck, bind an external client securely, or yield a durable result. Each is an experimental gate.

The target topology is Minecraft client ↔ Minecraft server/addon ↔ project control protocol ↔ project extension in a separate XMage service. The existing XMage client connects directly to that XMage service for gameplay. Only the Minecraft server may submit custody state, create managed matches, or accept terminal results. The XMage service owns actual Magic decisions and AI; Minecraft does not run an engine JVM or store engine game objects.

Source anchors from the exploratory revision: MTGCard `api/DeckboxStorage`, `deckbox/DeckboxBlockEntity`, `content/pack/cache/CardNBTUtil`; XMage `Mage.Common/.../MageServer`, `Mage.Server/.../TableController`, `Mage.Server/.../game/GameController`, `GameManagerImpl`, `GameSessionPlayer`, and `Mage.Server.Plugins/Mage.Player.Human/.../HumanPlayer`. The exact seam for the service extension must be verified at runtime before general implementation.

## Goals / Non-Goals

**Goals:** Prove exact physical deck extraction, complete mapping, exclusive custody, service-side human-versus-AI creation, existing-client play, authoritative terminal result, exactly-once Minecraft processing, safe release, and restart reconciliation. Capture measurable performance and failures. Preserve a narrow path to future recipient-specific views and native decisions.

**Non-Goals:** The future systems listed in `proposal.md`; especially native battlefield UI, historical products, trading, tournaments, other engines, and arbitrary mid-decision engine restoration. Engine play does not transfer persistent card ownership.

## Decisions

### 1. Pin one addon and engine stack

Implementation first pins a single compatible Minecraft version, loader, MTGCard release, XMage server/client build, mapping manifest, and AI fixture. Record hashes in a deployment manifest. Choose the loader from an actually buildable MTGCard release during the first task; the proposal does not assume legacy Minecraft Forge support. MTGCard remains an external dependency, with a narrow integration layer as the only reader of its storage and metadata. If custody cannot be enforced through supported hooks, document the missing hook and make a minimal compatible integration patch a separate, reviewable decision. Admission remains disabled until the guarantee is demonstrated.

### 2. Use project DTOs across the boundary

`CardIdentity` contains printing fields (Scryfall ID if present, set, collector number, finish), Oracle ID if present, face/layout data, a display label, and an optional physical instance reference used only on the Minecraft side. `DeckSnapshot` contains main-deck entries with quantities, format identifier/version, mapping digest, compatible engine build, and a deterministic content digest. Its internal persisted form also references the admission owner and reservation. These are project-owned records; no NBT tags, XMage `DeckCardInfo`, object IDs, sessions, or Java serialized types enter the domain model.

Resolve exact printing identifiers against a pinned manifest first. If the engine lacks that printing, an explicitly declared equivalent printing for the same Oracle identity may be used. Foil is an item finish, not a gameplay variant. Split and multiface cards map as one complete gameplay object. Reject conflicting Scryfall/Oracle/set fields, ambiguous faces, unmapped printings, and missing XMage implementations. Do not use fuzzy names as an admission fallback. `Mage/src/main/java/mage/cards/decks/DeckCardInfo.java` uses name, set, collector number, and quantity; `Deck.java` resolves set and collector number through `CardRepository`. The service adapter translates to that representation after preflight and asks XMage to validate the configured game format. The first fixture needs only a supported main deck; populated auxiliary slots fail clearly.

### 3. Use custodial escrow for the initial reservation

The command accepts a deckbox in the player's server-side hand only. Minecraft reads the source storage, builds a snapshot, checks an observed source revision/fingerprint again before commitment, and creates a durable admission intent. On the server thread, it transfers the original deckbox into project-controlled custody and prevents other references from accessing its backing record. The exact MTGCard item/backing-store behavior and any alias hooks are a gate. The reservation record binds world ID, player UUID, physical source reference, match ID, snapshot digest, and transfer phase. A boolean on a deckbox UUID alone would leave the original item or duplicate references usable.

Inventory persistence, our durable store, and MTGCard's files are not assumed to commit atomically. Use an operation journal, unique escrow ID, reconciliation of source and escrow state after restart, and a quarantine state for ambiguous transfers. Never issue a replacement copy solely because a timeout occurred. Return is a durable claim for the same original box, usable while the owner is offline or their inventory is full. The spike must show that all supported ways to open, move, extract, place, or clone the backing contents are blocked while reserved; otherwise admission fails closed. This is a narrowly supported custody path, not a general collection ledger or anti-cheat claim for all modded items.

### 4. Put control integration beside XMage, outside Minecraft

Use a project-owned extension in the XMage service process. It can call XMage's table/session APIs locally and own the mapping from project match/seat IDs to XMage table/game/user IDs. It presents a small versioned HTTP/JSON control API to Minecraft. Candidate operations are `GET /v1/health`, `POST /v1/decks/validate`, `PUT /v1/matches/{projectMatchId}` to prepare idempotently, `POST /v1/matches/{id}/start`, `GET /v1/matches/{id}`, `POST /v1/matches/{id}/cancel`, and a seat-access operation restricted to its Minecraft owner. The exact endpoint shapes may be refined by the first protocol spike; these are the minimum semantics. No broad `getAvailableActions()` or `serializeMatch()` abstraction is promised.

Minecraft-side `MagicEngine` is a small port with `validateDeck`, `prepareMatch`, `startMatch`, `getStatus`, `cancelMatch`, and owner-scoped `getSeatAccess`. Capability/version information comes from health. `reattachSeat` is not added unless the external-client reconnection experiment shows an operation is needed beyond native XMage login. A future native frontend can add seat-scoped views and decision responses through a separate project protocol without changing deck admission or result ownership.

Control requests use a service credential scoped to one persistent Minecraft world ID. Listen on loopback by default; require TLS for non-loopback traffic. Credentials are not exposed to Minecraft clients or routine logs. Require strict schema/version checks, bounded payloads, request timeouts, and structured error codes. The external XMage client continues to use XMage's native protocol; Minecraft never exposes JBoss Remoting or Java serialized objects as its control API.

### 5. Bind a managed native-client seat and enforce its deck

The service prepares one human seat and one pinned AI seat in a best-of-one match. It provisions a distinct native XMage account/session or another verified authenticated handle for the human, maps it internally to a project seat ID and Minecraft UUID, and privately gives access instructions to that Minecraft player. A table name or knowledge of the project match ID is not authorization. Starting the match waits for validated deck installation and authenticated seat readiness. A managed table rejects native client attempts to replace the deck or settings and denies unrelated users. This may require a narrow XMage extension/patch at `roomJoinTable`, `deckSubmit`, table ownership, or equivalent entry points. The spike must demonstrate the enforcement path before declaring acceptance. If stock client behavior prevents it, keep the change in experimental state rather than pretending credentials alone enforce the deck.

Only the XMage service may derive the winner. It listens to authoritative match completion, maps XMage IDs back to project seat IDs, and persists a terminal record before cleanup. Parse structured match state/outcome where available; a free-text game result is insufficient to identify a trusted winner. The same record is returned by status polling after reconnect/restart. A match creates no prizes or collection changes.

### 6. Persist the lifecycle and handle uncertain failures conservatively

Use a small durable world-local journal/database for match and reservation records, with write-ahead or atomic-file semantics. The implementation may choose SQLite or equivalent based on the repo's initial build; durability, unique IDs, and transaction boundaries matter more than a particular library. Project match IDs are UUIDs generated once by Minecraft; project seat IDs identify the bound human/AI seats. A world UUID prevents cross-world confusion. The service stores a persistent incarnation UUID per process start and match records/tombstones across restart. `PUT` with the same project ID/request digest returns the same match; a different digest conflicts. If service crash occurs during XMage creation, inspect persistent managed-table markers or fence the request; never create a second table blindly.

Lifecycle states are `ADMISSION_PENDING → RESERVED → PREPARED → ACTIVE → COMPLETED | CANCELLED | INTERRUPTED | FAILED`, followed by an independent `RETURN_PENDING → RETURNED | QUARANTINED` custody state. Only authenticated service terminal data can establish `COMPLETED`. Minecraft commits a validated terminal result and return intent once, keyed by project match ID plus result ID. Repeated identical records are harmless; conflicting records are quarantined. Cancellation is a request and does not free cards until the service confirms a terminal state or the old engine incarnation is verified fenced.

Temporary service unreachability leaves status unknown and the deck reserved. On Minecraft restart, query every nonterminal match by the original project ID. If XMage survived, resume observation. If a new authenticated engine incarnation proves the former match process is gone, record `INTERRUPTED`, with no winner or prizes, then schedule return. An operator can fence a definitively dead old service; old credentials/process must be revoked or stopped before release. Client disconnect changes neither engine ownership nor reservation. No attempt is made to restore a killed engine's suspended Java thread or pending prompt.

### 7. Measure the boundary before expansion

Record startup-to-ready time, process RSS and JVM heap, per-match memory delta, AI decision/turn durations, adapter latency, cleanup after terminal state, and behavior on client disconnect, Minecraft restart, and engine loss. Include build hashes, sample counts, workload/deck fixture, machine characteristics, and observed failure modes. Use a small configurable concurrent-match limit and bounded background requests; do not block the Minecraft tick thread. Redact access credentials and private deck contents from routine logs.

## Data and control flow

1. Authenticated player invokes command with a held MTGCard deckbox. Minecraft reads server-side contents through the MTGCard integration layer.
2. Convert all entries to canonical project card identities; resolve against the pinned mapping manifest and ask the service to validate the exact deck against XMage. Return all unsupported/ambiguous entries together.
3. Recheck source fingerprint, persist admission intent, escrow the box, then persist reservation and immutable snapshot. Crash ambiguity enters quarantine.
4. Submit a versioned, authenticated, idempotent prepare request using the durable project match ID and snapshot digest. Service creates a managed human/AI table and internal mappings. Minecraft persists acknowledgement.
5. Give the seat owner private native-client access data. The service authenticates that client, locks its managed deck, and starts when ready. XMage runs all gameplay.
6. Service persists authoritative terminal status. Minecraft polls, verifies world/match/seat/snapshot/service binding, commits result and return intent once, then makes the original deckbox claimable and cleans up active engine resources.

## Risks / Trade-offs

- **[Native-client deck replacement remains possible]** → Gate success on a demonstrated server-side rejection, including alternative `deckSubmit` and reconnect paths. Make a minimal pinned XMage patch if needed.
- **[MTGCard has alternate deckbox references or storage mutation paths]** → Inspect item/block/record semantics and test all supported paths; escrow and gate admission until exclusivity is demonstrated.
- **[Non-atomic storage across Minecraft and MTGCard]** → Journal transfers, quarantine ambiguity, use one-time return claims and crash-injection tests.
- **[Card printing absent from XMage]** → Maintain explicit equivalent-print mappings only where Oracle identity and face behavior match; reject the rest.
- **[A timeout hides a live match]** → Preserve reservation and reconcile by stable match ID; terminal confirmation or fencing is required before release.
- **[XMage result APIs expose text or game-level rather than match-level status]** → Spike structured match outcome and best-of-one handling before defining the terminal adapter.
- **[AI is slow or weak with fixture decks]** → Use a modest supported fixture, measure turn latency, and cap match concurrency. Broader NPC quality is outside this change.
- **[Separate process still has old native transport exposure]** → Restrict network reachability, use managed accounts, and scope the new control API. Future native UI must receive recipient-specific views.
- **[Upstream changes or licensing/IP assumptions]** → Pin versions and notices, record the unresolved MTGCard second license notice, and review Wizards/Minecraft terms before any public distribution of card content or branded builds.

## Migration Plan

There is no existing project data to migrate. Implement the project DTOs and protocol first, then the gated service extension and MTGCard admission. Start the pinned XMage service independently with an explicit config for world ID, bound address, credentials, version identities, storage path, AI fixture, and concurrency limit. The addon checks compatibility at startup and disables new admissions if the service is unavailable or mismatched; existing reservations remain reconciliable. Stop new admissions before upgrading either side. Rollback keeps durable lifecycle and escrow records accessible, reconciles active matches, and returns cards only after terminal confirmation or verified fencing.

## Open Questions and required experiments

### Evidence from initial implementation probes (2026-09-30)

### Gate review and implementation path (2026-10-01)

Task 1.7 was marked complete in the user working tree before applying this change.
Proceed with the project boundary and service implementation; this authorizes work,
not a claim that the failed technical gates passed. Admission stays disabled until
an addon interception experiment protects loaded inventories, future aliases and
backing-record access. Use addon Mixins rather than silently changing MTGCard.
Guard the native XMage transport before dispatch, while service-owned manager calls
install decks and start matches. Prove coverage against the audited native surface.
Capture completion through the native controller path into an acknowledged project
journal before cleanup; stock history remains diagnostic evidence only. If these
seams cannot satisfy the guarantees, pause for an artifact change. Acceptance remains
the original full human-versus-AI lifecycle, including physical return.

See `spikes/README.md` and the runnable probes. Fabric/Minecraft 26.3 and the pinned
XMage server/client build successfully. Programmatic table construction and fixture
card mapping passed. MTGCard's removal callback cannot veto access; exclusive custody
will require an addon interception experiment or an upstream policy hook, including
already loaded backing-record aliases. A live operator-created alias fixture confirmed
that a clone could empty the shared record while the original retained its cached card.
Command destruction also yielded a deckbox without backing-ID data; ordinary player
breaking remains unverified. No held-item custody path is currently approved. Keep
admission disabled until a protection hook and ordinary item lifecycle are tested.

XMage's `ExtensionPackage` is a card/deck/cube extension API, not a server lifecycle
plugin. Use a narrow bootstrap/server patch around the managers; do not assume a
drop-in lifecycle plugin exists. A bootstrap in `mage.server` reused the pinned
package-private native transport without editing upstream. Create through
`GamesRoom.createTable` to populate the lobby as well as the table manager.
The stock session library in a separate JVM authenticated against a precreated,
service-owned table and retained its human player UUID on reconnect. Swing UI and
active-game reconnect remain unverified. A service-owned table alone does not enforce
the deck: native leave/rejoin replaced the installed Plains deck with Islands.
Non-owner `matchStart` also changed the table to STARTING before ownership validation.
Managed policy must run before any transition, and cover creation, join/rejoin,
leave, deck submission/save, start, seat swaps and removal (audit in `spikes/README.md`).
Keep service ownership distinct from human seat access; credentials alone are insufficient.

Structured draw/concession state and upstream SQLite history survive controller cleanup
and a probe JVM restart. Capture before `Table.closeTable` clears its validator.
`result.proto` has no stable table/match/player UUIDs, and repository writes swallow
SQL exceptions, so upstream history alone cannot satisfy project result identity and
durable acknowledgement. The service must snapshot its stable bindings alongside the
structured outcome and persist with an acknowledged commit before release/cleanup.
The engine-driven zero-start-life fixture drew the first game and continued the match;
concession in the next game produced an AI match win. This is a worker fixture, not
the native controller callback. An ended game must not automatically terminate a
wins-needed-one match. No full Minecraft-to-XMage-to-Minecraft lifecycle has passed.

1. Which XMage API/extension seam can create a managed table programmatically without a normal lobby owner, and can it persist a unique project marker before any ambiguous failure?
2. How does the stock external client authenticate and attach to the precreated human seat, and which native deck submission paths must be blocked to enforce the snapshot?
3. Which structured XMage match result survives controller cleanup and can be read again after service restart? How are conceding, draws, errors, and cancellation represented?
4. Which MTGCard item, placed-block, dispenser, storage-record, and other access paths can reach a reserved deckbox backing record? Can addon hooks enforce escrow without a fork?
5. Can every card in the chosen fixture, including a reprint and a split or multiface card, map deterministically to XMage's pinned catalog? What format checks run in XMage?
6. What does native-client reconnect do when disconnected during a pending choice? What are timeout and abandoned-table cleanup semantics?
7. What memory and latency do two concurrent human/AI matches produce? What process-start and shutdown behavior does the pinned build exhibit?

Failure to establish exact deck enforcement, trusted outcome retrieval, or exclusive custody is a stop condition for claiming this milestone complete; the design must be amended with evidence rather than weakening acceptance silently.

### Ordinary held-source compatibility (approved 2026-10-01)

The real pinned Fabric server reproduced a missing UUID on the ordinary survival
loot path: the block retained `DeckboxId`, but its existing loot box lacked
`mtgcard_deckbox_id`. The user explicitly approved a scoped addon compatibility fix.
`LootIdentity` preserves the existing block UUID and tint on existing deckbox loot;
it creates no second drop or replacement record and copies no cached card contents.
The pinned upstream source/artifact remains untouched.

`bash scripts/check-custody.sh` now passes actual server-side survival block destruction:
one dropped box retains its original UUID/tint and forty physical cards. It also
passes placed-alias rejection, durable intent/removal/reservation, backing read/write
guards, cached read/removal/insertion guards, world lookup and item/dispenser placement
rejection, and journal reload. The world lookup guard covers the pinned store/database/
control/menu/automation callers; admission closes retained MTGCard menus before the
final recheck. Storage recheck/protection reuse the upstream storage mutex.

Production admission remains disabled until return/crash tests and the full lifecycle
are demonstrated. The fixture transfers the actual server-created item entity into
a server-side player hand; networked player input and external-client acceptance
remain section 6. The disposable probe mod is excluded from the production addon jar.

### Completed interactive lifecycle acceptance (2026-10-01)

The earlier probe limitations above describe the evidence at those stages. All
implementation gates and task 6.1 now have evidence: the user completed the runbook
with a real Minecraft client, forty Card Store-printed M10/146 Lightning Bolts, and
the pinned external Swing client, confirming the deck, concession, status, original
return and repeated claims. Read-only journal checks confirm matching immutable
requests and COMPLETED results in both applications, HUMAN LOSS / AI WIN, RETURNED
custody, no conflict and cleared terminal access. See `docs/diagnostics.md` for the
observation limits and Java 21 desktop-client workaround; the addon/service retain
Java 26. Admission remains an explicit opt-in and defaults off in shipped config.

This establishes the scoped integration milestone. The small catalog, manual
UUID/native-login workflow and external client remain in place. Automatic seat
connection, card-image presentation and a Minecraft gameplay frontend require
future changes; the separate-engine and recipient-scoped view/decision boundaries
remain the intended extension path. Swing embedding is still outside this change.
