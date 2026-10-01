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
incarnation, admission readiness and configured capacity. Current managed orchestration
is still under implementation: `ready=false`. Preparation/status/start are implemented;
seat access and cancellation fail NOT_READY until their native policy/result hooks land.
Native XMage listening alone is not admission readiness. Keep physical admission off.

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
and authoritative terminal persistence are verified.
