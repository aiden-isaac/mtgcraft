# Local service runbook

Build the pinned upstream stack with `bash spikes/build-stack.sh`, then run project
checks with `bash scripts/check.sh`. Project shared/service jars are separate under
`.cache/project/`; only the service build receives the XMage classpath.

Copy `service/config.properties.example` to a private local file. Use a persistent
world UUID and a random token file with mode 0600. Paths in properties resolve
relative to that file. Default control/native bindings are loopback; native
authentication is required and test mode is disabled. Match capacity defaults to
two (allowed range 1–16).

Start with `bash scripts/run-service.sh <absolute-properties-path> [state-directory]`.
The default state is `.cache/match-service`. Retain this directory across restarts.
The process owns an exclusive file lock and atomically persists a new incarnation
UUID, with file and parent-directory fsync, before serving requests. Never copy the
live state or run a second service with the same credential in another directory.
The lock stays held until process exit, after engine workers stop.

HTTP health authenticates the world/token and reports pinned compatibility,
incarnation, service readiness and configured capacity. Preparation/status/start,
owner-scoped seat access and cancellation are implemented. Service readiness is
independent of Minecraft custody verification. Keep physical admission off until
the custody experiment passes.

`python3 tests/service-smoke.py` starts and terminates two real pinned XMage JVMs,
checks authenticated HTTP health/native listening and persisted incarnation changes.
It uses disposable state and private randomly generated credentials, with no card
custody. Stop other local native servers on ports 17171/17179 first.

The deployment still uses the dependency/license inventory and measured limitations
in `spikes/README.md`, `spikes/artifacts.lock.json` and the Fabric dependency manifest.
Full lifecycle acceptance and resource measurements remain pending.

`bash scripts/check.sh engine` additionally exercises real XMage deck construction,
Freeform validation and exact installed quantities/face relationships. The manifest
is deliberately limited to the probed printings plus M10/230 Plains; other cards
fail closed. It supports normal, split and transform layouts. These are integration
fixtures, not an AI deck-quality or complete catalog claim.

`bash scripts/check.sh managed` additionally exercises durable human/AI preparation,
identical/conflicting retries, capacity, seat readiness and crash-intent fencing.
The sole pinned AI profile is `mad-plains-v1` (60 M10/230 Plains). This isolates
orchestration from AI quality. Creation commits intent and table association before
seat installation; an ambiguous retry fences the marker and returns a stable failed
tombstone. Restart acquires the exclusive process lock and records nonterminal old
matches as interrupted, with no winner. A world stamp prevents state-directory reuse
by another world. Health stays unavailable for physical admission until native policy
and authoritative terminal persistence are verified; Minecraft admission still needs
its separate custody gate.

The native deployment is dedicated to managed matches: registration/reset/admin
login, client table/tournament creation/join/leave, deck submit/save, native start,
seat swaps/removal and cheats are rejected before engine mutation. Authenticated
managed accounts reconnect to their existing seat; they cannot create an alternative
table. Gameplay dispatch checks the bound human and current game; rollback is off.
The native session test uses a separate stock session-library JVM and verifies the
original human UUID and 60 Plains survive all tested mutations. It is not Swing UI
or active-game reconnect acceptance. Seat access checks the persisted Minecraft
owner and uses private control responses, never a public table name as authorization.

`service/patches/lifecycle.patch` inserts three acknowledged cleanup callbacks into
the pinned TableController/TableManagerImpl sources. The build copies and patches
those two files under `.cache/project/patched/`, compiles them into the service jar
and places that jar before the upstream server jar on the classpath. The upstream
checkout is unchanged. Review this patch together with the upstream commit; do not
run the extension against another XMage revision. The installed Mage.Sets SHA-256
must match the mapping manifest before readiness.

The native controller callback records structured match-winner flags and bound
human/AI UUIDs before controller/deck cleanup. A game draw which continues the
match stays active. Polling reads the acknowledged journal; it does not derive a
winner from a message or advance engine state. Failed persistence prevents cleanup.
Cancellation aborts outstanding engine prompts, commits one terminal record, then
cleans up. Completion/cancellation share the service lock and first terminal commit
wins. Account access is revoked and persisted terminal records discard passwords.
The native fixture waits for an actual choice prompt before using XMage's concede
action, then stays attached until the service acknowledges terminal persistence and
cleanup. An immediate quit/disconnect fixture did not establish native completion.

