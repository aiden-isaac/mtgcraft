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

## HTTP boundary

All operations require `Authorization: Bearer <world service token>`,
`X-Mtgcraft-World: <world UUID>` and `X-Mtgcraft-Version: 1`, including health.
Tokens contain 32–256 ASCII letters, digits, underscores or hyphens. Generate a
random token; keep it on the server and deliver native seat credentials privately.
Bodies use `Content-Type: application/json`. Every record field is required; use
explicit null for optional UUID/result fields. Unknown/duplicate fields, coercion,
malformed UTF-8, fractional integers and noncanonical UUIDs are rejected.

| Method/path | Request | Response |
| --- | --- | --- |
| GET `/v1/health` | empty | Health/compatibility |
| POST `/v1/decks/validate` | ValidationRequest | Validation with all entry errors |
| PUT `/v1/matches/{UUID}` | MatchRequest | Status |
| GET `/v1/matches/{UUID}` | empty | Status, including durable terminal Result |
| POST `/v1/matches/{UUID}/start` | Operation/world/request digest | Status |
| POST `/v1/matches/{UUID}/cancel` | Operation/world/request digest | Status |
| POST `/v1/matches/{UUID}/seat-access` | OwnerRequest/world/player | SeatAccess |

Seat access must verify the persisted human owner; no public result-write endpoint
exists. Path, body and authenticated world/match IDs must agree. The service owns
request digest conflict checks and terminal durability. Successful calls return 200.
Errors return a versioned Failure with code, diagnostic message, retryable flag and
entry indexes. Boundary codes include UNAUTHORIZED (401), WRONG_WORLD (403),
BAD_SCHEMA (400), PAYLOAD_TOO_LARGE (413), CONTENT_TYPE (415), VERSION_MISMATCH (426),
NOT_FOUND (404), METHOD_NOT_ALLOWED (405), CAPACITY/SERVICE_FAILURE (503).

Requests/responses are capped at 256 KiB, JSON depth at 16, object fields at 32,
arrays at 1,024 elements, text at 256 characters, canonical deck entries at 512 and
total deck quantity at 1,000. The server admits at most 16 concurrent control
requests. JDK transport deadlines are 10 seconds; client deadlines are configurable
up to 30 seconds. Client responses are bounded while streaming. Redirects are
disabled. A timeout or SERVICE_FAILURE is an unknown outcome: retain custody,
reconcile and retry with the original IDs/digests. Never infer a terminal result.

The default deployment binds loopback. Non-loopback HTTP server binding requires an
SSLContext; non-loopback client URLs require HTTPS with normal certificate and
hostname verification. No credentials or private deck bodies appear in routine
logs; seat-access responses are `no-store` and their Java string form is redacted.
Run `bash scripts/check.sh` for a local socket test of the boundary.

## Durable acceptance and retries

Minecraft persists reservation and operation intent before the corresponding remote
operation. Create retries reuse the complete MatchRequest; start/cancel reuse its
request digest. An authenticated NOT_FOUND may recreate only a locally unacknowledged
request with the same immutable identity. A missing previously acknowledged match
is unknown and stays in custody; neither 404 nor changed health authorizes release.

Status/result world, match, digest and ordered seats must match the immutable request.
Incarnation remains bound to the first acknowledged status. ACTIVE/COMPLETED require
a durable local start authorization; CANCELLED requires a pending cancellation.
Revision increases monotonically. Exact terminal replay is idempotent; lower-revision
nonterminal responses are ignored. Conflicting terminal IDs/bindings/revisions or
incarnations quarantine without replacing the accepted result. Result IDs are unique
across local matches. A late conflict after acknowledged delivery is recorded
without reversing the RETURNED tombstone or touching a later match using that
physical backing. First terminal/result acceptance and RETURN_PENDING commit in
one atomic world-journal write; item delivery has its own acknowledged tombstone.

The service's exclusive persistent-state lock establishes restart fencing. It retains
old match incarnations in interrupted terminal records with NONE seat outcomes.
The addon accepts that authenticated bound history, rather than synthesizing a
result from health or transport failure. Polling uses bounded 4/8/16/30-second failure
backoff and at most sixteen asynchronous in-flight requests; no network work blocks
the Minecraft tick thread. There is no public result-write or quarantine-override API.
