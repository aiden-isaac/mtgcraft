## ADDED Requirements

### Requirement: Distinct printing and gameplay identities
The project SHALL represent collectible printing identity separately from gameplay identity. Canonical entries SHALL include a project card key, available Scryfall and Oracle identifiers, set code, collector number, face identity/layout, finish, quantity, and deck section. Display names SHALL be diagnostic labels rather than authoritative fuzzy-match keys. A physical instance identifier SHALL NOT be confused with either card identity.

#### Scenario: Foil and nonfoil copies share gameplay
- **WHEN** two physical copies differ only in finish
- **THEN** they retain distinct collectible attributes while resolving to the same gameplay identity where appropriate

#### Scenario: Reprints share Oracle identity
- **WHEN** different printings represent the same gameplay card
- **THEN** their printing metadata is preserved and the mapping manifest can explicitly resolve them to a supported equivalent engine printing

### Requirement: Pinned mapping manifest and engine catalog
All resolution SHALL use an immutable mapping manifest with a content digest, source-catalog version, and compatible XMage build/catalog identity. Live Scryfall requests SHALL NOT be required during admission. Engine-specific class names and printing identifiers SHALL remain inside the service adapter; public validation responses SHALL use project-owned identities and diagnostics. Version mismatch SHALL block match creation.

#### Scenario: Service catalog differs from the admitted snapshot
- **WHEN** match creation requests a mapping or engine version different from the service's compatible pinned configuration
- **THEN** the service rejects creation with a version error and creates no match

### Requirement: Complete and deterministic preflight resolution
Every canonical entry SHALL resolve unambiguously before any engine match is created. The resolver SHALL check identity consistency and supported face layout, aggregate errors by entry, and reject unsupported cards, contradictory identity fields, and missing or multiple candidate mappings. Equivalent reprint mapping SHALL be explicitly declared in the manifest; heuristic or silent name-based substitution is forbidden. XMage SHALL perform gameplay deck validation for the pinned format.

#### Scenario: Unsupported card appears in a deck
- **WHEN** one or more submitted entries have no supported engine implementation
- **THEN** validation returns the affected entries and reasons without creating a match, crashing, or substituting another card

#### Scenario: Identity fields conflict
- **WHEN** an entry's Scryfall printing ID, Oracle ID, or set/collector information contradicts the pinned manifest
- **THEN** validation reports an identity conflict instead of selecting whichever field matches first

#### Scenario: Split or double-faced card is mapped
- **WHEN** a split or multiface physical card has a complete supported manifest mapping
- **THEN** it resolves as one gameplay card with the correct complete face relationship rather than separate face entries

#### Scenario: Deck violates the selected game format
- **WHEN** card resolution succeeds but XMage rejects the deck's format constraints
- **THEN** admission reports engine validation errors without implementing those Magic rules in Minecraft

### Requirement: Immutable and reproducible match snapshot
Minecraft SHALL persist an immutable canonical snapshot containing entries and quantities, format policy version, mapping digest, and engine compatibility identity. A documented deterministic encoding SHALL produce its snapshot digest. Match creation SHALL bind to that digest, and the service SHALL verify its actual resolved deck against the validated mapping before starting play.

#### Scenario: Deck changes after submission
- **WHEN** an inventory edit, changed catalog, or altered create request would change the validated deck
- **THEN** the existing snapshot remains unchanged and the mismatched operation is rejected

#### Scenario: Exact submitted deck is installed
- **WHEN** the service prepares an admitted human seat
- **THEN** every installed engine card and quantity corresponds to the immutable snapshot and its approved mapping, with no omitted or extra entries
