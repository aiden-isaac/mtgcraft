## ADDED Requirements

### Requirement: Server-authoritative deckbox selection
The addon SHALL admit a deck only from an MTGCard deckbox held by the authenticated Minecraft player and read through a project-owned MTGCard integration layer. Possession in the server-side inventory is the custody criterion for this milestone; the system SHALL NOT claim to establish historical provenance. Client-supplied card metadata, ownership claims, decklists, and arbitrary storage identifiers MUST NOT determine the submitted deck.

#### Scenario: Player submits a held deckbox
- **WHEN** an authenticated player invokes the admission command while holding an eligible MTGCard deckbox
- **THEN** the server reads that deckbox and its authoritative contents and associates admission with that player's server-known UUID

#### Scenario: Invalid source is requested
- **WHEN** a client requests another player's deckbox, a placed/shared container, or a non-deckbox item
- **THEN** admission fails with an actionable error without creating an engine match or changing custody

### Requirement: Canonical extraction through a bounded integration layer
The integration layer SHALL convert physical card entries into project-owned card identities, quantities, and deck sections while retaining source references privately for custody enforcement. Raw MTGCard NBT layouts and classes MUST NOT become the match-domain representation. Initial admission SHALL support a two-player best-of-one deck with a main deck only; populated commander/sideboard sections, custom cards without mappings, and ambiguous container layouts SHALL fail explicitly.

#### Scenario: Supported deck is extracted
- **WHEN** an eligible main-deck-only deckbox is read
- **THEN** canonical entries preserve every submitted card and quantity while MTGCard storage details remain in the integration layer

#### Scenario: Unsupported deck structure is present
- **WHEN** deckbox contents cannot be assigned unambiguously to the supported main-deck format
- **THEN** the server explains the unsupported section or layout instead of silently ignoring contents

### Requirement: Exclusive reservation of physical custody
Admission SHALL remain disabled until addon interception protects both loaded
inventories and future backing-record/alias access on the pinned release. The
completed planning gate SHALL NOT be treated as successful custody verification.

Before asking the engine to create a match, Minecraft SHALL durably reserve the deckbox and all submitted physical cards and enforce exclusive custody. The initial mechanism SHALL escrow the held deckbox and protect its authoritative backing contents and aliases. A boolean flag keyed only by deckbox ID is insufficient. Extraction, mutation, alternate access, and resubmission of reserved cards MUST be prevented across the documented supported MTGCard paths. Sources for which this cannot be enforced SHALL be rejected.

#### Scenario: Second admission overlaps an active reservation
- **WHEN** a player attempts to submit the same reserved deckbox or its reserved contents for another match
- **THEN** admission is refused and the existing match and reservation remain unchanged

#### Scenario: Backing storage has an accessible alias
- **WHEN** an alternate deckbox reference or open container could access the proposed reservation's backing contents
- **THEN** the integration layer blocks that access or rejects admission before engine creation

#### Scenario: Loaded aliases diverge from the backing record
- **WHEN** a loaded deckbox retains a mutable in-memory inventory for the same backing UUID as the submitted item
- **THEN** admission requires protection of that loaded inventory as well as record reads and writes; protecting the file or removal callback alone SHALL NOT establish exclusivity

#### Scenario: Item loses its backing identity
- **WHEN** an item has no trustworthy backing identity or its relationship to stored contents cannot be established
- **THEN** admission fails without guessing a record, reconstructing custody from a display name, or creating an engine match

#### Scenario: Contents change during asynchronous validation
- **WHEN** the source revision or fingerprint changes between extraction and reservation
- **THEN** the server rejects the stale admission and requires fresh extraction and validation

### Requirement: Recoverable custody transfer and return
Escrow and return SHALL have durable operation identifiers and recovery records. Because Minecraft inventory persistence and addon storage are not assumed to share a transaction, ambiguous crash recovery SHALL quarantine the affected reservation for reconciliation rather than mint another copy. On terminal processing the system SHALL release match exclusivity and make the original deckbox available to its owner exactly once through a durable return claim. Full inventory or player absence SHALL NOT drop, destroy, or duplicate it.

#### Scenario: Owner is offline when a match ends
- **WHEN** terminal processing releases a reservation while its owner is offline
- **THEN** the original deckbox remains in durable return custody until the owner can claim it

#### Scenario: Crash interrupts a physical transfer
- **WHEN** restart finds an escrow or return operation whose physical completion cannot be established
- **THEN** the affected deckbox is unavailable for new admission until reconciled and no additional copy is issued automatically

### Requirement: Gameplay never changes collection ownership
Engine card objects, tokens, copies, control changes, zone changes, and match outcomes SHALL NOT mutate Minecraft card ownership. This milestone SHALL perform no prize, currency, or economy transactions.

#### Scenario: A match contains temporary card creation and control changes
- **WHEN** XMage creates tokens, copies spells, steals permanents, or reanimates an opponent's card
- **THEN** the reservation and eventual returned physical deck remain the original submitted collection items
