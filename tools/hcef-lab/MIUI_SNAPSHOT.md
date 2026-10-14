# Xiaomi Snapshot Experiments
> Historical evidence: the recorded package names and artifacts belong to the revisions tested at the time. Current tools use the AICEcho namespace; these device results have not been rerun under the new identities.

The later, separately authorized system NFC export also passed on this ROM.
See **System NFC Follow-Up** below. The owned-app result remains a separate gate,
not retrospective authorization to export another package.

## Result and Scope

On 2026-10-08, Xiaomi 15 Pro / Android 16 / OS3.0.308.0.WOBCNXM exported both
CE and DE marker files from the isolated `io.github.umislat.restorepathlab`
using **shell UID 2000**, without Root. This app has a key/value-only
`BackupAgentHelper`, not `fullBackupOnly`. The user separately authorized this
backup; the earlier restore permission was not treated as backup permission.

The export contains exactly four regular files: its manifest, both markers,
and its `restore_lab.xml` preferences. The 44/46-byte markers match the previous
Shizuku restore nonce exactly. The manifest's full public certificate matches
the pinned installed debug signer. Python verification and independent .NET
TarReader inspection agree.

This is **owned-app file-export evidence**, not yet a complete system NFC
snapshot, an app-integrated snapshot backend or original-account emulation.
No NFC backup/restore/reload, wallet operation, raw NCI, `su`, new APK install
or production app change was performed during this probe. The phone already
has Root/LSPosed; shell execution is not proof of a clean unrooted environment.

## Why This Path Differs

The actual device's public framework JARs were pulled and narrowly disassembled
locally. Vendor code and binary artifacts remain in ignored build storage.

| Entry point | Inspected behavior |
| --- | --- |
| Standard AOSP `adb backup` | Moves key/value-only agents out of the full-file queue; enabling key/value backup does not export arbitrary files. |
| Standard AOSP `bmgr fullbackup` | Full-transport eligibility rejects key/value-only agents; this is a transport operation, not a neutral local file read. Not invoked. |
| `miui.restore.service.backupAppData` | Actual ROM checks calling UID equals 6100 (`0x17d4`). Shell does not qualify. Not invoked. |
| `getDataFileInfo` / `listDataDir` | Actual ROM checks specific Xiaomi backup/migration package ownership and the system UID signature. Not invoked. |
| `MiuiBackup.acquire` | Checks `android.permission.BACKUP`, then owns the session by PID and a live Binder. Shell has BACKUP on this ROM. |
| `MiuiBackup.backupPackage` | Requires that session, delegates to modified `adbBackup`, and can temporarily disable/stop non-system target apps. |
| Modified `PerformAdbBackupTask` | `isForceAllowBackup` is checked at the key/value exclusion branch. A recognized Xiaomi session can retain a key/value agent in the full-file queue. This is the decisive vendor difference. |

The ROM's `isForceAllowBackup` also depends on international-build/system-app
classification. This is not a general Android guarantee. Its internal
`startConfirmationUi` implementation acknowledges its own backup session; this
path need not display the ordinary Android ADB confirmation dialog. Our probe
requires separate explicit human authorization and an explicit run argument.
Any eventual app integration must likewise provide its own informed confirmation.

The service performs lifecycle work and can alter a target's enabled state
temporarily. Calling this a strictly read-only device operation would be
misleading even though it exports rather than restores application data.

## Restricted Implementation

`audit/MiuiOwnedBackupProbe.java` uses the device's own Binder proxy by reflection.
There are no hard-coded transaction numbers or copied vendor implementations.
It is deliberately separate from the production APK and Shizuku AIDL surface.

- Only `--backup-owned-lab` is accepted; package, agent, version, flags and public
  signer are pinned. No arbitrary package, destination, archive or command input.
- Rejects any process UID except 2000 and any disabled or non-primary-user Lab.
- Requires an idle vendor service and successful acquisition. No forced takeover.
- Disables APK inclusion and XSpace; never calls a restore method.
- Reads binary output through a bounded 1 MiB pipe. Backup wait is 90 seconds;
  reader and release waits are also bounded. Timeout or return alone is not success.
