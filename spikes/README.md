# Integration experiments

These are disposable upstream probes, not the Minecraft addon or a working match service.
Do not admit player cards using this code.

## Stack under test

- MTGCard `bab304144c9b94e9d005a85cddb77ed41f6c2385`, declared version `1.7.0-26.3`.
- Minecraft 26.3 / Fabric Loader 0.19.5 / Fabric API 0.160.5+26.3.
- XMage server and client `7f42784132e054d0c5b6c581fa63fed5893327ab`, version 1.4.61.
- OpenJDK 26.0.2.1; Maven 3.9.9; upstream Gradle wrapper 9.5.0.
- XMage card-content version 241, expansion-content version 18. The commit and
  Mage.Sets artifact must also be pinned: these counters alone do not identify a catalog.

## Task 1.1 — buildable pinned stack: PASS (2026-09-30)

`bash spikes/build-stack.sh` fetches the exact commits, checks Maven's archive SHA-512,
and builds the Fabric artifact plus XMage server/client. It overrides upstream's floating
Loom snapshot with **1.17.21**. Both builds passed on the JDK above. The Minecraft target
is Java 25; XMage compiles its upstream Java 8 target. This selects the source-built
MTGCard release-version artifact, not a verified publisher-hosted release download.

`artifacts.lock.json` records SHA-256 for 178 observed Minecraft/XMage build artifacts
and resolved XMage server/client dependencies. `mtgcard/dependencies.lock.json` records
resolved Fabric runtime dependency coordinates and hashes. Upstream generated Maven
jars contain build timestamps, so rebuilding may change hashes; review and re-pin
artifacts rather than treating these files as a reproducible-build guarantee. These are
observed dependency manifests, not Gradle/Maven's automatic dependency-lock mechanism.
The pinned catalog is the `mage-sets` jar hash plus the upstream commit and DB counters.

`bash spikes/xmage/run-server.sh` starts the actual native server in a separate JVM,
with a 2 GiB heap cap and disposable state under `.cache/xmage-server`. Its checked-in
config binds `127.0.0.1:17171` and `127.0.0.1:17179`, enables authentication, caps game
threads/AI opponents at two, and enables only the duel, Freeform, human and MAD plugins.
Test mode is explicitly false. The stock native server reached readiness and both
loopback ports were reachable. This does not prove authenticated seat attachment;
managed account provisioning and the project HTTP adapter are not implemented.
Stop with Ctrl-C. Minecraft subsequently generated `eula=false` during the live-server
probe; the EULA has not been accepted and no active Minecraft world was started.

Initial server/client source build: 4 min 5 sec on this workstation (dependency caches
partly warm). Warm native startup reached the logged listening state on 2026-09-30.
No AI latency, match memory-growth, concurrency or cleanup performance claim is made.
The server emits reflective-access warnings on JDK 26; client gameplay compatibility
still needs testing before this runtime is used beyond the spike.

## Task 1.3 — table creation: PASS (2026-09-30)

`xmage/XmageProbe.java` compiled and executed against actual upstream jars with assertions
enabled. `bash spikes/xmage/run-probe.sh` reruns it after building the pinned checkout
at `.cache/upstream/mage` with Maven 3.9.9 in `.cache/tools/apache-maven-3.9.9`.
Build command: `mvn -B -ntp -pl Mage.Server,Mage.Client -am install -DskipTests`.
The upstream build passed; its test suites were explicitly skipped.

Observed output:

```text
catalog_ready_ms=13944
PASS table: registered human + MAD AI; exact 60-card decks; native submitDeck rejected in READY_TO_START
NOT PROVEN: authenticated client attachment, gameplay, comprehensive deck enforcement, durable result binding
PASS cleanup: prepared table controller removed
```

The probe initializes `RepositoryUtil` and `CardScanner`, registers `TwoPlayerMatch`,
`TwoPlayerDuelType`, `Freeform`, `HumanPlayer`, and `ComputerPlayerControllableProxy`
in the upstream factories, then constructs `MainManagerFactory(ConfigWrapper)`.
It creates an **in-memory, unauthenticated** `User`, obtains the main room ID, and calls
`TableManager.createTable(room, user, options)` followed by `joinTable` for each seat.
Both joins require an existing user ID, including the AI join. Both decks contain exactly
60 M10/230 Plains; the table reaches `READY_TO_START`. Removing the table removes its
controller. This proves prepared-table construction and AI instantiation, not AI play.
No network listener, credentials, native client, or GUI participates.

Candidate service seam: a bootstrap beside `MainManagerFactory`, with guards in
`TableManagerImpl`/`TableController`. The stock `ExtensionPackage` only exposes card sets,
deck types, and draft cubes, so it is not a managed-match plugin API. A narrow XMage
patch or custom server bootstrap is needed; runtime client binding remains task 1.4.

## Task 1.2 — custody: BLOCKED / unproven

- MTGCard `:fabric:build` passed. `mtgcard/MtgcardProbe.java` also passed metadata
  round-trip and removal-callback tests against the real upstream runtime. It uses a
  synthetic card on a vanilla paper item with explicitly bound empty components;
  this is **not** a real deckbox/world test. Task 1.2 remains open.
- Starting `:fabric:runServer --args nogui` reached Minecraft's EULA check and stopped.
  Its generated `fabric/run/eula.txt` is still false. Live deckbox tests require the
  user's explicit EULA acceptance. Gradle's successful exit does **not** mean the
  Minecraft server became ready.
- `DeckboxBlock.createDeckboxDrop` retains `mtgcard_deckbox_id`; cloning with data
  can retain the same ID. `DeckboxBlockEntity.loadStorageId` copies stored items into
  its own inventory; a missing record can be recreated. `setChanged` persists it.
