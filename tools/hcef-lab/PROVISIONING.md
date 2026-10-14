# Offline NFC-F Provisioning Candidate

Status: a controlled synthetic-identity restore/reload/RF/rollback experiment
passed on 2026-10-08. The planner itself remains offline. Root was used only for
complete private snapshots and readback; ADB restore and NFC reload ran as shell.
Subsequent standalone and in-app exports obtained the complete identity table
without Root on this ROM. Original-account acceptance, safe concurrent writeback
and clean-install reduced-privilege deployment remain unproven. The historical
experiment below must not be confused with those later acquisition results.

## Why a Separate Candidate

The externally supplied full research bundle reports successful marker-file
delivery into NFC private storage through user-confirmed ADB restore. Its detailed
record says the identity file was not changed; the stronger summary claim that
original-identity provisioning is complete is not accepted as evidence.

An existing persisted mapping can already be checked/enabled by the production
`PersistedHceRouteWorkflow`. Do not replace it or remove its exact-identity check.
The missing operation is a verified, data-preserving way to establish the mapping.
Controller-only ID changes still do not create Java/native listener mappings.

## Restricted Offline Planner

`provision_archive.py` independently constructs the Android backup envelope and
USTAR entries using Python standard-library parsers/writers. It does not import
the external generator, invoke adb/Root/Shizuku, access the network or issue NFC
commands. Format references are Android 16's `TarBackupReader`, `FullRestoreEngine`
and `BackupAgent`; this is not a promise of acceptance by an OEM restore service.

The tool requires a full reviewed XML snapshot, its SHA-256, an existing target
component/UID, the expected old identity, the requested new identity and one
explicitly verified storage domain (`f` OR `d_f`). It has no default identity,
automatic two-domain write, arbitrary-file option or routing-preferences option.

Only AICEcho's AicHostService and Lab's LabService are allowed targets.
Only a primary-user application UID and exactly one existing record can change.
Other records and their attributes are preserved semantically. Unknown schemas,
DTD/entity declarations, comments/PIs, duplicate component aliases, missing
targets, mismatched owners/preimages and oversized input fail before publication.
The rollback contains the complete original XML bytes, not a reconstructed table.

The resulting forward and rollback archives each contain exactly two files:

1. The fixed `apps/com.android.nfc/_manifest`.
2. The identity XML under the explicitly chosen file domain.

Generated archives are round-trip checked. Inspection rejects extra destinations,
links, extended metadata, changed manifests, compressed trailing data, oversized
payloads and noncanonical tar data. It never extracts archive paths to disk.

Outputs go into a newly created directory; an existing output is never overwritten.
`OFFLINE_PLAN_COMPLETE` is written last, after the data files have been flushed.
Partial output has no completion marker and must not be used. This is not a
power-loss durability guarantee, live transaction or recovery journal.

Files contain plaintext identity data. On Windows, protection depends on the
parent directory's ACL; POSIX 0600/0700 modes alone do not establish a Windows ACL.
Use private ignored storage, never a public/shared upload location. Hashes are
integrity checks, not proof of device origin, freshness or authorization.

### Usage

Python 3.10+ is sufficient. All arguments are mandatory; placeholders below are
not a runnable restore recipe:

```text
python tools/hcef-lab/provision_archive.py
  --baseline <reviewed-full-xml>
  --baseline-sha256 <reviewed-sha256>
  --domain <verified-f-or-d_f>
  --component <installed-target-component>
  --uid <installed-primary-user-app-uid>
  --expected-sc <current-SC>
  --expected-idm <current-IDm>
  --target-sc <requested-SC>
  --target-idm <requested-IDm>
  --out <new-private-output-directory>
```

Review `plan.json`, `baseline.xml` and `proposed.xml` locally. No real card identity
is printed to stdout. A complete plan remains `offline-unverified`; generation
does not authorize using `forward.ab` or `rollback.ab` on a device.

Run the offline tests separately from the JDK probe tests:

```powershell
python -B -m unittest discover -s tools/hcef-lab/test -p test_provision_archive.py -v
```

## Lab 0.2 Saved-Identity Mode

The existing LabService is reused; no new service slot or production-app change
is needed. The legal baseline remains `4000 / 02FE6C6162323031`.
The separate fixed synthetic provisioned identity is `88B4 / 012E6C6162323031`.
Neither represents a user's card or guarantees any game account.

`Use saved 88B4 / 012E lab identity` saves the mode, stops the foreground service
and reads its registered SC/NFCID2. Both must match before enable. The mode never
calls identity setters; a mismatch stays stopped. It survives Activity/process
recreation, so reopening after NFC reload does not reset the restored identity.
Set this mode before an approved restore experiment, not after a default-mode
resume has already overwritten the mapping.

