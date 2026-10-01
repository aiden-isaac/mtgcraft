# Local MTGCard/XMage runbook

This milestone is an experimental server addon and a separate managed XMage
service. Admission defaults off. Automated custody, native protocol and lifecycle
checks pass; task 6.1 still requires interactive Minecraft/stock Swing acceptance.
See [diagnostics.md](diagnostics.md) for measured results and limits.

## Build and check

Use the pinned environment: OpenJDK 26.0.2.1, Maven 3.9.9 and Gradle 9.5.0.
Minecraft/project classes target Java 25; XMage's upstream target is Java 8.
The scripts use cached source builds under `.cache/` and the existing Maven/Gradle
repositories. From the repository root:

```bash
bash spikes/build-stack.sh
bash scripts/check.sh managed
bash scripts/check-addon.sh
bash scripts/check-boundaries.sh
bash scripts/check-custody.sh
bash scripts/check-custody-crashes.sh
python3 tests/lifecycle.py
python3 scripts/dependency-notices.py
```

The custody scripts reuse only the already-approved disposable local Minecraft
EULA. They bind loopback, choose an ephemeral Minecraft port and use fixture
players in offline mode. Do not copy that offline configuration to a public server.
Each crash case abruptly halts a JVM, then recovers in another; a nonzero injected
process exit is expected. The runner must also find its matching PASS evidence.

Build outputs are `.cache/project/mtgcraft-shared.jar`,
`.cache/project/mtgcraft-service.jar` and
`addon/build/libs/mtgcraft-addon-0.1.0.jar`. The addon bundles project protocol
records, not the engine. Probe classes and their admission bypass stay out of the
production addon. Rebuilt upstream jars can contain new timestamps: the manifests
record observed artifact hashes, not a bit-for-bit reproducible-build guarantee.

## Configure local applications

1. Run a Fabric 26.3 server with Loader 0.19.5, Fabric API 0.160.5+26.3,
   MTGCard 1.7.0-26.3 and the addon jar. Install the same pinned MTGCard stack in
   the Minecraft client. For a development server, the pinned wrapper can run
   `bash .cache/upstream/mtgcard/gradlew -p addon runServer --no-daemon` from this
   repository; its default world/config live in `addon/run/`.
2. Start Minecraft without a service config first. Join locally and use
   `/mtgcraft world` to obtain its durable world UUID. Stop the server for setup.
   Retain the world's `mtgcraft/` directory and player/backing files together.
3. Generate a random token into a private file; for example, use Python's
   `secrets.token_urlsafe(32)` and mode 0600. Never put the token in chat, a
   screenshot or a command-line argument. Both applications use the same file.
4. Copy `service/config.properties.example` to an ignored private local file.
   Set `worldId`, `tokenFile`, `mappingFile` and `nativeConfig`; paths resolve
   relative to that properties file. Use the checked-in mapping and
   `spikes/xmage/config.xml`. Control binds `127.0.0.1:17180`; native ports are
   17171 and 17179. The native service requires authentication, test mode off,
   and a dedicated managed-only deployment. Stop other servers on those ports.
5. Copy `addon/config.properties.example` to Minecraft
   `config/mtgcraft.properties`. Set `endpoint` and `tokenFile`; keep
   `admissionEnabled=false` until interactive acceptance is established.
6. Start the service with
   `bash scripts/run-service.sh <absolute-properties-path> <persistent-state-directory>`.
   The heap cap is 2 GiB. Retain that state directory across restarts. Start
   Minecraft again. The addon pins compatibility from the manifest at build time;
   service health and deck validation must match it exactly before admission.

The service owns an exclusive state-directory lock until its process exits, after
engine workers stop. Never run a second service with the same credential in another
state directory, copy live state, or delete history to fix a match. Match capacity
is two by default (range 1–16). In the pinned native config, game threads and AI
opponents are also capped at two. A larger control limit does not increase those
native limits.

Non-loopback control URLs require HTTPS and normal certificate/hostname checks.
The provided executable bootstrap and native deployment are loopback configurations;
remote native exposure and certificate provisioning are not part of this spike.

## Interactive acceptance still required — task 6.1

The completed headless tests use the stock native session library in external JVMs.
They do not establish a real player's interaction with the Minecraft and Swing UIs.
Use a disposable world with the configuration above and enable admission there
when conducting this acceptance run. Record a sanitized observation; keep credentials
and private deck contents out of public logs.

1. Prepare at least forty main-deck cards using supported printings from
   `service/catalog/manifest.json`. The manifest is deliberately small; it is not
   a general catalog. Its M10/230 Plains row asserts no Scryfall/Oracle UUID and
   supports the null-ID fixture specifically. Printing identity mismatches fail
   closed; do not relabel unsupported cards to bypass them.
2. Load the physical MTGCard deckbox, remove it from the world and hold its one
   UUID-backed item. The approved scoped loot hook preserves the backing UUID
   and tint on the existing ordinary drop. Loaded placed aliases, embedded/legacy
   containers, auxiliary slots and unavailable/malformed backing records are
   rejected. Only the pinned MTGCard access paths and addon guards are supported.
