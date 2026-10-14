# Source provenance boundary
> 2026-10-14：当前产品已迁移到 AICEcho 新包 `io.github.umislat.aicecho`。本文中的历史包名、文件名、哈希与实机记录按当时版本保留，不代表新包已经完成真机复验。

This document records source-comparison audits. It is an engineering
record, not a legal opinion, and it does not grant permission to copy, modify,
or redistribute any material.

## Current status (2026-10-01)

Update (2026-10-09): the application now retains only original-IDm emulation.
The subsequently introduced SHA-256 compatibility generator, suffix substitution
and identity-retirement migration/UI have also been removed. The dated audit
and implementation records below remain historical; this does not rewrite Git
history or change previous licensing conclusions.

The owner chose removal rather than an alternative codec or a permission
request. Current source no longer contains `AicAccessCodeCodec.kt`, its
2304-byte substitution table, the decoding tests, or the HINATA-compatible
Amusement IC fingerprint branch. All NFC-F cards use raw Block `00`/`82`
capture; Access Code entry is manual. Existing profile/backup fields, raw
capture, and HCE-F replay are retained; legacy MIFARE direct reading is unchanged.
The candidate described below was not adopted.

This removes the identified HINATA SPAD0 material from the current source
snapshot, not from prior Git history or published APKs. The dated findings below
are retained as history, not claims that the removed files are still present.
The original AICEmu template and shared RF identifier have also been retired
from the current Android implementation. The limited image now constructs
documented fields rather than retaining the old issued metadata. See
[card-image generation](CARD_IMAGE.md) for field sources and functional limits.
This does not retroactively resolve historical permissions, prove every
contributor's rights, or introduce a root license.

## Audit scope (2026-09-01)

The comparison was performed on 2026-09-01 against these immutable revisions:

