# Third-party notices

This project depends on third-party components through their published package
coordinates. No prebuilt application or library copied from an earlier simulator
repository is checked in.

## Packaged and build dependencies

- **Apache Commons Compress 1.28.0**, **Commons IO 2.20.0**,
  **Commons Codec 1.19.0**, and **Commons Lang 3.18.0** - Apache License 2.0.
  Sources: <https://commons.apache.org/proper/commons-compress/>,
  <https://commons.apache.org/proper/commons-io/>,
  <https://commons.apache.org/proper/commons-codec/>,
  <https://commons.apache.org/proper/commons-lang/>.
  The restricted NFC snapshot parser uses Compress's TAR reader; the other
  components are its runtime dependencies. Published JAR `META-INF/LICENSE*`
  and `META-INF/NOTICE*` files are retained and merged in the APK.
- **Shizuku API and provider** (`dev.rikka.shizuku:api:13.1.5`,
  `dev.rikka.shizuku:provider:13.1.5`) - MIT License, Copyright (c) 2021 RikkaW.
  Source: <https://github.com/RikkaApps/Shizuku-API>.
  The full license is included in the APK at `assets/licenses/shizuku-api.txt`.
- **Dobby** (`io.github.vvb2060.ndk:dobby:1.2`) — Apache License 2.0.
  Source: <https://github.com/jmpews/Dobby>
- **libxposed API 101** (`io.github.libxposed:api:101.0.0`) — Apache License 2.0.
  It is a compile-only interface and is not packaged into the APK.
- **AndroidX**, **Material Components for Android**, and the Android Gradle Plugin —
  Apache License 2.0.
- **Kotlin** — Apache License 2.0.
- **JUnit 4** is used only for tests under the Eclipse Public License 1.0.
- **Robolectric 4.16.1** is used only for local Android tests; it is not packaged
  into the APK. Its primary license is MIT, with a subset of Android-derived
  files under Apache-2.0.
  Source/license: <https://github.com/robolectric/robolectric/blob/robolectric-4.16.1/LICENSE>

Each component remains governed by its own license. This notice does not grant a
license to project-owned source code.

## Interoperability source material

- **Project HINATA / hinata_go**, analyzed at commit
  `c56d8badc3a720e0ba9e2f721f3f73111f2f6d97`.
  Source: <https://github.com/Project-HINATA/hinata_go>

  Earlier versions used the Amusement IC fingerprint and FeliCa service/block
  behavior as reader references and included a 2304-byte substitution table
  matching `lib/utils/spad0.dart`. On 2026-10-01 the current source removed
  `AicAccessCodeCodec.kt`, its table and decoding tests, and the fingerprint
  classification branch. NFC-F reading now preserves raw blocks without
  decoding their Access Code. No alternative table source was adopted.

  No license file was present in the analyzed repository revision. This notice
  records historical provenance; removal does not relicense the historical
  commits or change already published binaries.

- **UmiSlat/AICEmu**, template introduced at commit
  `5a1ecc28e98541e8226e1e92b07bd2747e607bb3`.
  Source: <https://github.com/UmiSlat/AICEmu/blob/5a1ecc28e98541e8226e1e92b07bd2747e607bb3/app/src/main/assets/felica_template.json>

  Earlier card images and the shared compatibility identifier followed this
  template/behavior. The current Android source retires the shared identifier
  and constructs a limited read-only view from documented field definitions;
  the old issued memory configuration and key version are not reused. See
  [card-image generation](docs/CARD_IMAGE.md). No repository-level license was
  present in the analyzed revision; historical permissions remain separate.

KonamikU's later template is identical to the AICEmu template. Its GPL-3.0
repository license does not by itself establish permission for the earlier
template used in earlier versions.

## Project development lineage

**UmiSlat/AICEmu_rebuild** (`698a46d`) is the already modified intermediate
project revision, as clarified by the owner on 2026-10-01.
Source: <https://github.com/UmiSlat/AICEmu_rebuild>

Its protocol, hook, native, and KernelSU implementations are part of the
development chain being maintained. Similarity to that intermediate revision
does not establish a new third-party copying issue or require another rewrite.
Comparison with original AICEmu confirms substantial implementation changes;
the retired original template data is documented separately above. No current
launcher vector path matched original AICEmu artwork in this comparison.

The file-by-file evidence and unresolved licensing boundary are documented in
[`docs/PROVENANCE.md`](docs/PROVENANCE.md). Redistribution rights for the
identified material should be resolved before publishing source or binaries
under a project-wide license.
