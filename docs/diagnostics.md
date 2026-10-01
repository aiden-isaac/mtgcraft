# Lifecycle diagnostic report — 2026-10-01

Pinned identities and dependency hashes are in `spikes/artifacts.lock.json`,
`spikes/mtgcard/dependencies.lock.json` and `service/catalog/manifest.json`.
Tests used OpenJDK 26.0.2.1 on Linux 7.2.2-arch1-1, an Intel i7-12650HX
(20 logical CPUs), 15,704 MiB RAM and a 2 GiB XMage heap cap. Other desktop
workloads and swap were present; these are local integration observations.

## Method and workload

Two `python3 tests/lifecycle.py` runs each started two simultaneous human/AI games:
forty M10/230 Plains per physical human box and sixty Plains for `mad-plains-v1`.
Three independent Minecraft JVMs reserved, reattached and returned those boxes.
A stock native-session JVM authenticated/reconnected each human, joined the real
game and conceded the first game; process restart fenced the second. The second
run additionally exercised unsupported-card rejection through the actual command.

A separate `bash scripts/check.sh managed` run played through two AI turns in each
of two real games, then conceded/raced cancellation. It samples turn changes from
native game views while the fixture keeps the hand and passes human priority.
The mulligan decision microbenchmark calls the pinned AI's existing policy on a
copy with seven Plains, ten times per game. It neither writes new rules nor mutates
the live player's hand. Repeated decisions on one trivial hand are warm samples,
not a benchmark of search-heavy decks or combat.

## Startup and control latency

The service's own timer covers catalog initialization, native transport and control
readiness after the process lock/config/token setup. First startup in each disposable
state measured 17,502 and 16,850 ms (n=2). Restart using that state's database measured
7,457 and 6,988 ms (n=2). From launching `run-service.sh` to authenticated ready health,
including its build, the four samples were 21,778, 11,915, 21,066 and 11,300 ms.

Successful authenticated loopback control round trips include preparation/replay,
seat access, start, status and health; failures/readiness attempts are excluded.

- Control round trips: n=26, min/median/max 2.050/5.223/253.796 ms.
- Observed AI turn transitions: n=4, min/median/max 129.786/264.558/1185.728 ms.
- AI mulligan policy calls: n=20, min/median/max 0.014/0.023/0.189 ms.

Turn values include callback transport, game progression and the fixture's human
pass responses during the AI turn. They do not isolate AI CPU time. The policy
samples measure only the existing mulligan call. No broader latency SLA is claimed.

## Memory and cleanup

`/proc/<pid>/status` supplies RSS; `jcmd <pid> GC.heap_info` supplies used heap.
Each cell below has one sample at that phase in each of the two runs, in MiB.
No GC is forced. Sampling and unrelated allocation affect deltas.

| Phase | RSS run 1 / run 2 | Used heap run 1 / run 2 |
| --- | ---: | ---: |
| ready | 1845.89 / 1841.82 | 765.50 / 894.00 |
| prepared-1 | 1848.30 / 1848.73 | 780.00 / 908.50 |
| prepared-2 | 1848.61 / 1848.86 | 785.00 / 913.50 |
| active-two | 1862.25 / 1866.30 | 818.00 / 396.50 |
| one-cleaned | 1863.89 / 1868.00 | 825.50 / 404.02 |

First prepared-match RSS growth was 2.41 / 6.91 MiB; second-match growth was
0.31 / 0.14 MiB (n=2 for each position). Both active games together added
16.36 / 24.49 MiB over readiness. These shared-process deltas do not establish a
constant per-match cost. Run 2's active-game heap fell after ordinary GC.

Native completion revoked account access and removed the active controller before
the managed fixture acknowledged cleanup. Terminal journals/tombstones remained
readable in fresh JVMs. The one-cleaned RSS/heap samples did not fall; resource
cleanup does not promise immediate GC or RSS reclamation. Service restart stopped
all old engine workers under the exclusive state-directory lock. No test servers
or native clients remained running after these checks.

## Failure and acceptance evidence

- Identical/conflicting requests, guarded native deck/table mutations in six phases,
  authenticated reconnect, actual structured concession, cancellation race and
  post-cleanup result reads pass.
- Lost create/start/cancel acknowledgements recover with the same IDs/digests.
  Older active/prepared messages cannot reverse a terminal result. Conflicting
  world, snapshot, seats, incarnation, revision or terminal data quarantine custody.
- All twelve abrupt admission/return crash boundaries recovered in fresh Minecraft
  JVMs after terminal and return intent were made atomic. Ambiguous physical
  transfer/delivery stays quarantined; no automatic replacement is issued.
- Minecraft hard restart preserved two active real games and their original
  snapshots. Completion while the owner was offline produced a pending return.
  Original box UUID/tint and forty-card UID/count survived one-time claims/reloads.
- Six seconds of service unreachability while Minecraft survived did not release
  custody. Authenticated restart history interrupted the remaining old-incarnation
  match once, with NONE outcomes; no winner or prize was inferred.
- Reusing a returned box in a new match preserves both histories. A late conflict
  flags the old delivered record without undoing delivery or removing the current
  backing guard; duplicate old claims and journal reloads do not duplicate the box.
- Unsupported Cheatyface produced a named validation error without physical
  transfer, a match or a lingering reservation.

Interactive acceptance remains pending: the external clients above use stock
session/transport classes in separate JVMs, without Swing windows. Minecraft
players are server-side fixture entities, with disconnect represented by removing
the online player. A real person using Minecraft and the stock Swing game UI is
still needed for task 6.1. Long active-game disconnect/reattach timing and session
expiry were not measured; the three-minute native expiry is source evidence only.

Raw logs, heap output, statuses and credentials are kept in ignored private
`.cache/lifecycle-*` fixture directories. The checked-in report includes aggregate
measurements and public fixture names only; do not publish those private directories.