- Reserves `/data/local/tmp/aimesim-miui-owned-backup.ab` privately without
  overwriting an earlier result. Unresolved output must not be deleted for an
  automatic retry. This is a supervised probe, not a durable production journal.
- Releases its acquired session and reports whether the Lab's enabled setting
  was preserved. A release request after a timeout is not proof of completed work.

`verify_miui_owned_backup.py` only accepts the observed unencrypted, uncompressed
MIUI v2 / Android Backup v5 owned-fixture shape. It never extracts to disk.
It checks scope, manifest signer/version, both exact expected markers, regular
files, duplicates, bounded sizes, extended metadata and trailing data. It is not
a generic archive importer or an NFC archive validator.

Offline build and checks:

```powershell
./tools/hcef-lab/build-miui-owned-backup-probe.ps1 -OutputDirectory ./build/miui-owned-backup-probe
python -B -m unittest discover -s tools/hcef-lab/test -p 'test_*.py' -v
python -B tools/hcef-lab/verify_miui_owned_backup.py --archive <owned-lab-export.ab> --token <independently-recorded-marker-token>
```

Building does not authorize running the probe. Before a supervised run, verify
the prior restore is settled, preserve the Lab's exact marker hashes, check its
enabled state, and ensure no other backup or migration is active. Afterwards,
pull the binary result, verify its contents independently, compare the markers
and enabled state again, then remove only this probe's temporary files after
completion is known. Do not change its target to a system app without a separate
review and approval.

## Evidence

- Export: 6236 bytes; SHA-256
  `6aabbaae7db34002de127fd3a90df50d901105eb8f3a7d967f3cea476c5f29f0`.
- Probe DEX: SHA-256
  `b08a01f7f870c686e840d7c9cfe9f7cc1b1b1ffc9ee441d3406723b9691212eb`.
- Exact marker hashes matched before, inside the export, and after the operation.
  Lab enabled state was unchanged. NFC PID remained 7657; default wallet remained
  Aime's `DefaultNfcService`. No probe or `bu` child remained after completion.
- Probe javac/D8 build passed, with one expected standalone main-Looper API
  deprecation warning. All 27 Python test methods passed, including six new
  export-validator methods. No production APK was rebuilt for this experiment.
- Private/local evidence: `build/hcef-snapshot-audit-20261008`, including
  `owned-lab-export.ab` and `verified-owned-export.json`. Do not commit raw ROM
  JARs, disassembly or backup artifacts.

Inspected source binary hashes:

| File | SHA-256 |
| --- | --- |
| `miui-framework.jar` | `704bd79ddc28693c2911c3ce550c193e2aa78ce7edc6a95357ba3fc143efbe9e` |
| `miui-services.jar` | `00561c82eccd541a1935a4ed608283afdba712234aa5f884f4ba276c17104144` |
| `services.jar` | `00b899d027d940cdf7c130bab8e3a08cf7cf4fefef464eb476572392551ec799` |

## System NFC Follow-Up

The user subsequently authorized one **export-only** system NFC test. The current
APK hash was rechecked against the reviewed image. Its `NfcBackupAgent` overrides
creation and restore completion, not full backup. The inspected unbind path
excludes core UIDs from its normal agent-host kill decision; that is a static
finding, not a guarantee that all backup lifecycle effects are harmless.

A separate `MiuiNfcSnapshotProbe` preserves the owned-app probe unchanged. It
pins shell UID 2000, Android 16 / this ROM increment, system NFC UID 1027,
package/version/agent, public certificate, system-app flag and the exact APK hash.
It has only `--export-reviewed-nfc`, no restore/reload or arbitrary target input.
Binary output is capped at 8 MiB, and a private CREATE_NEW file descriptor is
retained rather than reopening a destination by path. Backup/release waits are
bounded. Stale output is not overwritten and there is no automatic retry.

Measured result on 2026-10-08:

- One export call returned and the vendor session released. Archive size 16965
  bytes, SHA-256 `216d08044a748c4a5f9369f40e26a91b14ada62cfa83672d7490c0245025d1d7`.
- Twelve regular files include `f/dynamic_systemcode_nfcid2.xml`: the **complete
  396-byte global XML**, containing two primary-user records. It was obtained
  directly from the backup, not reconstructed from `getNfcFServices(0)`.
