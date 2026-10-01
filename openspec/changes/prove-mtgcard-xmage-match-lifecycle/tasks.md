## 1. Pin and probe the integration stack

- [x] 1.1 Select one buildable Minecraft loader/version and MTGCard release; pin exact artifacts, XMage server/client revision, dependencies, catalog revision, and local run configuration. Evidence: `spikes/build-stack.sh`, dependency manifests and `spikes/README.md`; source-built release-version artifacts and native server startup verified.
- [x] 1.2 Inspect and exercise MTGCard deckbox item, placed block, UUID backing record, card metadata, and mutation paths; document the supported held-box custody path and whether addon hooks can enforce exclusive escrow. Evidence: `spikes/mtgcard/live-alias.mcfunction` and `spikes/README.md`; negative custody-gate result, no held-box admission path approved. Player interactions/automation still require verification when testing a protection hook.
- [x] 1.3 Spike XMage managed-table creation with one human and one AI from code; record required session, owner, room, and plugin initialization steps and the exact extension seam. Evidence: `spikes/README.md`; prepared-table/AI-construction probe passed, authenticated play remains 1.4.
- [x] 1.4 Spike stock XMage client attachment to a precreated managed seat, including authenticated reconnect; identify every native path that can change the submitted deck or match settings. Evidence: `PROBE_NATIVE=true` probe and native-surface audit in `spikes/README.md`; stock session-library attachment/reconnect verified, leave/rejoin deck substitution and non-owner start-state mutation reproduced. Swing UI/active-game acceptance and production guards remain 3.4/3.6/6.1.
- [x] 1.5 Spike structured match completion, draw/concession handling, and durable result retrieval after controller cleanup; reject a free-text-only winner source. Evidence: `spikes/xmage/ResultProbe.java` and `spikes/README.md`; draw/concession and fresh-process history reads pass, project result binding/acknowledged journal still required in 3.5.
- [x] 1.6 Spike one known normal card, reprint, foil pair, split/multiface card, and unsupported card through MTGCard identity → XMage catalog resolution; record unmapped and ambiguous cases. Evidence: `spikes/cards/run-probe.sh` and `spikes/README.md` (ten metadata projections; eight mapped, two unsupported).
- [x] 1.7 STOP GATE: Review evidence for exclusive custody, exact-deck enforcement, and trustworthy structured result retrieval. Do not proceed to section 2 without explicit user approval. If any gate fails, update design.md, specs, and tasks before further implementation.

Progress evidence (2026-09-30): `spikes/README.md`. The user authorized the local
Minecraft EULA; live alias divergence was reproduced and the custody gate fails with
the inspected public hooks. Task 1.4 passed stock native-session attachment/reconnect
to a server-created seat, but reproduced deck replacement and unauthorized start-state
mutation. Swing UI and active-game behavior remain unverified.
Task 1.5 passed draw, concession and cross-process history reads; the native callback
and durable project result binding remain implementation work. Evidence updated 2026-10-01.

Gate review (2026-10-01): task 1.7 was checked in the user's working tree before
apply. Continue implementation with admission disabled. Required fixes remain addon
interception of loaded/backing aliases (4.2/4.5), pre-dispatch native guards (3.4/3.6),
and acknowledged native-controller result persistence (3.5); prove each before 6.1.

## 2. Define the project boundary and catalog

- [ ] 2.1 Define versioned project DTOs for card/printing identity, canonical main-deck entries, immutable snapshot, player/seat binding, match request/status/result, and structured errors without MTGCard or XMage runtime types. Evidence: `shared/src/main/java/mtgcraft/protocol/Protocol.java`; `bash scripts/check.sh`.
- [ ] 2.2 Define deterministic snapshot encoding and digest, world/match/seat/result identifiers, request idempotency rules, and service/catalog compatibility checks. Evidence: `Canonical.java`, `docs/protocol.md`; canonical grouping/order and request binding checks pass.
- [ ] 2.3 Build a pinned mapping manifest and resolver for exact printings and explicitly approved equivalent printings; report all unsupported, inconsistent, and ambiguous entries in one validation response.
- [ ] 2.4 Add resolver tests for normal cards, reprints, foil/nonfoil copies, split/multiface layouts, conflicting IDs, missing engine cards, and unchanged immutable snapshots.
- [ ] 2.5 Specify and implement the minimal authenticated, versioned HTTP/JSON control protocol with schema limits, timeouts, world scoping, structured errors, loopback default, and TLS requirement for non-loopback use.

