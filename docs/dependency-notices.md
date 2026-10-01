# Pinned dependency and notice inventory

| Component | Version / immutable identity | Local notice evidence |
| --- | --- | --- |
| MTGCard | 1.7.0-26.3, commit `bab304144c9b94e9d005a85cddb77ed41f6c2385` | `LICENSE` declares MIT; `LICENSE.txt` says all rights reserved under the same copyright. Conflict unresolved. |
| Minecraft | 26.3, source-built Fabric integration | Proprietary game/runtime; local disposable test EULA was explicitly accepted. Cached common artifact has no resolved license declaration in the inventory. |
| Fabric Loader | 0.19.5 | Cached artifact/POM declarations in TSV |
| Fabric API | 0.160.5+26.3 | Module artifact/POM declarations in TSV |
| Fabric Loom | 1.17.21 override | Pinned build plugin; upstream floating snapshot is not used |
| XMage server/client | 1.4.61, commit `7f42784132e054d0c5b6c581fa63fed5893327ab` | Upstream `LICENSE.txt` declares MIT; compiled patched controller sources retain upstream notices |
| XMage sets | content 241, expansions 18; SHA-256 `2692e83f110985b00ea87ae259a78c1d3b264ee923e7cdc25ecfb94db13943ff` | Pinned XMage source notice; a code license does not establish rights to game artwork/text/trademarks |
| Gson | service compile/runtime 2.13.2; Minecraft resolved runtime 2.14.0 | Local POM declarations in TSV; actual addon tests run with Minecraft's version |
| OpenJDK | 26.0.2.1; project/Minecraft class target 25, upstream XMage target 8 | Runtime/toolchain, supplied locally; not bundled with project jars |
| Maven / Gradle | 3.9.9 / 9.5.0 | Build tools, supplied/downloaded by pinned build instructions |
| Project addon | 0.1.0 | Fabric metadata declares MIT; repository-wide grant/notice is not established by this inventory |

The mapping manifest pins printing identities, approved engine equivalents and
catalog/format compatibility. Its limited cards are integration fixtures, not a
complete card database. It includes source provenance; runtime admission does not
fetch a live catalog or perform fuzzy name matching.

`spikes/artifacts.lock.json` lists 178 observed XMage server/client/upstream artifacts
with SHA-256. `spikes/mtgcard/dependencies.lock.json` lists 128 observed Fabric-side
runtime artifacts (including classifier duplicates). [dependency-notices.tsv](dependency-notices.tsv)
retains all 306 entries and their observed hashes, declared license names and
lookup basis. Build plugins/tools are identified above separately; the runtime
manifests are not a complete build-tool software bill of materials.

Regenerate the TSV after the pinned build with
`python3 scripts/dependency-notices.py`. The script reads local resolved POM
license declarations (including resolvable parent POMs), cached Fabric metadata
and pinned XMage source notices. It uses no network or guessed license defaults.
Twenty entries currently have no resolved local declaration and are marked
`UNKNOWN`; that means this lookup did not establish their notice, not that they
have no license. The TSV is an inventory of declarations, not a distribution
authorization or a reconciliation of contradictory upstream notices.

Upstream jar timestamps can change on rebuild. Review and re-pin changed hashes;
the observed manifests do not promise bit-for-bit reproducibility. Preserve
upstream copyright/license notices when preparing any distributable package, and
resolve the recorded MTGCard notice conflict and unknown declarations before
claiming a complete release notice set. This spike creates no claim to rights in
Wizards card content or other third-party assets.