- Its bytes exactly match the historical post-rollback original; SHA-256
  `c0244b70ecc53df8915df2b4ee1d981434166bdfe60ed96ec2383de2726c28e1`.
  The historical reference used Root when originally captured; this new export,
  extraction and on-phone checks used no Root. A historical match is additional
  evidence, not a claim that a later write would still have the same preimage.
- NFC PID stayed 7657. The entire pre/post `dumpsys nfc` text is identical after
  removing exactly two changing NFCSNOOP log blocks. NFC/reader/secure states,
  cached services and routing text therefore matched too. Wallet component,
  NFC package enabled setting and original Aime card/appearance hashes matched.
- No restore, NFC reload, settings change, raw NCI, APK install or Root command
  was performed. The phone was already booted/unlocked earlier and was screen
  locked at capture; this is not a before-first-unlock test.
- The offline planner accepted the exported XML, changed only the synthetic Lab
  record and preserved the other record. The generated rollback contains the exact
  exported bytes. Those archives stayed on the PC and were not applied or sent
  to the phone.

`nfc_snapshot.py` validates the fixed package/manifest public signer, bounded
plain backup format, regular paths/domains, duplicates and trailing data. It
requires the reviewed CE identity location and rejects missing/ambiguous DE
copies or unsupported XML; it preserves the original XML bytes. It never silently
rebuilds a partial table. It inventories other NFC files but does not put their
preferences into a restoration package. Reports omit card identifiers and mark
future freshness and restore safety **unverified**. Manifest certificate metadata
is not a cryptographic archive signature; source provenance remains necessary.

The public certificate fixture in `test/fixtures/miui-nfc-signer.hex` is solely
for verifying this installed system package. It is not a private key, a project
signing certificate, or a replacement for AimeSimulator's release key.

Build and inspect offline:

```powershell
./tools/hcef-lab/build-miui-nfc-snapshot-probe.ps1 -OutputDirectory ./build/miui-nfc-snapshot-probe
python -B tools/hcef-lab/nfc_snapshot.py --archive <nfc-export.ab> --out <new-private-directory>
```

The probe javac/D8 build passed, with the same standalone Looper deprecation
warning. DEX SHA-256:
`62ffeefc7362054c188d28d89aeb9069cdcee224253c1a00f9b0d163f8090dcf`.
All 36 Python tests passed, including nine new snapshot/parser/planner tests.
Independent .NET TarReader inspection agreed with the Python result.

Private evidence is in ignored `build/hcef-nfc-snapshot-20261008`, protected on
Windows for the current user, SYSTEM and Administrators. It contains real NFC
identities and must not be uploaded. `verified-device-result.json` records the
cross-checks; `snapshot/identity.xml` preserves the exact bytes. Only this probe's
temporary phone DEX/export were removed after successful verification. The
subsequent APK/Shizuku source now includes a restricted snapshot endpoint.
Separately authorized in-app acceptance passed in build 2.2.22 (code 42): one
phone-confirmed export, exact 396-byte/two-record XML, private save and cleared
pending journal, unchanged NFC state except cyclic logs. Initial code 41 exposed
a Context/UID mismatch for reading BOOT_COUNT; the fixed shell-only read command
resolved it without weakening the pinned export policy. The independent probe
and app results remain separately recorded in
[Shizuku integration](../../docs/SHIZUKU_PROVISIONING.md).

## Remaining Gates

1. Extend the successful Shizuku snapshot test to device expiry, reconnect,
   process loss and failure recovery. One successful export is not full lifecycle
   acceptance, and the device still has Root/LSPosed installed.
2. Validate repeated fresh snapshots, device/boot provenance and supported ROM
   policy. One export does not establish behavior on another ROM or before first
   unlock. Unsupported/multi-user schemas must fail closed, not drop records.
3. Establish fresh read-back and conflict/rollback ownership. Even complete
   snapshots before and after a write do not provide an atomic compare-and-swap
   or prevent another writer between those reads.
4. Only then enable a bounded Shizuku provisioning backend and proceed
   to synthetic RF, original-card/account and failure-path tests.

Related: [Shizuku integration](../../docs/SHIZUKU_PROVISIONING.md),
[NFC provisioning boundaries](PROVISIONING.md).
