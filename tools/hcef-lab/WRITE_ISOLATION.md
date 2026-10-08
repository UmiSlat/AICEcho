# NFC Restore Isolation Audit

Scope: saved Xiaomi 15 Pro / Android 16 / OS3.0.308.0.WOBCNXM binaries, inspected
locally on 2026-10-08. No new phone export, restore, package-state change or NFC
restart was performed. This audit does not establish behavior on other ROMs.

## Findings

Full-file backup/restore does **not** provide an exclusive NFC identity-table
transaction on this ROM. The following attractive shortcuts do not close the
read-before-write race:

| Candidate | Evidence and limitation |
| --- | --- |
| Restore into an already running process | `ActivityManagerService.bindBackupAgent` reuses the process and schedules its backup agent. It does not replace the running application with a restricted one. |
| Stop NFC first, then launch a restricted restore process | The actual `attachApplicationLocked` requires the backup target's UID to be at least 10000, alongside the full backup/restore mode and restricted-mode flag. NFC's UID is 1027, so this branch cannot enable restricted application startup for it. |
| Keep the package force-stopped through restore | Agent binding explicitly sets package stopped state to false. A prior force-stop is not an exclusive lease covering restore. No force-stop was attempted in this audit. |
| Rely on automatic agent-host termination | `BackupAgentConnectionManager.shouldKillAppOnUnbind` returns false for core UIDs. NFC is a core UID; ending the agent operation does not imply the normal service is gone. |
| Turn NFC off | `RegisteredNfcFServicesCache.onNfcDisabled` clears `mActivated`. The setters reject RF activation, not a globally disabled adapter. Their Binder entry points do not acquire a restore-wide lock. |
| Hold the app's mutex or compare a snapshot hash | Neither takes the NFC cache's private lock. The inherited `BackupAgent` file-restore path does not acquire it either. Hash comparison cannot exclude a subsequent writer. |

These are control-flow findings, not a live concurrency stress test. They rule
out these specific isolation arguments, not every possible reduced-privilege
mechanism. NFC restore completion can also apply existing preferences through
`NfcBackupAgent.onRestoreFinished`; no preferences archive is needed for that
callback to run.

The Android 16 reference implementation likewise reuses existing processes,
clears stopped state during binding, and restricts the full-backup process flag
to app UIDs. Its application bootstrap uses a base Application only when passed
the restricted-mode flag. The ROM branches above were checked independently,
not inferred solely from upstream:
[ActivityManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java),
[ActivityThread](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/app/ActivityThread.java).

## Consequences

- This audit preceded the user's request to prioritize functional implementation.
  The later 2.2.23 source adds explicit experimental NFC provisioning endpoints;
  this does not turn the known race into an exclusive production transaction.
  See the current prototype in [the app record](../../docs/SHIZUKU_PROVISIONING.md).
- Local single-record plans and selective rollback preserve **observed** foreign
  changes; they cannot promise to preserve changes made after that observation.
- A supervised synthetic-identity experiment, if separately accepted by the
  user, must disclose that remaining risk. New complete observations, exact
  read-back, durable recovery records and an idle phone reduce risk but do not
  create atomicity. Approval to develop it is not permission to write the phone.
- Do not automatically reload after an uncertain restore, retry it, or replay a
  stale whole-table rollback. Killing the local `bu` command is not confirmation
  that BackupManager stopped its work.

## Restore Transport Repair

The existing `ProbeProcess` wrote stdin synchronously before waiting with a
timeout. A child that did not consume input could block before the timeout was
reached. A separate output-limit error also failed to terminate a waiting child
promptly. Two real subprocess tests reproduced these failures before repair.

Input and output now run concurrently within one monotonic deadline after process
startup. A failed transfer, overflow, timeout or interruption is uncertain; partial
output is not returned as a verified read. Input is bounded to 256 KiB and the
deadline to 1-300 seconds; current callers use at most 90 seconds. Child cleanup
uses bounded waits, preserves caller interruption, and never retries. System-level
backup operation completion still requires its own independent verification.

Nine subprocess cases pass on Windows/JDK 22 and Linux/WSL: binary transfer/EOF,
blocked input, nonterminating child, output overflow, incomplete input, nonzero
exit, invalid limits, preexisting interruption and interruption during transfer.
Child liveness is checked after completion. Full local regression: 317 tests,
no failures/skips; lint 0 errors/13 warnings. This transport revision has not been
installed or tested on the Android phone; no APK was assembled in this step.

## Reproducibility

Saved `services.jar` SHA-256:
`00b899d027d940cdf7c130bab8e3a08cf7cf4fefef464eb476572392551ec799`.

Narrow disassembly is retained in ignored
`build/hcef-write-isolation-20261008/rom`. In its
`com/android/server/am/ActivityManagerService.smali`, the cold-start UID gate is
near line 5467, stopped-state reset at 48818, existing-process lookup at 48970,
app-UID/full-backup gate at 49251 and agent scheduling at 49441. Its SHA-256 is
`c66f96a4937182095ea3340087ee0a73b64ad5c9f6f2ac444d900d588c701d41`.
Earlier saved `BackupAgentConnectionManager.smali` has the core-UID no-kill gate
at line 245. `RegisteredNfcFServicesCache.smali` has `onNfcDisabled` at 2141 and
the registration/identity setters at 2560/2837. These paths and offsets describe
local evidence, not source files intended for distribution.

The two failing pre-repair tests are preserved in the same ignored evidence
directory as `probe-process-regression-before.xml`. Do not commit ROM binaries,
disassembly or private snapshot/restore artifacts.
