# HCE-F Lab

Isolated Android 11+ research application and shell probe for the reduced-privilege
HCE-F investigation. It has its own package (`io.github.umislat.aicecho.hceflab`), no payment
AID, no network permission, no Root calls and no access to AICEcho card data.
It is not an Aime replacement or a release APK.

Lab 0.2 adds a fixed synthetic saved-identity mode and an offline, restricted
provisioning planner. See [PROVISIONING.md](PROVISIONING.md) for the separate
verification gates. On 2026-10-08 this revision separately passed a controlled
ADB-restore/reload/RF/rollback experiment using synthetic `88B4/012E6C6162323031`.
Both requested identity and blocks were read externally; the original 4000
baseline was restored and RF-verified. Root was used for read-only full-file
snapshots and checks, not for identity writes. This is not a clean-install
rootless, original-card or game-account result, and does not inherit unrelated
Lab 0.1 or guardian results described below.

## Build

On Windows PowerShell 7 with JDK 17+ and Android SDK platform 36/build-tools 36.1.0:

```powershell
./tools/hcef-lab/build.ps1
```

The script runs protocol, trace, session, cross-process lock and recovery tests, builds the APK and shell DEX,
and signs only with the existing local Android debug key. Override `-Sdk` or
`-DebugKeystore` when necessary. It does not generate keys, install, launch,
modify NFC settings, or build the main app. Outputs stay in ignored `build/hcef-lab`.

Tests alone need only JDK 17+, with no SDK, signing key or device:

```powershell
./tools/hcef-lab/test.ps1
```

To build only the probe, without consulting a signing key or building an APK:

```powershell
./tools/hcef-lab/build.ps1 -ProbeOnly -OutputDirectory ./build/hcef-lab-candidate
```

Each build uses a fresh staging directory for classes, so removed/test classes
cannot leak into a later DEX. Use a separate output directory when preserving
artifacts from a measured experiment. `test.ps1 -TraceFixture <local-dump>` adds
the original private GET_CONFIG capture replay to the synthetic trace checks.

## Legal Baseline

Install `build/hcef-lab/HcefLab-debug.apk` and open HCE-F Lab. While foreground,
it registers only its own service with `4000 / 02FE6C6162323031`, PMm
`00F1000000014300`. It disables its foreground service when paused. Resuming or
pressing Start resets the legal baseline and the synthetic image, except when
Lab 0.2's saved-identity mode is selected. That mode persists and only checks/
enables the exact synthetic 88B4/012E mapping without calling registration setters.

The test service supports read-only service `000B`, with synthetic blocks:

| Block | Data |
| --- | --- |
| 00 | ASCII `HCEF-LAB-0000000` (16 bytes), not a real Access Code |
| 82 | Synthetic lab NFCID2 followed by eight zero bytes |
| 85 | Image System Code followed by fourteen zero bytes |

Malformed frames and another NFCID2 receive no response. Unsupported reads and
writes are rejected. No card personalization or data import is available.

Validate FFFF/4000 polling and all three blocks through an external reader before
trying controller changes. Inspect Android HcefLab RX/TX logs too. A foreground
registration returning true alone is not proof of RF or application delivery.

## Shell Probe

The probe requires UID 2000 (including for reads), uses the real reflected INfcAdapter interface and existing shell
WRITE_SECURE_SETTINGS/DUMP permissions. It does not grant permissions or register
the global vendor callback. Response payloads come from the existing Android
NFCSNOOP system ring; unsupported or ambiguous capture fails closed.

Push `build/hcef-lab/probe-dex/classes.dex` to an explicitly named temporary path,
then run `app_process /system/bin NciLab read-lab` with CLASSPATH set to that path.
The read-only mode queries ID1 and Flags2. It does not restart NFC or alter routing.

Example read-only invocation (select the actual device):

```powershell
$adb = 'C:\Android\platform-tools\adb.exe'
$serial = '<selected-device-serial>'
& $adb -s $serial push build/hcef-lab/probe-dex/classes.dex /data/local/tmp/aicecho-hcef-lab.dex
& $adb -s $serial shell 'CLASSPATH=/data/local/tmp/aicecho-hcef-lab.dex app_process /system/bin NciLab read-lab'
```

**The write modes are experimental, not production activation APIs.** `temporary-sc`
requires enabled ID1 to exactly equal the known synthetic 18-byte baseline.
It changes only the two System Code bytes to `88B4`, verifies controller status
and readback, waits 120 seconds for RF testing, then restores the saved value
and checks it. No original-card NFCID2 or arbitrary parameter input is supported.

