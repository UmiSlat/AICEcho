# Restore Path Lab

An isolated owned application to test inherited full-file restore with a
key/value-only `BackupAgentHelper`. Package: `io.github.umislat.aicecho.restorepathlab`.
It has no NFC, network, shell or Root permission, and no exported restore receiver.
It never reads/writes system NFC, wallet data or another application's files.

The agent registers only `restore_lab` preferences, inherits `onRestoreFile`, and
records its own restore-finished callback time. It declares allowBackup,
restoreAnyVersion and debuggable, but not fullBackupOnly or custom extraction
rules. This models one part of the candidate path, not system-app UID/signature
policy or Xiaomi NFC agent side effects.

The Activity displays a bounded `restore_probe.txt` from its own CE and DE files
directories, plus the callback timestamp. Opening/resuming only reads. Explicit
`Write baseline markers` writes `baseline-ce` and `baseline-de`; do not press it
after restore until the restored markers have been recorded. `Refresh markers`
is read-only. A callback timestamp by itself does not prove file restoration.

## Build and Prepare Offline

PowerShell 7, an existing JDK/Android SDK and the local debug signer are required.
This does not use or create a production signing key. The exported DER is only
the public signing certificate, required because this ordinary app does not get
the system-app signature exemption.

```powershell
./tools/hcef-lab/build-restore-lab.ps1 -OutputDirectory ./build/restore-path-lab-candidate
python -B tools/hcef-lab/marker_archive.py --certificate ./build/restore-path-lab-candidate/signer-public.der --token <32-lowercase-hex-digits> --out ./build/restore-path-lab-candidate/owned-marker.ab
```

Use a fresh output directory. `-Keytool` can explicitly select the JDK keytool;
otherwise the script checks PATH and the active Java runtime home. It does not
modify PATH, keystores, devices or adb authorizations. Public certificate bytes
are exported directly to a file, not through a cross-shell text pipeline.

The fixture generator has no package/path argument. Its three entries are the
fixed own-package manifest and one marker in each of `f`/`d_f`. The signer field
is the full DER certificate, not its SHA-256 digest. The CLI bounds/checks the
certificate shape, not its cryptographic validity; compare the exported
certificate hash to `apksigner verify --print-certs` before a device experiment.
The local tests use a synthetic certificate-shaped fixture for serialization.

## Later Device Verification

No device action has been authorized by creating these artifacts. When the user
permits testing, install only this independent app, explicitly establish/read its
baseline, and verify the package/signer/fixture scope before a user-confirmed
restore. Then reopen/refresh, checking the exact CE and DE nonce values against
the archive, along with logs and callback time. Preserve refusal/cancellation
results too. Never treat the system confirmation or completion log alone as success.

There is no reason to restart or write `com.android.nfc` during this isolated
test. Passing it demonstrates this app's inherited file-restore behavior on that
ROM; it does not establish NFC restore eligibility, full-table preservation,
original IDm, RF, game-account correctness or rootless deployment.

## Local Verification, 2026-10-08

- APK Java/resource/DEX build and v3 signature verification passed.
- Parsed binary manifest confirms the own package, backup agent, flags and no
  requested permissions. Minimum API 30, target API 36, version 0.1/code 1.
- Marker generator tests and the separate NFC archive tests passed (21 methods
  total). Independent .NET tar/zlib parsing confirmed own-package-only entries,
  both marker domains and a manifest signer matching the APK certificate hash.
- `dist/RestorePathLab-0.1-debug.apk` SHA-256:
  `ABBB599C2EBCD1057C0B74978C06625E7A80D766CFAC25D59DA787D8C4252C2C`.
- At the local-build stage no APK was installed. Subsequent device evidence is
  recorded separately below.

## Owned-App Device Verification, 2026-10-08

On Xiaomi 15 Pro / Android 16 / OS3.0.308.0.WOBCNXM, the user authorized testing.
Two USB installs were cancelled before confirmation; the third succeeded after
the user confirmed. Version 0.1 ran as UID 10397. The user pressed the baseline
button, and shell `run-as` reads verified both exact 12-byte baseline files.

The reviewed archive contained only this package's manifest and CE/DE markers,
with the matching APK certificate. The user accepted Android's restore dialog
at 19:35:27 +08:00. Both restored files matched the expected nonce byte-for-byte
(CE 44 bytes, DE 46 bytes). The agent persisted callback epoch 1791459327347;
BackupManager logged signature match, agent connection, process termination and
restore completion. The Activity resumed after the restore. A callback alone
was not used as the acceptance criterion.

This session used no `su`, NFC commands, system-file restore or permission
changes. NFC PID was 19647 before and after. The device is already rooted;
existing hook conditions were not re-audited, so this is not clean-ROM evidence.
Only the owned-app file restore gate passed. System NFC eligibility, agent side
effects, complete identity-table preservation, reload, RF and account identity
remain unverified by this experiment.

Private evidence: `build/restore-path-device-20261008` (fixture verification,
binary pre/post markers, callback XML, filtered restore logs and result JSON).
The marker archive SHA-256 is
`56CD40C8A455A54E74BF1C1F5B85654CE03F43BE46D2D0ED37679E8A1BF9FB34`.
The app remains installed with restored markers; no baseline reset was performed.