- Opening a block menu, container access, deck-control actions, and
  `SulfurCubeDeckboxCards` are separate access paths. Storage save/load have no
  reservation policy. The removal callback is notification-only, as verified above.
- **Decision needed before custody implementation:** keep MTGCard as a dependency,
  but prototype narrow addon Mixins or an upstream access-policy hook. Both existing
  loaded aliases and future record reads/writes need protection. Locking only the
  file or intercepting removal cannot establish exclusive escrow. No patch/fork
  or claim of complete mutation-path coverage has been made.

## Task 1.4 — native attachment: PARTIAL

With `run-server.sh` running, `bash spikes/xmage/run-client-probe.sh` passed against
the real loopback native transport with authentication enabled and test mode off.
It creates a random, temporary account directly in the disposable server's authorized
user database (no registration email), authenticates using stock `SessionImpl`, prepares
a human/AI table, disconnects while preserving the session, then restores the old
session with a second `SessionImpl`. The authenticated account remains the table owner
and sees both seats. The table and temporary account are removed afterward. Passwords
exist only in process memory and are not printed.

This exercises the stock client **session library**, not the Swing UI, a managed
server-created seat, an active game, or a Minecraft binding. Task 1.4 remains open.
Native entry points requiring a complete guard audit include `roomJoinTable`,
`deckSubmit`, `deckSave`, `roomLeaveTableOrTournament`, `tableSwapSeats`, `tableRemove`,
and `matchStart`; public table/option mutation and reconnect paths also need review.
The READY_TO_START `submitDeck` rejection from task 1.3 alone is not deck enforcement.

## Task 1.5 — result capture: PARTIAL

These two commands passed, in separate JVMs:

```sh
PROBE_RESULT=true bash spikes/xmage/run-probe.sh
PROBE_HISTORY_ONLY=true bash spikes/xmage/run-probe.sh
```

The first starts a real `GameWorker` with one human and one AI, waits for the isolated
worker to wait for a response, and invokes XMage's concession API. Its callback ignores
the result string and asserts structured `MatchPlayer` wins and winner state. It
captures `TableProto` **before** `closeTable`, closes/removes the controller, and reads
the exact snapshot back through `TableRecordRepository`. The second reads the same
record after the entire probe process has exited. The callback is a test callback,
not the native `GameController` path; no full game was played through a client UI.

Capture timing matters: `closeTable` clears the validator, so a later `table.toProto()`
reports `<deck type missing>`. `TableRecorderImpl` records before that cleanup.
Its protobuf contains names, wins, draws and quit reasons, but no table/match/player
UUID fields, and `TableRecordRepository.add` logs SQL errors without acknowledging
failure to its caller. A project result journal and stable seat binding remain needed.

`PROBE_RESULT=true PROBE_DRAW=true bash spikes/xmage/run-probe.sh` is a deliberately
unfinished draw experiment. Direct off-worker draw injection produced a win; the
current worker-listener attempt timed out. Neither is evidence of an XMage rules bug.
Use an upstream draw-producing card test or a correctly initialized worker fixture next.
Source inspection indicates wins-needed-one can continue after a drawn game; do not
equate every ended game with an ended match. Task 1.5 remains unchecked until draw
handling and the real managed completion hook have been exercised.

## Task 1.6 — metadata/catalog mapping: PASS (2026-09-30)

Run `bash spikes/cards/run-probe.sh`. The checked-in fixture contains identity fields
retrieved from the five documented Scryfall API URLs on 2026-09-30, with no card art.
The probe writes each record through real MTGCard `CardNBTUtil.populateCardNbt`, reads
back its stored metadata, exports ten projections (nonfoil/foil), and resolves them in
a separate XMage JVM using exact set and collector number. Both processes assert
identity/face preservation. XMage constructs the card implementations and loads each
supported entry as one deck card.

| Printing | Result |
| --- | --- |
| M10/146 Lightning Bolt | `mage.cards.l.LightningBolt` |
| M11/149 Lightning Bolt | Same implementation and Oracle ID; different Scryfall printing ID |
| APC/128 Fire // Ice | One `FireIce` object, both halves verified |
| ISD/51 Delver of Secrets | One transformable `DelverOfSecrets`, reverse face verified |
| UNH/30 Cheatyface | Missing from pinned XMage catalog; rejected without substitution |

Foil/nonfoil projections share printing/gameplay identity and have distinct physical
instance IDs. None of these exact tuples was ambiguous. Scryfall's combined DFC name
differs from XMage's front-face name, which is why a name-only lookup is insufficient.
XMage does not resolve the Scryfall/Oracle IDs here: the pinned fixture binds them to
the exact engine tuple. This is a fixture experiment, **not** a general trusted resolver
or a live deckbox extraction test. Conflicting/untrusted metadata handling remains 2.3–2.4.

## Graphify

Local `graphify-out/graph.json`, `graph.html`, and `GRAPH_REPORT.md` index 18 selected
upstream Java files (790 nodes, 2,395 edges). AST extraction used zero LLM tokens;
this is not a measurement of total session token savings. Query, for example:
`graphify query 'DeckboxStorage' --budget 800`.

The graph health check found 187 dangling endpoint edges and 196 collapsed directed
edges. A query also inferred a cross-repository `.error()` call incorrectly. Use this
graph to locate source, not as evidence of Java call resolution or security guarantees.
The generated graph, copied source corpus, tools, and builds are local ignored artifacts.

## Stop gate

Section 2 remains blocked by task 1.7's explicit approval gate. Completing an experiment
does not mean the full integration milestone is complete. Failed custody/result/client
experiments must be documented and the design amended before implementation expands.