`set-baseline` performs a same-value SET_CONFIG control. On the tested ROM both
this and an 88B4 write are rejected while discovery is running. The validated
`temporary-sc-idle` variant uses the ROM's reflected `pausePolling(long)` with a
15-second safety timeout, writes and reads back the exact lab slot, then calls
`resumePolling()` only if it owned the pause. It checks readback again after
resume and uses the same pause/write/readback/resume sequence for restoration.
This implementation intentionally fails on other API signatures instead of
guessing transaction numbers. Neither mode issues raw discovery/routing commands.

Only a slot still containing that exact temporary value is eligible for restoration.
An observed owner/configuration change rejects the write. `restore-lab` is an explicit recovery
command accepting only the exact known temporary value or the unchanged baseline.

Before a write test, establish stable ADB, release the reader's active target,
keep Lab foreground, and record the baseline. Run independently of the USB shell
connection with an observable on-device log/PID so a loose cable cannot terminate
the restoration sequence. Do not kill the process during its test window.
Automatic restoration in these legacy modes is best effort: process death or loss of NFC service can
prevent it. In that case, recover using `restore-lab` (only with no pending supervised record), or stop/reopen only the lab
application so Android recommits its baseline, and verify the controller again.
Never claim recovery from intent or the process's exit code alone.

The observed recovery sequence is: wait for the detached test process to finish,
confirm `RESTORED exact baseline` and a new GET_CONFIG readback, reset the image
to 4000, then verify FFFF/4000 and block reads externally. A normal Activity pause
or the Stop button then removes Lab's foreground route. **Do not use force-stop
alone as proof of route cleanup**: on the test ROM it left the T3T cache and
native slot active until Lab was reopened and normally paused.

The protected `LabControl` receiver can change only the synthetic Block 85 image,
without changing controller routing or restarting the activity. Its explicit
`io.github.umislat.aicecho.hceflab.IMAGE_SC` action accepts integer `sc` 16384 or 34996,
and requires the sender's Android DUMP permission. Matching the image to 88B4 is
a separate operation, not proof that the RF System Code changed.

## Guarded Probe Revision

The guarded revision differs from the first RF-tested DEX. `NciSession` separates
the exact synthetic transition from Binder so failure paths can be tested without
operating hardware. It rechecks ID1 and its enabled flag after pausing, requires
the exact expected source value (not either lab value), and validates readback
both before and after resume. `resumePolling()` must return success.

An elapsed-realtime deadline includes time spent in the pause call. A write is
not initiated if the observed pause budget has less than 3 seconds remaining;
neither write nor resume is initiated after observed expiry. During the 120-second
window, a check after each 5-second sleep detects a disabled/replaced slot or a
framework recommit. Original failures are retained if cleanup also fails. A SET
with a lost response still triggers guarded readback/recovery, but a session
that never attempted a write does not adopt another actor's temporary value.

Controller-access modes, including `read-lab` and explicit restore, hold an OS file lock at
`/data/local/tmp/aicecho-hcef-lab.lock` for their entire lifetime. The empty file
is intentionally retained; never delete it to bypass contention, since unlinking
allows simultaneous locks on different inodes. It is mode 0600 on the tested
phone. Process death releases the lock, **not the controller configuration**.
Use the running probe's logs or read-only dumpsys during a window, rather than
starting a second controller-access command. The newer supervised mode's status
and stop requests only access local metadata. The first RF-tested probe does not use this
lock; never mix old and new probes concurrently.

Trace correlation now rejects duplicate exact commands, unresolved preceding
same-opcode commands, and interleaved/fragmented same-opcode commands, rather
than choosing the most recent similar command and assuming its response belongs
to this call. No global vendor callback is taken over.

The guards in this RF-tested revision are not an independent watchdog, an atomic compare-and-set API or
a hard timeout for synchronous Binder calls. A competing framework write can
still race a read/write pair, and process death, suspend or service failure can
prevent prompt restoration. They are prerequisites for a future limited shell
backend, not proof that production activation/recovery is solved.