- Project HINATA / `hinata_go` commit
  [`c56d8badc3a720e0ba9e2f721f3f73111f2f6d97`](https://github.com/Project-HINATA/hinata_go/tree/c56d8badc3a720e0ba9e2f721f3f73111f2f6d97)
- `UmiSlat/AICEmu` commit
  [`5a1ecc28e98541e8226e1e92b07bd2747e607bb3`](https://github.com/UmiSlat/AICEmu/tree/5a1ecc28e98541e8226e1e92b07bd2747e607bb3)

Neither revision contains a license for the repository as a whole. License
files inside vendored dependency subdirectories do not license the surrounding
application source.

## Observed correspondence

| AimeSimulator path | Observed relationship | Current boundary |
| --- | --- | --- |
| `app/src/main/java/io/github/umislat/aimesimulator/nfc/AicAccessCodeCodec.kt` | The decoded 9 x 256-byte substitution table is byte-for-byte identical to `hinata_go/lib/utils/spad0.dart`. Both byte sequences have SHA-256 `5CD26727D9961A2CD6F70651B5BC8493A5C3CF1C5B4376BEDE302FC407F367B6`. The table keys, inverse-table construction, round count, table progression, and 15-byte rotation also correspond. | Unresolved source-material boundary. Do not describe this file as merely informed by protocol facts. |
| `app/src/test/java/io/github/umislat/aimesimulator/nfc/AicAccessCodeCodecTest.kt` | The tests identify the HINATA behavior and exercise the corresponding SPAD0 transform and AIC fingerprint. | Tied to the unresolved codec feature, although the test structure itself was not found verbatim in HINATA. |
| `app/src/main/java/io/github/umislat/aimesimulator/nfc/PhysicalCardReader.kt` | The AIC candidate values and decision predicate correspond to HINATA: IDm prefix `01 2E`, PMm `00 F1 00 00 00 01 43 00`, System Code `88B4` or an absent/zero code, service `000B`, and SPAD0 Block `00`. | Recorded as interoperability and protocol-identification facts. This audit found no large copied data table in this file, but it does not make a legal determination about individual constants. |
| `app/src/main/java/io/github/umislat/aimesimulator/nfc/CardImage.kt` | The block set and fixed values correspond to `AICEmu/app/src/main/assets/felica_template.json`, including Blocks `83`, `85`, and `88`. The template was introduced by AICEmu commit `5a1ecc28`. | Unresolved template-data boundary. Its history is separate from the later HINATA physical-card reader work. |

The Android HCE service, UI, storage, root integration, native hooks, build
scripts, and other protocol code were outside this focused comparison. Their
absence from the table is not a declaration that every line has been fully
audited for provenance.

## Protocol facts versus source expression

Identifiers needed for interoperability, such as NFC command values, service
codes, block numbers, IDm/PMm fingerprints, and System Codes, are recorded
separately from source expression. The exact 2304-byte SPAD0 table and the fixed
card-image template require additional review because the present project data
matches material in unlicensed upstream revisions, rather than only using a
small set of protocol identifiers.

Changing formatting, language, or encoding does not by itself resolve that
provenance issue. A clean-room rewrite of control flow also would not establish
the origin or redistribution status of table data that remains byte-identical.

## Required resolution before a project-wide license

Before adding a root license that could be read as covering the whole source
tree, choose and document one of these paths for each unresolved item:

1. Obtain explicit permission or a compatible license from the relevant rights
   holder and preserve the required attribution and license text.
2. Replace the material using an adequately documented public specification or
   a compatible-licensed source, keeping evidence of the independent source.
3. Remove the affected feature or data from the redistributable tree, then
   license only the remaining project-owned work.
4. Use carefully scoped file-level licensing and explicit exclusions after an
   appropriate legal review.

After those boundaries are resolved, the project can add a root license for
material its contributors are entitled to license while retaining this record
and `THIRD_PARTY_NOTICES.md` for third-party material.

## Full-tree follow-up (2026-09-25)

The earlier table remains a record of the 2026-09-01 focused audit. This
follow-up inventoried every tracked file in both current lines: 101 files at
`main` (`e905d6f`) and 104 at `codex/hcef-rootless-experiments` (`c0758bb`),
including their local uncommitted contents. The comparison used whole-file
SHA-256, whitespace-normalized text, and 16-token windows against these source
snapshots: AICEmu `5a1ecc2` and `18733bd`, AICEmu_rebuild `698a46d`, HINATA
`c56d8ba` and `c43591a`, and KonamikU `53fe140`. The source and target trees
were also reviewed manually for encoded data, converted templates, native and
Java/Kotlin control flow, Android resources, module scripts, and the diagnostic
web tool. Build output, local signing material, and private card data were not
part of the source inventory. No build or device test was performed.

| Area | Follow-up finding | Assessment |
| --- | --- | --- |
| `AicAccessCodeCodec.kt` | Independently decoded the Base64 literal and parsed HINATA's Dart integer arrays. Both are exactly 2304 bytes with the SHA-256 above. Keys, inverse lookup, round progression, and bit rotation also correspond. | Confirmed reused source data; unresolved permission. Re-encoding does not make it independent. |
| `CardImage.kt` | The same 27 template block addresses occur in AICEmu's 2023 template and KonamikU's template. After zero-padding short values, all 25 fixed blocks match the older template; only Blocks `00` and `82` are supplied by the selected profile. The two upstream JSON templates are identical at every key. | Template-data source remains to be resolved against original AICEmu. Many values are zero/default or protocol parameters; byte equality alone is not a determination of copyright protection. The Kotlin container is already reimplemented. |
| `pmm_hook.cpp`, `hal_injector.c`, `ksu-module/` | These continue the already modified AICEmu_rebuild intermediate revision. Against original AICEmu, the fixed-offset PMm scan has been replaced by bounded parameter handling; ST HAL injection, pointer-slot patching, and KernelSU integration are absent from the original tree. | Retain the existing implementation. Intermediate-project continuity is not an additional external-source issue or a reason to repeat the rewrite. |
| `FelicaCodec.kt`, `AimeHostService.kt`, `NfcFrameworkHook.java` | Original AICEmu's fixed-offset packet parsing and unconditional validation hooks have been replaced by validated request parsing, explicit error handling, and modern framework interception. | Existing implementation rewrite; retain it. Shared protocol bytes and Android registration do not justify another rewrite. |
| Launcher vectors | No current vector path string exactly matches those in the compared repos. Resemblance to AICEmu_rebuild is continuity with the already modified project, not evidence of newly copied original artwork. | No original vector-path reuse identified; do not require redrawing solely because of intermediate-project resemblance. |
| Other app code, resources, tests, and `tools/hinata-diagnostics/` | No large literal match appeared in the tracked-file scan. Manifest/service XML similarities are largely required Android declarations; the only whole-file exact match was the generated Gradle `gradlew.bat` script in KonamikU. | No additional literal reuse identified in this comparison, not a certification that reuse is absent. |

The AICEmu_rebuild snapshot and this project's first commit share the same
author and 2026-08-01 timestamp, but are distinct Git histories. That supports
the observed development lineage; it does not establish ownership of material
inherited from the 2023 AICEmu contributors. No repository-wide license was
found in the compared AICEmu, AICEmu_rebuild, or HINATA revisions. The license
files under HINATA's dependency/plugin directories apply to those components,
not automatically to `spad0.dart`. KonamikU has a GPL-3.0 repository license,
but its matching template does not independently settle the earlier AICEmu
template's provenance.

**Decision (corrected 2026-10-01):** Retain the existing protocol, hook, native,
and module implementations. AICEmu_rebuild is the project's already modified
intermediate revision, not an additional original upstream whose resemblance
requires another rewrite. The concrete original AICEmu material still requiring
source resolution is the fixed card-image template; its Kotlin container has
already been reimplemented. The HINATA table remains a separate unresolved item;
the investigation below records possible resolution paths. This review does
not add a root `LICENSE`.
Resolve those data sources before deciding the scope of a license. This is a
technical source audit, not a legal opinion or a proof of independence from
every possible source.

## Original-source reassessment (2026-10-01)

The owner clarified the development chain: original AICEmu (2023), then the
already modified AICEmu_rebuild (2026), then current AimeSimulator. Treating
similarity to that intermediate revision as a new external-source issue was
overbroad. The earlier suggestion to rewrite those implementations again is
superseded by this reassessment.

An original-only comparison of the same 104 experimental and 101 main tracked
files against AICEmu `5a1ecc2` and `18733bd` found zero whole-file exact
matches and zero whitespace-only matches. Token candidates were Android
declarations, reader API calls, vector/XML attribute scaffolding, and the
generated Gradle Windows wrapper. Those results are a screening aid, not proof
of independent authorship; the implementation conclusions below also use
manual source comparison.

| Area | Original-source comparison | Next action |
| --- | --- | --- |
| FeliCa codec and HCE service | Original fixed offsets, minimal length checking, zero-filled missing blocks, and generic unsupported-command responses were replaced by service/block validation, two-/three-byte descriptors, bounded response construction, and explicit failure handling. | Keep the completed rewrite. |
| Framework hook | Original legacy Xposed hooks accepted validator inputs unconditionally and coupled validation to library loading. Current libxposed hooks check input shape, preserve normal handling for other inputs, and separate library loading. | Keep the completed rewrite. |
| PMm patch | Original fixed-size byte scan and direct writes were replaced by bounded configuration parsing and guarded hook installation, with separate vendor packet handling. The target PMm and legacy symbol remain interoperability parameters. | Keep the completed rewrite and additions. |
| HAL injector and KernelSU module | These components do not exist in original AICEmu; they are additions in the modified development chain. | Keep them; intermediate continuity does not require rewriting. |
| Card-image container and template | Original Gson/JSON loading and per-read hex decoding were replaced by a profile-backed byte map and copy-on-read. The 27 block addresses and 25 padded fixed values still match the original template. | Verify template-data sources and meanings, especially Blocks `83`, `85`, `86`, and `88`; do not rewrite the container or remove compatible blocks solely to reduce similarity. |
| UI, storage, backup, and launcher | No substantive original literal match was identified in the reviewed implementations, and no unchanged original vector path was found. | Keep the existing work. |

The fixed compatibility IDm `02FE001145141919` preserves original compatibility
behavior and should remain attributed to that lineage. Required IDm/PMm,
System Code, service, and block constants should not be changed simply to
reduce a similarity score. This review distinguishes retained data and
interoperability behavior from already replaced implementation code.

## HINATA resolution investigation (2026-10-01)

The current upstream snapshot is HINATA `3ca80cc`. Its SPAD0 implementation
now lives at
[`lib/core/crypto/spad0.dart`](https://github.com/Project-HINATA/hinata_go/blob/3ca80ccedb971b8c9de6e2817b2ff44f9f5be8aa/lib/core/crypto/spad0.dart).
Following the file history reaches
[`fc89839`](https://github.com/Project-HINATA/hinata_go/commit/fc89839c465ae8f82fce06ec146c8aa27428b3af),
added on 2026-03-06 by akachan. That file and commit do not identify the
table's original source or grant a license. No repository-wide license or
SPAD0-specific license was found in the inspected tree and READMEs. The three
license files found are inside separate dependency/plugin directories.

The algorithm is also described in the research author's
[Amusement IC documentation](https://sega.bsnk.me/allnet/amusement_ic/).
However, its example uses placeholders for `S_BOX` and `S_BOX_INV`, not a
complete table or a table-generation procedure. It is an algorithm reference,
not by itself a replacement source for the current 2304 bytes.

One candidate with a published license is
[PN532-Aime-Reader](https://git.fragrance.moe/zhicheng233/PN532-Aime-Reader),
inspected at `8feaf84860a17f10ebd12c3827a17cc7deaa01c3`. Its README and
root LICENSE declare MPL-2.0. Parsing the `SBox` and `SBoxInv` byte literals
in `src/NfcAime.Cli/FeliCaDecryptor.cs` gives these comparisons:

| Candidate data | Comparison with current codec | SHA-256 |
| --- | --- | --- |
| 2304-byte `SBox` | Exactly equals our decoded HINATA table after per-table XOR with `tableKeys`; zero differing bytes. | `BB3E7E76E33C55FC801283191FA385EF25A0BD63CA8B09D8721176AD80186230` |
| 2304-byte `SBoxInv` | Exactly equals our constructed inverse tables; zero differing bytes. | `28E1992462FF8F4DDEA558462B776303A7873B8D1B6C771DB91E82BB374A86AD` |

These arrays entered that repository in `2dd378b` on 2026-05-17, later than
HINATA's SPAD0 addition. Its decryptor does not identify the table's original
source. Its README credits NfcAimeReader for card-reading logic, which does
not settle the SPAD0 table's origin. Matching bytes and dates do not prove
which project copied which, nor that the candidate author can license every
inherited byte. This remains a candidate, not an adopted dependency or a
resolved permission finding.

Preferred next step: ask the HINATA contributor for the table's source and
permission covering the table, transform implementation, Kotlin adaptation,
and source/APK redistribution. Alternatively, verify the candidate's data
origin and licensing scope before adopting an explicitly attributed codec.
If MPL material is adopted, preserve the applicable notices and license and
make the covered modified source available, following
[Mozilla's guidance](https://www.mozilla.org/en-US/MPL/2.0/FAQ/).
Do not relabel the existing HINATA-derived code or data as MPL solely because
another project publishes matching tables.

If neither source can be resolved, an implementation fallback is to retain
raw IDm/Block `00`/Block `82` capture and manual Access Code entry while
removing automatic SPAD0 decoding and its table. Current CardImage reads the
stored encrypted SPAD0 without calling this decoder; capture, backup, and
simulation do not inherently require local decryption. A fallback would still
need coordinated reader/UI/test/documentation changes. No such change, source
replacement, new license, build, or device test was performed in this review.

### Removal implemented after the investigation

The owner subsequently selected the fallback. Both maintained working trees
remove the decoder and table, replace the classifier/decryptor route with raw
FeliCa capture, and update the reader result screen and bilingual messages.
The editor receives raw fields without an automatically derived Access Code.
Legacy MIFARE direct reading and existing stored data remain supported.

Seven new capture tests cover unchanged raw bytes, combined-read fallback,
partially missing blocks, IDm-only capture, other IDm prefixes/system codes,
zero blocks, and unexpected combined block counts. Selected production Kotlin
sources were compiled directly with cached Kotlin 2.3.21 and the Android 36 API
jar, then exercised by JUnit: 19 tests passed on the experimental line and 18
on main, including existing profile, hex, and MIFARE codec tests. This was not
a full Gradle test/lint run or APK build. Reader string references, bilingual
XML, and both working-tree whitespace checks passed. No device test, APK
installation, root license, commit, or push was performed.

## Template and shared-identifier retirement (2026-10-01)

The owner requested retirement of the original shared compatibility identifier.
Both maintained trees now derive a stable 02FE RF identifier using a versioned,
domain-separated SHA-256 digest of the full original IDm. Original routing is
unchanged; the experiment also keeps its suffix route. Old fixed-mode selection
is cleared on upgrade, without deleting card data, and a persistent notice asks
for explicit reselection. The experimental saved-route-only flag is also reset
for that migration.

Rather than disguising the old template literals, CardImage constructs a limited
read-only view using the fields documented in Sony's Lite-S manual. Captured
SPAD0/ID blocks are kept verbatim; device metadata follows the current RF IDm,
and System Code follows the service. The old issued MC blob and key version are
not reused. Unimplemented authentication/state blocks fail reads and writes
return read-only failure instead of a success that did not change anything.
No-selected-profile requests are ignored, including obsolete persisted RF routes.
Sources, field policy, digest format and test limitations are recorded in
[CARD_IMAGE.md](CARD_IMAGE.md).

This is not a claim of complete emulation, proven cabinet compatibility,
issuer-assigned identity, a clean-room process, or a legal clearance. Historic
source/APK provenance is retained. No root license, commit, push, APK build,
installation or device test accompanies this change.

Verification compiled the current data and NFC production packages against
Android 36 with cached Kotlin 2.3.21 and AndroidX, then ran selected JVM tests:
45 on the experimental line and 38 on main passed. The generated BuildConfig
class came from earlier local build output; this was not a new APK, Gradle/lint
run, or UI compilation. Bilingual XML and whitespace checks passed. Android
upgrade migration, RF output, game-account identity and cabinet acceptance
remain unverified for the new construction.