The managed check also races active native concession against control cancellation,
tests deck/start guards in six table phases, and reads the exact result in a fresh
JVM whose classpath contains no XMage runtime. These checks passed on 2026-10-01.
The second native client showed roughly 18 seconds of callback delay and an upstream
closed-connection warning; its phase deadline is 45 seconds. This is fixture evidence,
not a measured reconnect/turn-latency SLA. Swing UI and physical custody acceptance
remain unverified.

# Current physical integration gate

Build the pinned addon with `bash scripts/check-addon.sh`. Run its real disposable
server experiment with `bash scripts/check-custody.sh`; it reuses only the previously
approved local test EULA and never connects players to a public server.

The addon includes the explicitly approved normal-loot compatibility fix: it preserves
the existing block UUID/tint on its existing drop, without another box or record.
The live probe passes survival destruction, exact contents, placed-source rejection,
durable physical transfer, storage/cache/world-access/placement guards and journal
reload. Its probe mod and admission bypass are excluded from the production addon jar.
Return claims pass offline/full-inventory retention, owner checks, repeated/reloaded
one-time delivery, original-box re-placement and ambiguous-delivery quarantine.
The return keeps original item components plus a project custody marker; no card
ownership or prize changes occur. Production admission defaults off until complete interactive acceptance. The crash
matrix and authenticated lifecycle checks now pass.
No background test server remains running after a successful probe.

Copy `addon/config.properties.example` to Minecraft `config/mtgcraft.properties`.
Set its endpoint and a private token file (mode 0600), shared with the service.
Use `/mtgcraft world` to get the durable world UUID for the service configuration.
The compiled addon pins the mapping/catalog/engine compatibility; changing the
manifest requires rebuilding it. Keep `admissionEnabled=false` until acceptance.

Players hold one eligible box and use `/mtgcraft submit`. The server validates its
own snapshot asynchronously and rechecks the source before transfer. Use
`/mtgcraft access <match UUID>` for private native credentials, attach in XMage,
then `/mtgcraft start <match UUID>`. Status/cancel use the same owner-scoped ID.
`/mtgcraft claim <match UUID>` delivers only a durable return-ready claim.
Verified terminal acceptance atomically records the first result and a return intent;
physical delivery remains a separate acknowledged, one-time owner claim. The live command probe uses an authenticated
HTTP engine fixture and captured private player messages, without an engine game.

`bash scripts/check-custody-crashes.sh` abruptly halts the test JVM at twelve
admission/return boundaries, then starts a fresh server for each recovery. It verifies
player data, unchanged physical card UID/count, durable phase/tombstone and no
replacement issuance. Ambiguous intents/deliveries remain quarantined and protected;
the addon provides no automatic quarantine override. Retain their journal and backing
files for an operator audit. The regular live probe also blocks hopper, sleeve and
sulfur-cube fallback access, including embedded container data.

Lifecycle reconciliation polls reserved matches asynchronously with at most sixteen
requests in flight. Failed requests back off (4, 8, 16, then 30 seconds); owner commands
can retry immediately. Timeouts, connection failures, unknown matches after an
acknowledged creation, and changed health incarnation alone retain custody. Only
an authenticated, correctly bound terminal record schedules return. In this pinned
deployment the service's exclusive state-directory lock proves the former process
has exited; restart persists `INTERRUPTED` under the match's original incarnation,
with all seat outcomes `NONE`, before serving it. Losing or replacing that history
requires operator investigation, never an automatic box replacement or winner.

`python3 tests/lifecycle.py` runs three independent Minecraft JVMs, a real pinned
XMage service and two external stock-session JVMs. It reserves two physical
forty-Plains boxes before creation, starts both games, abruptly stops Minecraft,
reattaches tracking while both games survive, concedes through the native protocol,
and claims the original first box once after offline completion. It then keeps
Minecraft alive through service unreachability, restarts the exclusively locked
service and verifies old-incarnation interruption/NONE outcomes before returning
the second original box. The private disposable directory under `.cache/lifecycle-*`
contains logs, acknowledged statuses and raw memory/latency samples. It contains
private test credentials: do not publish the directory. All owned processes stop
on success or failure. This is a headless native-session experiment; it does not
verify Swing interaction or a networked Minecraft player's full experience.

The real lifecycle script also submits an unsupported MTGCard Cheatyface box through
`/mtgcraft submit` to the actual service. The private player response is
`No approved engine implementation — Cheatyface`; the original source fingerprint
and held item stay unchanged, with no reservation or additional engine match.
