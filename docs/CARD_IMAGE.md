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
| `83` | Original profile IDm followed by configured PMm. |
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

## Original RF identity only (2026-10-09)

The application now always uses the selected profile's original IDm. The generated
02FE algorithm, suffix substitution, route enum/selector and retirement migration
have been removed. This development version has no alternate-identity support
contract. Obsolete preferences are ignored; no migration clears the selection or
rewrites raw captures, Access Codes or the emulation method.

Registration, provisioning, activation and packet matching all use that original
IDm. CardImage has no RF-identity override. It preserves captured Block 82 verbatim
even if its bytes differ from the profile IDm; missing Block 82 uses the original
IDm, and Block 83 always uses it. A stale alternate system registration cannot
receive the selected original card's block responses.

The internal 4000/02FE placeholder remains available for public-API initialization
before original-IDm/88B4 provisioning; it is not a user-selectable emulation mode.
Reader/account compatibility still requires validation. An original card whose
actual IDm starts with 02FE is not rejected or transformed.

## Verification boundary

Local tests cover original identity preservation, ignored obsolete preferences,
card data and selection retention, captured bytes, defaults, original-IDm device
metadata, System Code, unsupported blocks, copy-on-read and write-failure frames.
RF, account and cabinet acceptance still need version-specific device tests.
The dated sections below describe earlier snapshots, including since-removed
compatibility algorithms and migration; they are retained as historical evidence.

### Original-identity cleanup validation (2026-10-09)

The current experimental working tree passed local JDK 22
`:app:testDebugUnitTest :app:lintDebug`: 616 tests, no failures/errors/skips,
and lint 0 errors / 19 existing warnings. Coverage includes ignored old route
preferences, original-only service responses and metadata, card/selection
preservation, UI controls, original-IDm preparation and mismatched-plan rejection.
The static 4000/02FE initializer is unchanged. No APK was assembled or installed,
and there was no phone, reader or account test for this cleanup.

The subsequent local JDK 22 `assembleDebug` produced
`dist/AimeSimulator-3.0.0-original-idm-preview-debug.apk`, versionCode 48,
15,779,690 bytes, SHA-256
`972C8298F4EF2C37249312F21592E5C09D72137DCC38CCEE84C9701EE8E34CC3`.
Package metadata, APK v2 signing with the existing debug certificate, and artifact
checks passed. The APK has not been installed; device acceptance remains pending.

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