3. Use `/mtgcraft submit`. The server reads and validates its own immutable
   snapshot, rechecks the physical fingerprint, acknowledges escrow in the
   journal/player data, then creates the remote match. Keep the returned match UUID.
4. Use `/mtgcraft access <match UUID>` for private host/port/username/password.
   Start the pinned stock Swing client on a separate JVM, log in with those
   credentials and attach to its precreated human seat. Its main class is
   `mage.client.MageFrame`; use the built client jar plus
   `.cache/xmage-client-classpath.txt`, with a working directory outside the repo's
   tracked files. Confirm the exact submitted deck and unchanged settings in the UI.
5. Use `/mtgcraft start <match UUID>` after native authentication. Play against
   the pinned MAD AI (sixty Plains). A legal native concession can end this
   best-of-one fixture; its purpose is integration, not an AI-quality assessment.
6. Use `/mtgcraft status <match UUID>` to see the authoritative terminal state,
   then `/mtgcraft claim <match UUID>`. Verify the original UUID/tint/contents
   returned once. Repeat status and claim; verify the result ID/record and item
   count stay unchanged. The diagnostics describe the corresponding headless proof.

Status, access, start, cancel and claim accept only an owner-scoped match UUID.
The client supplies neither deck metadata nor a winner. No prize or ownership
change occurs. To abandon a match, use `/mtgcraft cancel <match UUID>` and wait for
its verified terminal response before claiming. Native table creation/join/leave,
deck submit/save, swaps/removal, start and cheats are denied before mutation.
Gameplay dispatch requires the bound human/current game; rollback is disabled.

## Recovery and interruption

The world journal records immutable requests, operation intents, acknowledged
service status and physical delivery phases separately. Reservation is durable
before remote creation; create/start/cancel retry the same IDs and digest.
Authenticated terminal acceptance atomically records the first result and return
intent. Exact repeats have no domain effect. Lower-revision nonterminal messages
are ignored; conflicting bindings, incarnations or terminal records quarantine
custody without overwriting the first accepted result.

A Minecraft player disconnect does not discard the association or release escrow.
Completion while offline leaves `RETURN_PENDING`; a full inventory also stays
pending. Claims require the owner and an available slot, acknowledge player data,
then persist a delivery tombstone before unlocking the original backing record.
Crashes during transfer or delivery can be ambiguous: `INTENT`/`CLAIMING` recover
as `QUARANTINED`, with no automatic replacement or override. Keep those journals,
player data and physical backing files for an operator audit.

Minecraft restart loads the same world/match/seat/snapshot bindings and asynchronously
queries surviving service history. At most sixteen background requests are in flight.
Failures back off 4, 8, 16, then 30 seconds; owner commands can retry immediately.
Timeouts, connection loss, a missing previously acknowledged match and changed
health incarnation alone retain custody. No deadline invents a winner.

On service restart, its exclusive process lock establishes that old workers can no
longer run. Before accepting control requests it persists `INTERRUPTED` for old
nonterminal records, with the match's original incarnation and all seat outcomes
`NONE`. Only the authenticated, bound terminal history schedules the physical
return. Lost/replaced history requires investigation; no automatic replay,
mid-decision restoration, winner or prize is supplied. A conflicting late result
cannot reverse the interruption.

The native reconnect fixture disconnects while preserving the session, restores
the old session ID and verifies the same human UUID/deck. Actual pending-choice
reconnect timing is unmeasured. Pinned `UserManagerImpl` checks activity every
30 seconds, marks connection problems after 30 seconds, expires sessions after
three minutes and removes users after eight minutes. Those are source constants,
not a measured grace period or promise of indefinite reconnect. An expired user
can cause controller cleanup; the guarded service hook records interruption when
there is no authoritative completed match outcome.

## Evidence, patches and notices

`tests/lifecycle.py` uses independent Minecraft/service/native-client JVMs, two
simultaneous real games, a Minecraft hard halt, offline concession, six seconds
of service unreachability, exclusive-process restart and one-time original-box
claims. It also exercises unsupported real-card submission without custody.
Private `.cache/lifecycle-*` directories contain credentials and raw diagnostics:
do not publish them. Every owned process stops on success/failure.

`service/patches/lifecycle.patch` adds three acknowledged callbacks to pinned
TableController/TableManagerImpl cleanup. Builds copy/patch those sources under
`.cache/project/patched/` with zero fuzz and place the extension jar first on the
service classpath. Upstream source is unchanged. Structured winner flags and bound
player IDs are journaled before cleanup; polling never derives a result from text
or advances the engine. Failed persistence prevents cleanup. Cancellation and
completion share one service lock; the first committed terminal result wins.

Versions, catalog identity, hashes and license declarations are documented in
[dependency-notices.md](dependency-notices.md), [dependency-notices.tsv](dependency-notices.tsv),
[protocol.md](protocol.md), [boundaries.md](boundaries.md) and [diagnostics.md](diagnostics.md). MTGCard's contradictory
license files and unresolved dependency declarations are recorded rather than
silently resolved. Keep the change experimental until interactive acceptance passes.
