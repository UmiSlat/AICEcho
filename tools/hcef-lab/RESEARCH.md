# Reduced-Privilege HCE-F Research Gates
> Historical evidence: the recorded package names and artifacts belong to the revisions tested at the time. Current tools use the AICEcho namespace; these device results have not been rerun under the new identities.

The goal is usable Aime simulation without Root or with reduced privileges.
A synthetic 02FE demonstration is not a substitute for account-correct simulation.

## Verified On 2026-10-08

Target: Xiaomi 15 Pro, Android 16, HyperOS OS3.0.308.0.WOBCNXM, ST NFC.
Reader: HINATA Lite. Application: isolated HCE-F Lab, synthetic data only.

| Gate | Evidence | Boundary |
| --- | --- | --- |
| Shell raw NCI | UID 2000, WSS permission, CORE_GET_CONFIG command and response | Not an unprivileged app API |
| Legal host service | 4000 / 02FE6C6162323031, framework cache and native configuration | No invalid framework identity registration |
| Baseline RF/host | FFFF and 4000 with 00/82/85 blocks; phone RX/TX logs | Synthetic card only |
| State control | SC change and same-value write both rejected during discovery; 88B4 accepted with framework-managed pause | API portability unverified |
| 88B4 RF/host | FFFF and 88B4 read all blocks; 4000 absent; Block 85 can agree with RF | No separate routing write needed on this ROM |
| Recovery | Timed 4000 restore, readback, RF recheck, normal pause restores MIMIBEAM | Forced process death alone left routing stale |

The tested sequence was:

```text
App: valid 4000 + 02FE registration and foreground enable
Shell: inspect the exact owned slot and flags
Shell: pausePolling(long), with bounded timeout
Shell: CORE_SET_CONFIG for that slot, changing only SC to 88B4
Shell: validate controller response and GET_CONFIG readback
Shell: resumePolling(), then read back again
Reader: poll 88B4 and read 000B blocks
Shell: pause, restore saved 4000, read back, resume, read back
Reader: verify the original baseline
App: normal foreground disable; inspect route cleanup
```

No Root call, permission grant, global vendor callback replacement, raw RF
deactivation or raw routing command was part of this sequence. The phone was
already rooted and LSPosed framework was loaded, so no-hook validation remains
open. The first run did not include a reboot.

## Cold-Boot Rerun Without Project Hooks

The user subsequently disabled the project's hooks and rebooted. Read-only
configuration/process inspection confirmed the AimeSimulator LSPosed module was
disabled and the PMm injection library was absent from the HAL. HyperCeiler still
had an enabled NFC scope and LSPosed framework was present; the user chose to keep
that condition for this comparison. Root was used only for this environment
audit, not for NCI operations or permission grants.

The unchanged APK/probe repeated the legal baseline, RF-only 88B4 and matching
RF/Block-85 reads successfully. Both 120-second windows restored ID1; a fresh RF
scan after resetting the synthetic image passed the original 4000 baseline.
Normal Activity pause cleared the foreground route and restored MIMIBEAM, without
changing NFC/HAL PIDs. An intervening scan after the first window had expired was
retained as RF4000/image88B4, not mislabeled as a successful 88B4 test.

Flags2 changed from boot-time 0100 to 0300 and stayed 0300 after normal pause. The
probe did not write that parameter. Record this as an unresolved controller
lifecycle observation rather than assuming byte-for-byte boot-state restoration.
The result removes a direct dependency on the project's Aime/PMm hooks for this
synthetic experiment, but does not resolve original-card identity or clean-ROM
deployment.

## Guarded Probe RF Rerun

The guarded probe revision adds independently tested transition logic, deadline
checks, exact preimage checks, progress-window monitoring, process serialization
and stricter trace correlation. Its final DEX, SHA-256
`C60352969CB3974606BA4FD301FA14A2AA19E0AAC340E12EFEA82D6308E27DC6`,
passed a separate RF test on the same boot, with the unchanged Lab APK. All NCI
operations used shell UID 2000; no permission, module or real-card change was made.

The legal baseline passed. After changing the synthetic Block 85 and running
`temporary-sc-idle`, two complete scans in the monitored 120-second window found
FFFF/88B4, read 00/82/85 with matching 88B4, and found no 4000 target. Framework
registration remained 4000 with the same synthetic NFCID2. The probe completed
its periodic checks and exact baseline restoration, then exited. A separate
readback confirmed 4000 before the image was reset.

The first post-restore scan had a PN532 `response has no command` error on FFFF,
but 4000 read all blocks and 88B4 was absent. The error capture was preserved;
a second complete scan passed FFFF/4000 and all blocks. The transient's cause
is not established. Normal Activity pause cleared the foreground cache/route
and restored ID1 to FEEF/MIMIBEAM. Flags2 remained 0300, NFC/HAL PIDs were
unchanged, and temporary probe files were removed while retaining the lock file.

This validates the guarded normal write/monitor/restore path on this device,
not crash recovery, all NFC-scoped hooks being absent, original-card identity,
or production integration. Private evidence is under
`build/hcef-lab-hardening-20261008/rf-validation`.

## Remaining Work