In saved mode, the host accepts only requests addressed to the synthetic `012E`
identity. Response NFCID2 and Block 82 use that identity, Block 85 is `88B4`, and
Block 00 remains the synthetic Lab payload. Baseline `02FE` requests, malformed
requests and unsupported operations do not receive another identity's data.
Writes remain rejected. The old IMAGE_SC receiver affects only the baseline mode.

`Start 4000 baseline` is an explicit state-changing recovery operation: it saves
baseline mode, registers legal values for this LabService only and resets Block
85. Do not press it during a saved-identity verification. Normal Stop/background
pause disables the foreground service without clearing the saved mode.

The code has local protocol checks, a debug-signed APK and the separate device
verification below. Saved mode survived a cold Lab process start after NFC
reload, enabled the exact synthetic identity and refused the restored baseline
until the baseline button was explicitly selected. The previously RF-tested
Lab 0.1 APK remains available for comparison.

## Required Device Gates

1. Confirm device, package/UID, NFC APK/ROM, hook conditions and the complete live
   identity table/storage domain. A received historical XML or matching local hash
   is not a current snapshot. Without a complete trustworthy baseline, stop.
2. First validate the restore mechanics with an isolated owned test application;
   account for its different signature/eligibility rules. That does not by itself
   prove system NFC restore acceptance.
3. Check restore-agent side effects and data-clear policy before any NFC restore.
   User confirmation, a reviewed plan and explicit approval are required. No
   automatic NFC crash/reboot or overwrite of other service/routing preferences.
4. Install/enable the new synthetic Lab mode, then verify a synthetic identity
   restore, reload, native/Java registration, real RF and blocks, and recovery.
   Observe all preserved services as well as the changed Lab entry.
5. Recheck the live table before rollback too: restoring the old full file after
   another app changes it can discard those new values. The local planner does
   not provide atomic on-device ownership or solve this race.
6. Only after the synthetic flow succeeds, validate original-card identity and
   the expected account. Real-card and cabinet success cannot be replaced by
   source review, a restore completion log, a PID change or synthetic block reads.

No bounded Shizuku provisioning backend, safe automatic system-file merge,
on-device restore transaction or multi-slot integration is implemented here.

## Local Candidate Verification, 2026-10-08

> The following device records preserve the names and package identities used in those historical builds. The current AICEcho identities have not yet received device verification.



The restricted planner passed 18 unittest methods (including malformed/oversized
archives, owner/preimage mismatch, unrelated-record preservation, exact rollback,
CLI refusal and partial publication). The fixture output contains synthetic data
only; independent .NET tar/zlib reading confirmed both directions, exact XML and
the preserved other service. No historical real-card snapshot was made into a
new restore package.

Lab 0.2 passed 63 protocol checks; the unchanged NCI suites passed 21 trace,
40 session, 7 lock and 23 recovery checks. Its complete resource/Java/DEX build
and v3 debug signature verification passed. The signer matches the preserved
Lab 0.1 artifact. The new APK is `dist/HcefLab-0.2-provisioning-debug.apk`, SHA-256
`E9607E0C8468D2E6A783478F1852753DDF106625BCE6C94F89AFBB3CB7CCA63F`.
At that build stage the Activity mode had not been exercised on Android. Its
subsequent controlled device result is recorded below; it is not exhaustive
lifecycle coverage.

A separate [Restore Path Lab](restore-lab/README.md) and marker-only archive
generator were built for the isolated restore gate. After the user subsequently
authorized device testing, its own CE/DE markers were restored and verified
byte-for-byte on the same ROM. The agent callback and BackupManager logs agree.
That isolated phase performed no system NFC restore or restart. Its success
satisfied the owned-app mechanics gate, not the system NFC gates; the separate
system experiment followed only after explicit user authorization.

## Synthetic Identity Device Record

Device: Xiaomi 15 Pro, Android 16 / OS3.0.308.0.WOBCNXM, HINATA Lite PID 0148.
The live NFC APK SHA-256 matched the previously audited `6F6E8FF4...` binary.
Lab 0.2 retained UID 10394 and the original signer. This run did not re-audit
hooks; the already rooted phone is not evidence of a clean no-hook environment.

Preparation used read-only `su` to capture the complete CE identity XML, NFC
files/preferences and hashes. The live identity file contained Lab and Aime
records, with no pending AtomicFile backup/new file observed. The corresponding
DE files directory was empty. Users 0 and 999 existed; no identity-file snapshot
was synthesized from a current-user Binder list. NFC's own backup agent was
reviewed, the force-clear-before-restore list was unset, and its callback's
NFC/reader/secure-state effects were checked against current state.

The independent archive check allowed exactly manifest plus the CE identity
file. Only Lab changed from `4000/02FE6C6162323031` to
`88B4/012E6C6162323031`; the other record retained every attribute. The mode was
saved before restoration, and live preimage hashes were rechecked before each
direction. This reduces risk but is not an atomic on-device transaction.

