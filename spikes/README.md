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

## Other experiments in progress

- MTGCard `:fabric:build` passed. `mtgcard/MtgcardProbe.java` also passed metadata
  round-trip and removal-callback tests against the real upstream runtime. It uses a
  synthetic card on a vanilla paper item with explicitly bound empty components;
  this is **not** a real deckbox/world test. Task 1.2 remains open.
- XMage `TableRecorderImpl` writes structured protobuf match history to SQLite.
  `result.proto` contains names, wins, draws and quit reasons, but no table, match or
  player UUID fields. Existing history cannot be assumed to provide durable project
  result identity. Task 1.5 remains open.
- No reconnect, exclusive-custody or complete-match test has passed yet.

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
