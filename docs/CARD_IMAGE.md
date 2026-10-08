# Card-image generation

## Independent field construction

The current image is a limited read-only view, not a clone of an issued physical
FeliCa Lite-S card. It no longer loads or re-encodes the old AICEmu template.
Public field definitions are taken from Sony's
[FeliCa Lite-S User's Manual v1.4](https://www.sony.net/Products/felica/business/tech-support/data/fls_usmnl_1.4e.pdf),
sections 3.1.2-3.1.12 and Table 4-6:

| Blocks | Current construction |
| --- | --- |
| `00`-`0D`, `0E` | Zero scratch defaults; REG defaults to all FF. Saved SPAD0 replaces `00`. |
| `82` | Saved ID block, unchanged; otherwise original profile IDm plus zero suffix. |
| `83` | Routed RF IDm followed by configured PMm. |
| `84`, `85` | Default service number; configured System Code followed by zeros. |
| `88` | Read-only permission masks, no NDEF or authentication restrictions, RF parameter 07. |

The old issued MC blob and key-version value are not reused. Blocks
`80`, `81`, `86`, `87`, `90`, `91`, and `92` are unsupported rather
than fake challenge/key/MAC/state responses. Reads containing an unsupported block
fail; writes return `09` with status `01/A8` and do not modify data. No selected
profile means no application response, including on an obsolete saved RF route.

These are protocol-field facts and a deliberately limited application policy,
not Sony source code, copied document examples, or a license grant. The image
does not implement real-card issuance, keys, MAC, counters, or writable memory.
Existing readers may reject it if they depend on the removed issued metadata;
previous machine tests do not validate this new image.

## Stable compatibility RF identifier

The generated route is:

```text
02FE || first_6_bytes(SHA-256(domain || original_IDm_bytes))
domain = ASCII("AimeSimulator/compatibility-idm/v1") || 00
```

All eight original IDm bytes participate, including their prefix. The result is
stable across restarts, rename, export/import, and profile UUID changes. It is not
randomized per scan, inferred from an Access Code, allocated by an issuer, or
guaranteed collision-free (the suffix has only 48 bits). Changing this algorithm
would change card identity and must be treated as another migration.

The `02FE` prefix serves Android HCE-F identifier compatibility; it is not a
claim about the original card manufacturer. See Sony's
[HCE-F Guidelines v1.02](https://www.sony.net/Products/felica/business/tech-support/data/M1053_Host-Based_Card_Emulation_for_NFC-F_Guideline_1.02e.pdf).
An Android-valid identifier does not bypass System Code validation or prove
reader/account compatibility.

Original-IDm routing is unchanged. The experimental suffix route is also
unchanged. The shared legacy identifier is retired. An installation using that
route loses only its active selection and returns to original-IDm mode; profiles,
Access Codes, and raw blocks are retained. The experimental saved-route-only
option is disabled for that migration because its old provisioned identifier
does not match. A persistent UI notice asks the user to select a card and route
again. New generated routes require fresh provisioning where applicable.

The generated RF identifier can select a different game account even if SPAD0
and Block 82 are unchanged. It is a compatibility experiment, not recovery of a
lost card or a substitute for a pre-loss physical-card backup.

## Verification boundary

JVM tests cover the digest vector, input normalization, full-IDm participation,
metadata-independent identity, captured bytes, defaults, per-route device
metadata, System Code, unsupported blocks, copy-on-read, and write-failure frame.
Migration now has local Android-runtime coverage as detailed below; real-device
upgrade and foreground route behavior still need Android upgrade tests. RF,
account, and cabinet acceptance still need new reader/device tests. Do not
transfer previous fixed-IDm/template results to this construction.

### Full Debug build validation (2026-10-01)

Both current working-tree snapshots subsequently passed full Gradle
`testDebugUnitTest lintDebug assembleDebug` in isolated WSL Ubuntu 22.04.
The experimental line passed 94 JVM tests and main passed 78; neither skipped
or failed tests. Lint reported 0 errors and 6 non-blocking warnings on each line.
Complete UI/resources/DEX and arm64 native builds, module packaging, artifact
checks and Debug APK v2 signature verification passed. Toolchain versions were
Gradle 8.14.5, AGP 8.13.2, Kotlin 2.3.21, JDK 21, Android 36, Build Tools 36.1.0,
NDK 25.1.8937393 and CMake 3.22.1, without changing repository declarations.

This closes Debug compilation/lint validation, not the runtime/visual tests
listed above. No APK was installed or published; release/formal signing was not
tested. Validation artifacts retain existing version codes and use the WSL
Debug signer, so in-place installation is not assumed.

### Android-runtime regression (2026-10-01)

Robolectric 4.16.1 on API 36 now exercises the actual CardStore and
AimeHostService, not replacement storage or response helpers. Tests cover
legacy JSON import, fixed-route retirement, exact saved-data preservation,
persistent acknowledgement, empty storage, raw capture/save/reopen/response,
route and profile cache refresh, deselection/deletion, unsupported services and
blocks, and read-only writes. All fixtures are synthetic.

The first experimental run reproduced two response bugs: another NFCID2 could
receive the selected card's data, and a zero-block request returned success.
Both lines now ignore requests addressed to another routed NFCID2 and reject
zero-block read/write requests. Switching route also stops responses for the
previous identifier, without rewriting captured Block 00 or 82.

After the fixes, full testDebugUnitTest/lintDebug/assembleDebug passed:
experiment 113/113, main 96/96; zero failures, errors or skips, and lint
0 errors / 6 existing warnings on each. APK v2 signatures, module packaging
and artifact checks passed. Local Android tests require JDK 21 for API 36;
the APK bytecode target remains 17. Main CI and release runners now use JDK 21.

This verifies local application behavior, not controller/HAL routing, ROM
validation, RF, game identity or cabinet acceptance. Upgrade dialog display,
actual package upgrade and process restart, UI visual regression, and reader
tests remain pending. No device was changed, APK installed, release signed or
GitHub workflow run. Artifacts are in ignored dist/runtime-validation-20261001.