This revision passed desktop fault tests, real trace replay, an Android UID 2000
read-only check, and a separate RF rerun on 2026-10-08. Guarded DEX SHA-256:
`C60352969CB3974606BA4FD301FA14A2AA19E0AAC340E12EFEA82D6308E27DC6`.
With the unchanged Lab APK, two scans during one monitored 120-second window
read all three blocks under FFFF/88B4, with matching Block 85 and no 4000 target.
Automatic restore, independent readback, a fresh 4000 RF baseline, and normal
Activity-pause cleanup were verified. The first post-restore scan had a PN532
`response has no command` error on FFFF while 4000 read successfully; the next
complete scan passed. Both captures are retained, and the transient's cause is
unresolved. This is a successful normal-path rerun, not hardware fault-injection
coverage or a process-death recovery guarantee. HyperCeiler's NFC scope remained
enabled, as in the earlier cold-boot comparison.

## Supervised Session Candidate

The current source adds an independent recovery process to the isolated probe.
This candidate is **desktop-tested only**, not installed or RF-tested. Its DEX is
`build/hcef-supervised-20261008/probe-dex/classes.dex`, SHA-256
`A1D5F82A22015FAB75CADB81F2E8584AEB9814B7AA956E66EECEED8AC7CD016F`.
The C6035296 guarded RF artifact and the installed Lab APK remain unchanged.
Do not transfer their device-test results to this candidate.

The allowlisted entry points are:

| Mode | Effect |
| --- | --- |
| `session-start` | Exact synthetic 4000-to-88B4 transition, at most a nominal 120-second window, then guarded restore; starts an independent guardian before SET |
| `session-status` | Read local session token/phase/boot metadata only; not evidence of a live process or successful recovery |
| `session-stop <token>` | Record a token-bound stop request; does not confirm restoration |
| `session-watch <token>` | Internal guardian entry point, required to attach before the worker can write; refuses late attachment to an armed record |
| `session-ack <token>` | Under the shared lock, GET the independently restored exact synthetic 4000 baseline, then clear a pending record; no controller write |

The worker holds the existing OS lock throughout its session. Before writing, it
persists a PREPARED record, launches a guardian using Android `nohup/app_process`,
and requires the guardian's READY signal and live process. Immediately before
SET it records ARMED intent, then rechecks the slot, enabled flag and pause
budget. A live worker that aborts without attempting SET marks the record DONE,
so recovery does not adopt a value written by someone else during preparation.
Normal restored completion also marks DONE. A stop request is checked before
writing and between monitor intervals; it cannot interrupt a blocking Binder call.

The guardian caches its own Binder before READY and never reconnects to a new
NFC service. It waits for the same OS lock without stealing it or killing the
worker. After handoff of an incomplete record, it waits 15 seconds for the
worker's possible polling pause, then recovers only the exact enabled temporary
synthetic image. A changed slot, disabled flag, dead service, invalid record or
expired lease prevents a blind write. Failed recovery retains the record and
blocks new write modes; `read-lab` remains available when the lock is free.
Pending records must not be deleted to bypass recovery. After independently
restoring Lab and confirming the guardian is terminal, use the baseline-only
acknowledgement if needed. An acknowledgement is not an RF verification.

Records/signals/logs are private under `/data/local/tmp/aicecho-hcef-recovery`
(0700 directory, 0600 files on POSIX). The fixed-schema record includes a random
session UUID, boot UUID, elapsed creation time, phase and checksum. Its payload
contains no real card data or arbitrary commands. Updates fsync a temporary file
and atomically rename it, but do not fsync the directory; power-loss durability
is not claimed. The checksum and UUID do not authenticate against another process
running as shell. All writers must cooperate with this revision's lock and record;
older probes are unaware of the recovery gate and must never run concurrently.

Desktop validation covers normal expiry, stop, failed startup, ownership changes,
corrupt/stale records and forcibly terminating a real worker JVM while a separate
guardian JVM survives. These tests use file-backed synthetic slots, accelerated
clocks and desktop process semantics, not Android Binder/NCI or phone process death.
The guardian launch path and permissions on Android still need validation.

This remains best-effort recovery, not atomic ownership or a hard Binder timeout.
ARMED means intent, not proof that SET executed: a crash between intent and SET
cannot distinguish an identical value installed by another actor (an ABA race).
The 15-second grace is not proof that queued Binder work has drained. Suspension,
both processes dying, service failure or an unresponsive call can prevent prompt
recovery. The guardian exits rather than stealing a lock after its bounded wait;
the worker's wait timeout reports pending recovery and does not kill the guardian.
No Shizuku/app IPC, original-IDm support, Block 85 synchronization, production
integration or account correctness is added by this candidate.

## Evidence Boundaries

> The following device records preserve the names and package identities used in those historical builds. The current AICEcho identities have not yet received device verification.



### Read-Only Identity Audit

