## Why

The project needs to prove that a Minecraft-owned physical deck can drive an authoritative Magic match without implementing Magic rules or coupling Minecraft domain code to XMage internals. A small, recoverable MTGCard → XMage → Minecraft lifecycle will expose the riskiest integration problems before investment in native gameplay UI, economy, or world generation.

## What Changes

- Add a Minecraft addon with a narrow MTGCard integration layer. An authenticated player's command selects a deckbox held by that player; the server reads its authoritative contents and verifies exclusive custody.
- Introduce project-owned card identities, canonical deck entries, immutable deck snapshots, match IDs, seat IDs, statuses, and results. Keep collectible printing identity separate from gameplay identity.
- Introduce a pinned card-mapping manifest that resolves normal, reprinted, split, multiface, and foil/nonfoil cards into a pinned XMage catalog. Reject unsupported, inconsistent, or ambiguous entries before creating a match.
- Reserve the submitted deckbox and its contents through a narrowly supported custody mechanism. Admission must fail closed if exclusive reservation cannot be enforced; a deckbox-ID-only flag is insufficient.
- Add a small versioned HTTP/JSON control protocol between Minecraft and a project-owned integration extension inside a separately running XMage service. Minecraft does not use JBoss Remoting or Java object serialization.
- Create one human-versus-AI, two-seat, best-of-one match from the exact submitted snapshot. Use the existing external XMage client, with a service-controlled seat and server-enforced deck. Bind credentials and seat access to the Minecraft player.
- Persist admission intent, reservations, match associations, terminal results, and release progress. Retrieve authoritative results by polling and process them idempotently; no valuable prizes or ownership changes arise from gameplay.
- Reconcile Minecraft restarts while XMage survives. Preserve reservations during uncertain service reachability; classify confirmed engine loss as interrupted rather than inventing a winner or promising mid-decision restoration.
- Record reproducible integration experiments, resource/latency measurements, cleanup observations, and failure-injection evidence. Unproven seat binding, native-client deck enforcement, result extraction, and reservation hooks are implementation gates.

### Goals and success

A player can submit a real MTGCard deckbox, complete a human-versus-AI game through the external XMage client, and see the authoritative result recorded once in Minecraft with the reservation correctly released. Every submitted card is mapped before match creation, unsupported cards fail clearly, duplicate requests/results are harmless, and XMage types remain confined to the service adapter. Detailed acceptance criteria and experimental gates are in `design.md` and the capability specs.

### Non-goals

Native Minecraft battlefield UI; Swing embedding; PvP matchmaking; four-player Commander; draft pods; tournament brackets; generated LGS structures; NPC personalities; historical set releases; booster economy redesign; player trading economy; auction houses; player-owned LGSs; ranking/MMR; cosmetics; polished animations; historical banlist simulation; multiple rules engines; Forge integration; valuable prizes; worker clustering; and transparent XMage process-crash restoration.

## Capabilities

### New Capabilities

- `mtgcard-deck-admission`: Server-authoritative deckbox selection, canonical extraction, exclusive reservation, and recoverable return of physical custody.
- `canonical-card-mapping`: Versioned printing/gameplay identities, complete preflight resolution, immutable snapshots, and explicit mapping errors.
- `xmage-match-service`: A separate XMage deployment with a project-owned control protocol, controlled external-client seats, exact-deck enforcement, AI participation, and authoritative outcomes.
- `durable-match-lifecycle`: Durable lifecycle records, idempotent terminal processing, reservation release, restart reconciliation, interruption handling, and spike diagnostics.

### Modified Capabilities

None. `openspec/specs/` is currently empty; this change introduces the project's first capabilities.

## Impact

The implementation introduces the initial addon, project protocol/domain models, MTGCard integration, XMage service extension, persistent lifecycle storage, configuration, tests, and a local integration runbook. MTGCard remains an external pinned dependency; no MTGCard fork is planned. A small pinned XMage extension/patch may be necessary to control managed seats and enforce decks through its existing native-client paths.

The implementation will pin one Minecraft/loader/MTGCard combination, XMage engine and client builds, a mapping manifest, and an AI fixture. Existing upstream APIs are source evidence, not verified runtime guarantees. Dependency notices and upstream licensing ambiguity must be recorded; this spike makes no claim that engine licenses grant rights to distribute Wizards content. Only planning artifacts are created by this proposal.