| Phase (UTC+8) | Observed result |
| --- | --- |
| 19:47:27 forward restore | User confirmed; shell ADB restore wrote exactly the proposed 374-byte XML. Other recorded file contents and default wallet remained unchanged. |
| Before reload | NFC PID 19647 still held Lab's old 4000 mapping despite the changed file. Restore completion alone was insufficient. |
| Approved NFC reload | Shell `am crash` restarted NFC as PID 1309. Framework registered the synthetic 012E/88B4 mapping. No raw NCI command or Root write was used. |
| 19:48:56 Lab cold start | Saved mode read the expected identity and enabled it without setters. |
| 19:49:48 RF | FFFF and 88B4 returned IDm 012E6C6162323031, PMm 00F1000000014300, SC 88B4 and exact blocks 00/82/85. 4000 found no target. Lab RX/TX matched the external requests. |
| First two rollback attempts | User confirmation timed out after about 60 seconds. ADB exited zero but the file stayed modified; the hash gate prevented an NFC reload. Both failures were retained. |
| 19:55:46 rollback | User confirmed the third attempt. XML matched the original 396 bytes exactly, followed by an approved shell reload to NFC PID 7657. |
| 19:57:39 baseline RF | Saved mode correctly refused the rolled-back identity. After explicit baseline selection, FFFF/4000 returned 02FE and matching three blocks; 88B4 found no target. |
| Cleanup | Normal pause cleared foreground T3T cache/routing. Reader disconnected. All recorded NFC files/preferences matched original hashes; default wallet and NFC/reader/secure-state values were unchanged. |

Baseline SHA-256: `C0244B70ECC53DF8915DF2B4EE1D981434166BDFE60ED96EC2383DE2726C28E1`.
Proposed SHA-256: `755F8F0C327572A00498C1A3BEAD4026319370F9C8D63CB758E3AC7790CB3E6A`.
Private evidence: `build/hcef-provision-device-20261008`, including both RF JSONs,
pre/post XML, limited file snapshots/hashes, restore logs and the reviewed plan.
Do not publish these files: the preserved other service contains a real identity.

This establishes a controlled non-02FE synthetic identity path through persisted
registration, activation and app data exchange on this ROM, plus a verified
rollback. It does not establish original-card/account acceptance, long-term or
cold-boot stability, arbitrary identities, Shizuku integration, race-free rollback,
pure-rootless full-table acquisition or general OEM support. Restoration verifies
original file contents, not original file timestamps. Both test APKs remain
installed; Lab is back in baseline mode and inactive. No production APK changed.

## Complete-Table Boundary

The saved ROM's `RegisteredNfcFServicesCache` reads one global dynamic identity
XML into per-user maps, deriving each user from its stored UID. Its writer loops
over all user maps. In contrast, `getNfcFServices(userId)` returns that user's
installed service cache; the Binder also validates the caller's profile access.
Consequently, `getNfcFServices(0)` alone is not a proven complete snapshot of the
file, even when every returned service can be decoded. It can omit other users
and does not expose the persisted maps as a whole.

Do not synthesize a replacement global file from that list or assume an absent
record is safe to discard. This planner requires a complete trustworthy live
baseline and fails closed on unsupported multi-user/duplicate-component input.
Local hash validation cannot establish completeness or prevent a concurrent
writer. This finding comes from the saved ROM disassembly, not a new live file
read or a demonstrated rootless snapshot mechanism.

A later, separately approved `MiuiBackup` export obtained the complete original
396-byte global XML without Root on the same ROM. See [MIUI_SNAPSHOT.md](MIUI_SNAPSHOT.md).
The snapshot parser preserved exact bytes, and this existing planner produced a
Lab-only offline change plus exact rollback from them. No plan was sent to the
phone or restored in that export test. This resolves one acquisition mechanism,
not live freshness, atomic conflict detection or safe rollback ownership.

## Later App-Side Plan

The Shizuku application's export-only build subsequently passed one user-confirmed
complete-table capture. Local Kotlin helpers now also generate a restricted
single-record plan and fixed CE archive. They are not connected to a device write
endpoint. Unlike this historical Python planner's whole-original rollback archive,
the Kotlin rollback candidate merges the old target into a newly observed table
when other services changed, and rejects a changed target record.

Neither planner provides an atomic transaction with the NFC service. The service's
cache lock is not shared by the inherited backup-agent restore path. Rechecking
hashes or switching NFC off does not establish writer exclusion. The Kotlin
archive uses the actual pinned public signer and ROM version; its device acceptance
must be verified separately from the historical Python archive. See
[the app integration record](../../docs/SHIZUKU_PROVISIONING.md#local-provisioning-plan-2026-10-08)
for current checks, tests and remaining gates. This step did not operate the phone.