`audit/NfcIdentityAudit.java` is built separately so the RF-tested probe artifact
is not changed. It accepts no arguments, requires shell UID 2000, enumerates the
actual Binder method signatures and runs local format checks. Its only service
calls are NFC state, NFC-F interface/max/list getters and the Lab's individual
identity getters. It does not enable a service or send NCI. It reports only Lab
from the service list; other applications' identity values are not printed.

```powershell
.\tools\hcef-lab\build-identity-audit.ps1
```

Output: `build/hcef-identity-audit/dex/classes.dex`. For an already authorized
connected device, push that file to a temporary path and invoke entry point
`NfcIdentityAudit` through shell `app_process`. Remove the temporary file after
capturing the report. Do not use root as a substitute for a shell audit.

The 2026-10-08 audit found a service-ownership check in addition to the format
check, and confirmed the ROM's Java foreground-ID mapping. An individual getter
returning null to shell does not mean the app has no saved configuration. See
[Original Identity Entry-Point Audit](IDENTITY.md) for measured results, ROM
disassembly boundaries and implications for the future backend.

### Xiaomi Snapshot Candidate

A separately authorized shell-only probe exported both files from the owned
key/value-agent Lab through the ROM's `MiuiBackup` full-file exception. A later,
separately approved fixed-ROM probe also exported the complete global NFC identity
XML without Root, preserving its exact bytes. Neither tool restores anything or
is integrated into the APK; future-write freshness and safe rollback remain open.
See [MIUI_SNAPSHOT.md](MIUI_SNAPSHOT.md) for evidence, scope and safety gates.

### RF Findings

- On 2026-10-08 Xiaomi 15 Pro/Android 16/OS3.0.308.0.WOBCNXM, shell UID 2000
  CORE_GET_CONFIG and this probe's NFCSNOOP response correlation were validated.
- After recovering a loose USB connection and restoring Lab foreground, HINATA
  Lite found the synthetic identity under FFFF/4000 and read blocks 00/82/85.
  88B4 correctly found no target in this baseline. Earlier errors/no-target runs
  with Lab paused were retained as inconclusive, not counted as failures of HCE-F.
- Both changing SC and writing back the same value during discovery returned
  API=0 but controller payload `090140`, leaving the slot unchanged.
- `temporary-sc-idle` obtained controller `0000`, verified 88B4 before and after
  resume, and HINATA found FFFF/88B4 with successful 00/82/85 reads. 4000 then
  found no target. Updating only the synthetic image via LabControl made Block 85
  agree with RF 88B4. Phone service RX/TX logs independently confirmed delivery.
- No additional controller routing command was necessary in that experiment;
  the framework still reported `4000/02FE6C6162323031` during RF 88B4 operation.
- The 120-second automatic restore returned the controller to 4000. After the
  synthetic image was reset, FFFF/4000 and all three blocks passed again, while
  88B4 found no target. Normal Activity pause cleared the foreground T3T cache
  and restored the original FEEF/MIMIBEAM slot. NFC/HAL PIDs did not change.
- A later cold-boot rerun confirmed the AimeSimulator LSPosed module disabled and
  the PMm injection library absent from the HAL. Baseline, RF-only 88B4, matching
  Block 85, two timed restores, and final RF/normal-pause checks all passed.
  HyperCeiler remained enabled for the NFC scope and LSPosed framework remained
  loaded, at the user's choice. Root was used only for read-only environment
  auditing; all NCI operations still ran as shell UID 2000. This excludes a direct
  dependency on this project's two hooks in that run, not all possible hooks.
- One scan ran after the first restore and saw RF 4000 with image 88B4. It was
  retained as a timing control, not a successful 88B4 consistency test. Only after
  that process exited was a second window started for the matching-image test.
- After this reboot, Flags2 was 0100 before lab activation and 0300 after normal
  pause, although ID1 returned to FEEF/MIMIBEAM and the foreground route cleared.
  The probe never wrote Flags2. Recovery of ID1 is not proof that every controller
  parameter equals its boot value; the flags lifecycle remains an open issue.
- A clean no-hook environment, repeated lifecycle behavior, original-IDm routing,
  account identity and unmodified cabinet behavior remain separate gates. The
  rooted test phone still had LSPosed framework loaded. Shell UID 2000 success
  must not be presented as a full clean-install rootless or cabinet result.

Do not commit raw device dumps, captured card data or APKs. Keep this app separate
from production until the full evidence chain and recovery behavior are verified.