## 3. Build and secure the XMage service extension

- [ ] 3.1 Start the pinned XMage process with the project extension, explicit config, readiness/version endpoint, persistent service incarnation, and small configurable active-match limit.
- [ ] 3.2 Implement XMage deck resolution and gameplay-format validation from the project snapshot, verifying the installed engine deck matches its approved digest and quantities.
- [ ] 3.3 Implement idempotent managed human/AI match preparation and start, with persistent project-to-XMage ID mapping and a reconciliation/fencing strategy for ambiguous creation.
- [ ] 3.4 Provision and bind native-client seat access to the project seat and Minecraft player; privately deliver credentials and reject unrelated users, native deck replacement, changed options, and unauthorized managed-table actions. Guard before table-state changes and close the reproduced leave/rejoin substitution path; cover the native surface audited in `spikes/README.md`.
- [ ] 3.5 Capture structured XMage match outcomes, map them to project seats, persist terminal records/tombstones before cleanup, and expose stable status plus cancellation semantics.
- [ ] 3.6 Test repeated/conflicting create requests, seat hijacking, deck substitution through every identified native path, cancellation/completion race, capacity limit, and result reads after cleanup/restart.

## 4. Integrate MTGCard and reserve the physical deck

- [ ] 4.1 Build a narrow MTGCard adapter for held-deckbox selection, server-side source reading, metadata extraction, section validation, source fingerprinting, and canonical conversion.
- [ ] 4.2 Implement durable admission intent, escrow journal, exclusive backing-store/alias protection, and a recheck immediately before the physical transfer; reject sources whose exclusivity cannot be enforced.
- [ ] 4.3 Implement durable one-time return claims and quarantined crash ambiguity; verify offline and full-inventory returns do not lose or duplicate the original deckbox.
- [ ] 4.4 Add the player command/minimal interaction, useful validation errors, owner-only seat access/status, and an asynchronous Minecraft-to-service client that never trusts client-provided deck or result data.
- [ ] 4.5 Test concurrent admission, placed/shared/foreign box rejection, alternate backing references, in-flight edits, and crash injection at each escrow/return boundary.

## 5. Persist and reconcile the lifecycle

- [ ] 5.1 Implement a durable world-local match journal with unique constraints for world/match/result and separate terminal and custody-return phases.
- [ ] 5.2 Commit reservation before remote creation; retry create/start/cancel with stable IDs and digests, and reconcile nonterminal records after Minecraft restart.
- [ ] 5.3 Verify authenticated terminal results against world, player/seat mapping, snapshot, service incarnation, and expected state; commit first result and release intent once, quarantine conflicts.
- [ ] 5.4 Treat network timeout as unknown and retain custody; implement confirmed-incarnation-loss/verified-fencing interruption policy with no winner or prize.
- [ ] 5.5 Test duplicate status/results, lost acknowledgements, Minecraft client disconnect, Minecraft restart while XMage survives, service unreachability, confirmed engine loss, and delayed stale messages.

## 6. Demonstrate and document the milestone

- [ ] 6.1 Run a complete human-versus-AI match using a real MTGCard deckbox and external XMage client; verify exact deck contents, authoritative result, one-time processing, and return of the original box.
- [ ] 6.2 Run an unsupported-card submission and show clear validation errors with no match or lingering reservation.
- [ ] 6.3 Run a two-match concurrency and cleanup probe; collect startup-to-ready time, RSS/heap, per-match memory growth, AI decision/turn latency, adapter round-trip latency, and cleanup observations with sample counts.
- [ ] 6.4 Write a reproducible local runbook, version/dependency/license inventory, protocol contract, diagnostic report, and observed limitations, including exact reconnect and interruption behavior.
- [ ] 6.5 Audit dependencies so MTGCard layouts stay inside the Minecraft integration package, XMage types stay inside the service extension, and no Minecraft rule, target, mana, combat, or priority implementation was introduced.