The external full bundle reports marker-file restore into NFC private storage,
but its detailed record explicitly leaves identity-file replacement and RF
acceptance untested. The new [offline provisioning candidate](PROVISIONING.md)
preserves unrelated records and prepares an exact XML rollback. Lab 0.2 can
check/enable a fixed non-02FE synthetic identity without overwriting it on resume.
The subsequent controlled device experiment separately passed full-file
preservation, synthetic 012E/88B4 restoration, reload, RF/blocks and rollback.
Root was required for trustworthy read-only full-file snapshots/checks, while
restore and reload used shell. Original account, rootless snapshot acquisition
and clean-environment gates remain. See PROVISIONING.md for the exact limits.
The existing production saved-route workflow is unchanged.

1. Exclude remaining NFC-scoped hooks (notably HyperCeiler on the test phone)
   before claiming a fully hook-free result. The cold-boot test without the
   project's own hooks is complete, but clean-ROM deployment remains unproven.
   Preserve existing Aime card data and pre-provisioned identity.
2. Make ownership and recovery independent of process survival. Cover timeout,
   disconnect, service restart, competing foreground service and framework
   routing recommits, and the observed Flags2 difference after normal pause.
   A supervised synthetic-session candidate now has desktop subprocess-crash,
   stop and pending-record tests; Android guardian launch, RF recovery and
   service-death behavior remain unverified. Exact-value checks are not atomic
   ownership and cannot resolve an identical-value ABA race. Refuse restoration
   when a different owner/configuration is observed.
3. Design a limited ADB/Shizuku shell backend. Ordinary app reflection and
   permissions are not validated by app_process. Avoid exporting arbitrary NCI.
4. Solve original-IDm native/Java mapping separately. Changing only controller
   NFCID2 does not update native listener identity or Java service lookup.
   Persisted-file restoration has now established a synthetic non-02FE mapping
   with actual RF/app exchange. Real-card/account validation and safe rootless
   provisioning remain open; the SC-only probe itself has not gained IDm support.
5. Retain the provision-once route as a distinct original-identity option, not a
   clean-install rootless solution.
6. Validate account behavior and an unmodified cabinet after identity is resolved.
   Local HINATA and game bridge results cannot replace that gate.
7. Integrate a verified experimental backend only after bounded failure handling.
   Keep diagnostics in advanced mode; never silently replace a user's identity.

### Desktop Recovery Candidate

`NciSupervised`, `NciRecovery` and `NciRecoveryJournal` add a token-bound stop
request, persisted intent and a separately launched guardian. New writes are
blocked while a recovery record is pending. The desktop suite passed 40 session
scenarios, 23 recovery scenarios (including actual worker-process termination),
7 lock checks, 33 protocol checks and 25 trace checks with the private capture.
The probe-only build reran the same suites without the optional capture (21 trace
checks) and produced DEX SHA-256
`A1D5F82A22015FAB75CADB81F2E8584AEB9814B7AA956E66EECEED8AC7CD016F`.
The DEX contains probe classes only, not tests or the Lab app.

This revision was not pushed to the phone. Its subprocess tests use file-backed
synthetic slots and accelerated clocks, not Binder/NCI or Android process groups.
The previous RF-tested C6035296 DEX and Lab APK are preserved. See the tool README
for exact modes, retained-record handling and failure limits. Recovery work does
not resolve the original-account gate below.

## Original Identity Boundary

The pinned Android 16 r1 sources were fetched again from the official repository
and matched the already reviewed local SHA-256 values. In
[native activation](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r1/libnfc-nci/src/nfa/ce/nfa_ce_act.cc#801),
NFCID2 is matched against the registered listener table. In
[Java dispatch](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r1/NfcNci/src/com/android/nfc/cardemulation/HostNfcFEmulationManager.java#120),
the first command needs a matching cached service; the active-service fallback
does not establish that initial mapping. This supports investigating native and
Java registration together rather than assuming a controller ID change updates
either. It is a source constraint, not proof that every Xiaomi-specific route is
impossible. The current probe still changes only SC and never replaces NFCID2 or
weakens the application's identity check.

A subsequent [actual-ROM entry-point audit](IDENTITY.md) confirmed the Java
dispatcher/cache behavior in the installed Xiaomi APK, and a read-only shell
probe confirmed interface shape and local format validation. The Binder setter
also enforces service ownership; shell cannot stand in for the application's UID.
Persisted-record replay explains the known provision-once path but is not a shell
provisioning API. That audit did not establish another registration path; the
later user-confirmed restore experiment is a distinct path, documented separately.
A follow-up offline audit of `libstnfc_nci_jni.so` embedded in that saved device
APK now confirms the eight-byte native listener comparison and registration-copy
path in this specific binary. This is targeted static analysis, not a complete
binary audit or a fresh hash of the library loaded in the running process. See
IDENTITY.md for file hashes, symbol addresses and evidence boundaries. The later
synthetic non-02FE restore/RF result does not establish a normal Binder setter or
an original-card/account result.

## Local Evidence

An isolated owned-app restore gate subsequently passed on 2026-10-08: Restore
Path Lab 0.1 inherited full-file restore through its key/value-only helper, and
both CE/DE nonce markers matched the reviewed archive exactly. User-confirmed
ADB restore, agent callback and engine logs were captured without `su` or NFC
operations; the NFC PID stayed unchanged. This does not prove system NFC file
restore or original identity. See [the device record](restore-lab/README.md) and
[the complete-table boundary](PROVISIONING.md) before any identity experiment.

Private logs/APKs are not tracked. In the originating workspace, build/hcef-lab
contains baseline, temporary 88B4, recovery captures, shell transcripts and phone
logs. Shared Chinese reports record exact filenames, hashes and conditions.
Never publish raw NFC dumps or treat another device's historic result as proof.
