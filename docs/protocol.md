# Control protocol v1

Java 25 project records live in `shared/`; they contain no Minecraft, NBT, XMage or
Swing types. UUIDs identify worlds, matches, seats, players, results and service
incarnations. Generate each operation ID once and persist it before retrying.
Printing identity contains optional Scryfall/Oracle UUIDs plus mandatory project key,
set, collector number, finish, layout and ordered face names. Physical source and
instance references remain private to the Minecraft integration.

## Deterministic encoding

`Canonical` hashes a binary encoding with SHA-256. Integers are signed big-endian
32-bit values. Strings are UTF-8 preceded by their byte length; null is length -1.
UUIDs use lowercase standard text, enums use their exact uppercase names.

Snapshot order: string `mtgcraft.snapshot.v1`, protocol integer, format, format
version, mapping digest, engine build, catalog digest, entry count, then entries.
Each entry is section, card fields, quantity. Card fields are project key, Scryfall
ID, Oracle ID, set, collector number, finish, layout, face count, ordered faces,
diagnostic label. Identical card/section rows are merged and quantities added;
rows sort by `section + ':' + lowercase hex of encoded card fields`. Physical
references never affect this encoding. Lists are copied and unmodifiable.

Request order: string `mtgcraft.request.v1`, protocol integer, world ID, match ID,
human seat ID/role/player ID, AI seat ID/role/null player ID, snapshot digest,
pinned AI profile. Snapshot and request digests are recomputed on construction.
JSON property and input entry order do not affect them. Foil, printing, faces,
quantity, compatibility and seat changes do affect them. A retry must reuse both
match ID and request digest; a different request for the same ID conflicts.

Compatibility is exact equality of protocol, format/policy version, immutable
mapping digest, engine commit and catalog artifact digest. A mismatch disables
creation. No live catalog fetch or name-based fallback occurs during admission.
