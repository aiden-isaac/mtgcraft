# Dependency boundary audit — 2026-10-01

| Production area | Reviewed responsibilities | Runtime boundary |
| --- | --- | --- |
| `shared/mtgcraft/protocol` | Immutable identities/snapshots/seats/status/results, canonical digests, strict JSON and asynchronous HTTP client | JDK/Gson only; no Minecraft, MTGCard, Fabric, XMage or Swing types |
| `addon/src/main/java/mtgcraft/minecraft/mtgcard` | Authoritative MTGCard/NBT extraction, physical custody/journal/claim, private commands, async reconciliation, pinned access/loot Mixins | Minecraft/Fabric/MTGCard and project DTOs; no XMage classes |
| `service/src` | Pinned engine catalog/deck installation, managed human/AI tables, account/native request guards, structured cleanup hooks and persistent results | XMage/service runtime; no Minecraft/MTGCard/Fabric types |

`bash scripts/check-boundaries.sh` checks imports/references, pinned Fabric
runtime dependency coordinates and both built shared/addon jars. It rejects engine
classes or probe classes in those jars, verifies the server-only addon identity
and checks protocol bytecode for game-runtime dependencies. Run it after
`bash scripts/check.sh` and `bash scripts/check-addon.sh`.

Manual review traced submission from authoritative held-box extraction through
remote validation, durable physical reservation and managed preparation, then
from native structured completion through atomic terminal/return persistence and
acknowledged original-item delivery. MTGCard storage layouts, component names,
NBT slot/metadata decoding and UUID loot adaptation occur only in its Minecraft
integration package and Mixins. Project DTOs contain identity/quantity/bindings,
not physical stacks or engine game objects.

Minecraft code implements no Magic rule, target selection, mana payment, combat,
priority or AI decision logic. Its mutations concern inventory/backing protection
and physical delivery. The service registers the pinned upstream format/game/player
plugins and invokes existing engine operations; the lifecycle patch adds acknowledged
result/cleanup hooks, not alternate gameplay rules. Native gameplay travels directly
between the external client and XMage, with account/game dispatch guards.

The probe source set can use the project ControlServer fixture, and engine/native
checks necessarily compile against XMage. Those test dependencies/classes are excluded
from the production addon jar. Gameplay callback automation and timing samples live
only in test JVMs. The shared-only check and fresh history-reader JVM passed without
XMage runtime classes, and the pinned addon build/adapter check passed.

Interactive Minecraft/Swing acceptance remains task 6.1. This dependency audit
establishes packaging and source separation; it does not substitute for that UI test.
