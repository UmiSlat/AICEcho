# Shizuku NFC Experiment Integration

Current source now supports original-IDm emulation only (2026-10-09). Generated
and suffix compatibility routes and their migration UI have been removed.
Preparation and activation use the selected card's original IDm; the legal
4000/02FE registration initializer remains an internal prerequisite when needed.
This cleanup was installed in code 49 on 2026-10-10; earlier 02FE device cycles
below are history, not an alternate-identity feature of the current source.

## AOSP Backend Update (2026-10-09)

Current source replaces `MiuiNfcExporter` with `AospNfcExporter`, using only
standard `bu backup` and `bu restore`. The backend change used UserService version 6. Device
capabilities are available outside advanced mode and without a selected card.
Package version/SDK/public certificates come from the current device, not a
fixed Xiaomi allowlist. No OEM fallback remains in the application.

The earlier APK and device results below describe the previous backend. They
are historical evidence, not validation of the new AOSP export. In particular,
an NFC key/value-only agent normally does not export the identity XML via AOSP.
An explicit export probe is allowed when standard entry prerequisites pass;
the file route stays blocked for that agent until complete XML is observed.
That route takes a fresh full snapshot before writing and after restoring.
Runtime reconstruction is now a separate local-source route, described below.
Standard backup may introduce additional system confirmation screens.
See [current behavior and acceptance checks](AOSP_CAPABILITIES.md).

Device probe on 2026-10-09: `3.0.0-aosp-probe` / code 46 was installed over
3.0.0 with the same debug signer on Xiaomi 15 Pro, Android 16,
`OS3.0.308.0.WOBCNXM`. Shizuku shell and BackupManager were available, but the
NFC agent declared key/value backup. One user-confirmed standard export finished
without complete identity XML. The full-file baseline route was blocked on this ROM.
Card preferences, HCE-F registrations and the default wallet were unchanged;
no restore, explicit NFC reload or RF test followed. This result does not
establish compatibility or incompatibility of other ROMs.

The next local source iteration used UserService version 7 and added an explicit,
read-only runtime inventory to the capability page. It tries structured
`getNfcFServices(0)` first, then a bounded `dumpsys nfc` fallback. When dynamic
fields can be distinguished, it validates an in-memory XML preview of the
observed user-0 overrides. This is not a complete disk snapshot, does not alter
provisioning eligibility and cannot submit a restore. The installed code-46 APK
lacks this entry and the subsequent implementation below.

The runtime-provisioning implementation used UserService version 8. Card preparation separately checks
the full user list, installed service candidates, dynamic-field availability and
two stable structured reads. Runtime reconstruction currently supports Android
14+ and a single unlocked foreground owner user only; other profiles/users and
incomplete caches block it. A full-file export remains a fallback on eligible
devices. Text dumps are inspection-only, never a minimal-table write fallback.

Plans retain FILE or RUNTIME provenance. FILE comparisons remain byte-exact;
RUNTIME comparisons use canonical user/component records while retaining missing
attributes versus NULL. A normal runtime restore completion becomes
AWAITING_RELOAD, not VERIFIED. Explicit reload obtains fresh interfaces and checks
the reconstructed table before permitting activation. Cancellation is detected
only at that readback; no automatic restore, reload or retry is added.

Plan-store version 2 retains phase, direction and the actual merged rollback
candidate. A fixed rollback callback must retain the actual candidate in app
storage before restore submission; a failed acknowledgement prevents submission.
Intent is saved before write/reload IPC and settled checkpoints before clearing
the pending journal. A fresh session blocks new preparation until unresolved
saved progress is resumed. Reopening AWAITING_RELOAD cannot become PREPARED merely
because the old cache is visible. Lost completions remain UNKNOWN in the same or
an unknown boot; a service-verified new boot permits explicit read-only resolution,
never automatic replay. Legacy version-1 plans remain FILE plans. These source
changes initially received local validation only. Code 47 was subsequently built
and installed over code 46 with the same debug signer. Its explicit runtime
inspection read two user-0 services, including separate dynamic fields, and
validated the in-memory XML preview. Shell `pm list users` also confirmed a
running XSpace user 999, outside the current single-user reconstruction scope.
No prepare, backup, restore, NFC reload or RF test followed. Card preferences,
existing NFC registrations and the default wallet were unchanged. See
[the package and read-only result](AOSP_CAPABILITIES.md) for evidence boundaries.

The user subsequently removed the app clone, and a fresh user listing contained
only user 0. With the same code-47 APK and a shell-started Shizuku service,
explicit card preparation passed the full runtime baseline checks and returned
RUNTIME / ALREADY_PRESENT. The selected identity was already registered, so no
new restore plan or write was needed. Card preferences, the previous saved plan,
NFC registrations and the default wallet were unchanged; only the settled
preparation journal changed. No backup, restore, registration, NFC reload,
emulation activation or RF test was performed. This validates baseline capture
and preparation on this device, not a new-identity restore cycle or multi-user
support.

Current source (2026-10-10) uses UserService version 9 and binds every snapshot,
plan and selective rollback to a single CE (`f`) or DE (`d_f`) identity destination.
File observations retain the archive's actual domain; runtime observations infer
it from the NFC package's default Context data directory and require it to stay
stable. Unknown default directories may fall back to an eligible full-file export,
not a guessed CE target. This assumes the NFC cache uses the default package
Context; vendor-specific alternate contexts/file layouts need separate validation.
Both-domain exports are ambiguous and rejected; restores never write both domains.
Plan store v3 and snapshot store v2 retain this domain. Older plans/snapshots are
read as historical CE records. Fresh observations must match the prepared domain,
including after runtime reload. Advanced configuration shows the domain; ordinary
configuration remains unchanged. Local tests are not device/RF evidence, and code
48 was built before this change.

Code 49 (`3.0.0-storage-domain-probe`) was subsequently built and installed over
code 47 with the same debug signer. Installation preserved all captured app
files, NFC registrations and the default wallet. An explicit read-only prepare
returned RUNTIME / ALREADY_PRESENT / CE; only the settled preparation journal
changed. The default package directory also reports CE, but no disk identity
file was read. That initial read-only phase performed no restore, registration,
NFC reload, activation or RF test. The separately authorized cycle below subsequently
validated new-identity restoration on CE; DE devices remain unverified.
Package details and private-evidence boundaries are in AOSP_CAPABILITIES.md.

## AOSP Runtime CE Device Cycle (2026-10-10, Code 49)

The installed code-49 APK completed original -> independent synthetic -> original
provisioning on Xiaomi 15 Pro / Android 16 / HyperOS, with only unlocked user 0.
The synthetic profile used IDm `012E4C4142343931`, no account data and no original
card blocks. It did not reintroduce a compatibility-IDm feature.

- Preparation used complete structured runtime reconstruction, RUNTIME provenance,
  CE destination and plan store v3. Standard shell `bu restore -user 0` returned
  AWAITING_RELOAD / FORWARD after the user's explicit Android confirmation.
- A separately approved NFC reload changed PID 10576 to 11696. Fresh structured
  readback matched the complete proposed registration table semantically and the
  CE domain, resulting in RELOADED / FORWARD. HINATA Lite FFFF/88B4 polling,
  expected IDm, PMm 00F1000000014300, SC 88B4 and blocks 00/82/85 passed.
- The retained in-session rollback saved the actual observed/replacement table
  before submission. Its subsequent, separately approved reload changed PID
  11696 to 16409; complete runtime verification passed as RELOADED / ROLLBACK.
  This was a real rollback, unlike the fresh forward plan used in the historical
  2.2.23 cycle. A second Android confirmation page was not independently observed;
  success is established by readback and RF, not assumed UI behavior.
- The original profile was reselected, the synthetic profile deleted, and the
  ordinary Start emulation entry passed the original HINATA polling and all three
  blocks. Both post-reload activations used the dedicated `:nfc_session` entry
  without restarting the whole app or repeating restore to repair a stale Binder.
- Final original profiles/selection, other HCE-F registrations and default wallet
  matched the initial state. Emulation was stopped, Shizuku mode intentionally
  retained, and a final read-only prepare returned ALREADY_PRESENT / CE. The
  settled rollback plan remains stored with no pending operation.

Independent forward and rollback readback checks passed (7 and 9); final state
and two RF reports passed 56 checks. These are device-evidence checks, not new
unit tests. Private reports and captures remain in ignored
`build/storage-domain-probe-20261010`; original identities are not published.
No application source changed or new APK was built during this cycle.

Baseline capture, restore and reload ran via shell UID 2000 without Xiaomi export
or Root NFC writes. The initial module-mode navigation included its existing
registration attempt and Root status entry before switching to Shizuku, so this
is not a whole-session no-Root-call audit. The phone remains rooted with LSPosed
installed; no hooks/modules were changed, no cold reboot or game login occurred.
Clean unrooted deployment, DE, other ROMs, account acceptance and process-death
plan recovery remain separate acceptance work.

## Scope

The requested end state is Aime emulation without Root, or with reduced
privileges, including reliable original-card identity and account selection.
This integration is a prerequisite, not that end state.

Version 3.0 uses one state-driven primary action on the ordinary card page,
without a duplicate on Status. Live matching registration starts daily emulation
without Shizuku; a mismatch opens **Set up and use**, and unfinished operations
offer **Continue setup**. Selection alone never rewrites a saved identity.
The ordinary setup wizard shows Check / Apply / Load and check / Done. It binds
an already authorized, running shell Shizuku server automatically on entry;
permission, restore and reload remain explicitly requested. A user-issued setup
request performs checks and any necessary legal initial registration, but never
automatically applies a restore or reload. Detailed developer controls, including
the owned-marker byte-for-byte restore probe, remain in Advanced mode.
Current source includes AOSP NFC snapshot export, current-card provisioning,
read-back and NFC reload. It reuses `PersistedHceRouteWorkflow` for activation and
does not change default-wallet settings. On 2026-10-09, build 2.2.23 completed an
original-IDm -> prefix-02FE -> original-IDm device/RF cycle, with an app-process
restart workaround after NFC reload. The code-49 cycle above subsequently passed
CE runtime provisioning and both post-reload dedicated-process activations without
that whole-app restart. Account acceptance and broader reconnection/lifecycle cases
remain unverified. The original
Lab, snapshot controls, baseline source and CE/DE details require Advanced mode,
which directly shows a flat diagnostics section rather than another disclosure.
Ordinary device checks retain basic results; runtime and full-backup probes are
advanced-only. This UI cleanup follows the code-49 device cycle and was packaged
in the 3.0.1 release, but has not yet been installed on the test phone; historical
screenshots do not validate the new layout. Release provenance is recorded in
[the acceptance record](3.0_ACCEPTANCE.md#301-发行基线).

## Earlier 3.0 Acceptance Build (Before the AOSP Backend Update)

The source is version 3.0.0 (45). Fresh installs default to saved-identity/Shizuku
mode; existing installations retain their method and profiles. MainActivity in
this mode does not activate/register identities or automatically inspect Root PMm
state. Both the daily Start emulation command and the setup flow use the private
foreground session activity. Missing mappings lead back to setup without setters.
Module mode retains the earlier activation and optional PMm implementation.

Settled sessions can now be recovered explicitly with **Resume previous plan**.
The private version-1 record is bounded and validated against the owning UID,
both XML hashes and the reconstructed own-service-only proposal. A new session
token is issued, then UserService API 13 (version 5) exports current state again:
the exact original becomes PREPARED, the exact proposal becomes VERIFIED, an
already reverted own record becomes ROLLED_BACK, and other changes become
CONFLICT. Selective rollback still preserves observed external records. Resume
does not restore or reload, and does not declare foreground/RF success.

The operation journal still blocks unresolved same-boot work. This does not
resolve an in-flight system restore or implement an independent recovery daemon.
Only one plan is retained; preparing a new one replaces it, as stated in its
confirmation. Save now validates read-back because AtomicFile can log rename
failure without throwing. Tests model POSIX rename-over-existing on Windows;
no host-specific workaround is added to application storage code.

Refreshing an empty rebuilt UserService clears stale live activation/reload/
rollback state while retaining the available private plan. Ordinary setup shows
only the relevant steps; raw IDs and Lab/snapshot diagnostics remain advanced.
The selected card must match a prepared identity before write/enable is offered.

Local validation: 395 tests pass with no failures/errors/skips, lint 0 errors/14
warnings, debug APK build and v2 signature verified. Artifact:
`dist/AimeSimulator-3.0.0-preview-debug.apk` (16,809,169 bytes), SHA-256
`CB3306F2FF1F4F7BABA40F79595F70B54158E63AE3EA57DEDC1283193D4E7B1E`.
Its debug signer matches installed 2.2.23. This is not a public stable release.
No new phone operation or installation was performed for this build. Version-3.0
RF, live process/session retention, resumed-plan rollback and original-account
acceptance still require the separately authorized device tests.

Prior PC-driven tests established owned-app CE/DE restoration and a controlled
synthetic non-02FE NFC identity/RF/rollback cycle on one Xiaomi ROM. The latter
used Root for complete baseline reads. Those results do not establish the
Shizuku transport, a rootless fresh baseline, original-account emulation, or
cabinet compatibility. See [the Lab evidence](../tools/hcef-lab/PROVISIONING.md).

## Requirements and Deliberate Limits

- Shizuku API 13+ running as ADB shell (UID 2000). Root-started Shizuku is rejected
  by the client and again at the service boundary. A rooted phone running the
  shell backend still does not constitute a clean rootless-device test.
  Automatic Sui initialization is disabled before provider creation.
- Explicit Shizuku authorization and a separate confirmation for each export or
  restore. Opening the screen, granting permission or reconnecting never starts
  either operation.
- Primary Android user only. Secondary/work-profile UIDs are rejected.
- For the isolated restore only: separately installed `io.github.umislat.restorepathlab` versionCode 1,
  debuggable, allowBackup, exactly one current signer matching public SHA-256
  `3bf1577c690a4a57534afe7338b9534fdba659deedce1e6f3ed408ae750545dc`.
  This pins the previously tested local debug Lab, not a production signing key.
  Other developers' debug builds will fail this check. Do not remove the pin to
  make an unknown package pass; review a replacement fixture and its certificate.
- `dist/RestorePathLab-0.1-debug.apk` is the local tested Lab artifact, not
  distributed inside the main APK. Lab source/build instructions live in
  [restore-lab](../tools/hcef-lab/restore-lab/README.md).
- `/system/bin/bu` and `run-as` must be available and accepted by the ROM. Their
  presence or Shizuku permission alone does not prove restoration works.
- No concurrent backup/restore experiment. Keep the phone unlocked to accept
  Android's own confirmation promptly; observed expiry was about 60 seconds.

## Implementation and Failure Handling

The original Lab AIDL surface exposes capability inspection, a fixed own-app restore
with a random nonce, status and nonce read-back, plus start/status/read methods
for the fixed NFC snapshot export. It has no command, package,
path or archive input. Version 4 adds the restricted current-card NFC provisioning
methods described below; version 5 adds validated saved-plan resumption. Neither
accepts arbitrary target services. Incoming Binder callers must match the owning
application UID. The UserService is not an exported Android service. The SDK
provider is protected by `INTERACT_ACROSS_USERS_FULL` as required by Shizuku.

The isolated Lab archive is a bounded Android Backup v5 zlib stream with three closed USTAR
entries: the owned app manifest and `f/restore_probe.txt` / `d_f/restore_probe.txt`.
The manifest contains the full installed public signer DER, not its hash. No
external tar input is accepted and no archive or marker is written to shared
storage. Binary input goes directly to `bu restore -user 0`; no shell command
string or text pipeline is used.

The client commits the nonce and unresolved intent before dispatch. A ViewModel
survives Activity rotation and the system confirmation taking the foreground.
The service waits for command completion and verifies **both exact markers**.
Exit zero, a confirmation screen, callback time or one matching file is not
success. A command has a 90-second deadline and bounded output. Killing the local
command does not prove cancellation inside BackupManager.

Timeouts, disconnects, process loss and unverified outcomes block another write.
Reconnect can recover the original service result; explicit read-back can prove
that markers match, but cannot by itself prove that the old operation ended.
Known command completion plus both matching markers, or a preflight failure
before dispatch, clears the journal. Otherwise a confirmed device reboot is
required for a new attempt. Missing boot-count information fails closed. No
automatic retry or arbitrary reset-pending button is exposed.

The archive targets only the two independent Lab marker files; the Lab's own
restore callback also records its completion timestamp. A successful test leaves
those synthetic markers in the Lab; its explicit baseline button can reset them
after the result has been recorded. It is not a card-data backup/restore UI.

## Verification Gates

Local checks cover archive scope, pinned public signer, exact CE/DE contents,
preflight rejection, cancelled zero-exit restores, timeouts, dispatch errors,
pending-journal persistence, capability gating and screen recreation without
implicit authorization or restoration. These are not Android Binder/ROM/RF tests.

Device acceptance must separately verify:

1. ADB-started Shizuku, permission denial/grant, service UID 2000, pinned Lab.
2. Explicit test confirmation and Android confirmation, exact fresh nonce in
   both Lab domains; no system NFC/wallet changes.
3. Cancellation, expiry, rotation, service/app death and reconnect behavior.
4. No repeated write after an unresolved operation, including app relaunch.

Debug builds alone expose the explicit Activity alias `.ShizukuRestoreLabEntry`.
It opens this page without going through MainActivity's normal NFC activation;
it accepts no restore input and opening it neither binds nor restores. Release
builds have no alias and keep the Activity non-exported. Normal navigation back
to MainActivity resumes its existing selected-card activation behavior, so device
tests should exit to the Android home screen when preserving the NFC baseline.

## Device Result, 2026-10-08

The dated sections below preserve the implementation and results at those older
versions. References to pinned ROMs, Xiaomi export, or unfinished steps are
historical; the AOSP backend update at the top describes current source.

On Xiaomi 15 Pro / Android 16, the user authorized updating the existing debug
installation with the same signer (no uninstall or card migration was performed
by this experiment). Local build `2.2.20-shizuku-probe`, versionCode 40, passed
219 tests and APK verification; lint reported zero errors and 11 warnings.
The version override is local to this test artifact, not a stable release bump.

Official Shizuku 13.6.0 was installed and its packaged starter launched via USB
ADB. The server and app UserService both ran as **shell UID 2000**. No `su` was
used. Opening the debug alias did not activate the selected NFC card. The screen
reported the expected signed Lab and `bu` as available, and the NFC identity file
as unreadable, confirming the direct-file-access limitation. The later vendor
backup experiment reads through a different mechanism.

The user initiated the in-app test and accepted Android's restore confirmation.
The app reported VERIFIED. Independent binary `run-as` reads confirmed both
fresh nonce markers exactly (CE 44 bytes, DE 46 bytes), and the persisted pending
flag was false. Original Aime card and appearance preference hashes matched the
pre-install snapshot. The NFC process remained PID 7657 and the default wallet
component was unchanged. No NFC file restore, NFC reload or raw NCI command was
issued. NFC private files were not read through Root to strengthen this claim.

Evidence is retained in ignored `build/shizuku-integration-20261008`, including
`verified-result.json`, marker bytes and screen captures. It also contains the
original private card preferences and must not be uploaded. Local APK:
`dist/AimeSimulator-2.2.20-shizuku-probe-debug.apk`, SHA-256
`0354A88ADDF8BFE7E077E4C1A099F7535252D5817347262A78C0F0133FD22AA0`.

This establishes **phone-side owned-app restore after Shizuku startup**, not
computer-free Shizuku wireless pairing/startup, clean unrooted deployment, NFC
identity provisioning or original-account emulation. Device cancellation,
expiry, process-death and reconnect cases remain untested in this integration;
their local tests must not be counted as device evidence.

## Xiaomi Snapshot Candidate, 2026-10-08

After separate user authorization, a standalone shell UID 2000 probe used the
ROM's `MiuiBackup` session to export the owned Restore Path Lab. The actual ROM
has an `isForceAllowBackup` exception at the key/value-only filtering branch;
the standard AOSP backup limitation must not be generalized to this vendor path.

The 6236-byte export contains the pinned Lab manifest, exact CE/DE markers and
its preferences. Independent .NET inspection and the bounded Python verifier
confirmed both markers. No Root or system NFC backup/restore/reload was used;
Lab enabled state, NFC PID and default wallet remained unchanged. This probe
is not integrated into the APK. It can stop the target app temporarily and its
vendor session may acknowledge backup internally without the normal ADB dialog.

That first result established an **owned-app snapshot mechanism** only. A later,
separately authorized standalone shell probe also exported the system NFC package
without Root. The complete 396-byte CE global identity XML was present, with two
records and exact historical baseline bytes. NFC state/cache/routing output,
wallet and card preferences were unchanged; only cyclic dump logs differed.
No restore/reload was performed. This proves acquisition on this ROM, not
freshness for a future write or safe concurrent rollback. The production APK
now has a restricted snapshot endpoint with a subsequent successful in-app test.
It still has no NFC provisioning endpoint.
See [the Xiaomi snapshot audit](../tools/hcef-lab/MIUI_SNAPSHOT.md) for scope,
actual-ROM evidence, source hashes and the restricted probe.

## Historical In-App Snapshot Implementation (2.2.21)

The exporter pins shell UID 2000, Android 36, OS3.0.308.0.WOBCNXM and the NFC
package's version, system UID, flags, backup agent, public signer and APK hash.
It also rejects boot/vendor copies shadowing its bundled parser dependencies.
The candidate indicator is not a claim that export has already succeeded.

After the user's explicit in-app confirmation, a bounded pipe calls the reviewed
`MiuiBackup` session for **com.android.nfc only**. The backup call waits up to 90
seconds, reading is bounded to 8 MiB, and session release waits up to 15 seconds.
No archive is written to shared storage. Only a validated, complete CE identity
XML and provenance are atomically stored under `noBackupFilesDir`; unrelated
NFC preferences and the full archive are not persisted. The data is plaintext,
not cryptographically encrypted. Archive manifest certificate bytes are metadata,
not a cryptographic archive signature. Neither archive import nor upload exists.

Commons Compress 1.28.0 parses TAR entries within a closed format. Package,
manifest, domains, sizes, file type, duplicate paths, extensions, padding and XML
structure are checked. No TAR entry is extracted to a filesystem path. Original
identity XML bytes are preserved. Bundled dependency notices remain in the APK.

Export and the isolated restore are mutually exclusive, with separate persisted
nonce/boot journals written before dispatch. Unknown outcomes block both; only
the same completed export result can be retrieved on reconnect, without a new
export. A completed valid capture clears pending only after private storage
succeeds. Failed validation preserves the previous snapshot. A known reboot can
resolve an interrupted session but does not make an old capture current.

The UI shows capture time, record count, byte count and a hash prefix, not raw
identities. A five-minute age window also requires matching boot and fingerprint;
even a recent capture is explicitly **not a recheck of current NFC state**. Deleting
the local snapshot does not reset pending journals or touch NFC. No write path
consumes this cache automatically. An unsupported format or missing baseline
fails closed, without synthesizing a replacement from installed-service caches.

Local validation includes synthetic malformed inputs, actual private export
byte comparison on Android 29/36 test runtimes, storage/expiry and lifecycle gates.
Android-all 16 contains an older Commons library; the parser tests explicitly use
the app's resolved dependency, while Android XML/storage remain under Robolectric.
This classloader setup is not evidence of phone-side compatibility. Subsequent
device installation, context correction, export and state checks are recorded below.

Candidate build `2.2.21-shizuku-snapshot` (local versionCode 41) passes 269 local
tests with no skips, including the opt-in private archive comparison. Debug APK
v2 signing verifies with the same local certificate as the installed 2.2.20 build.
Lint reports zero errors and 13 warnings; the added private-API warning records
the deliberate vendor reflection dependency, and the added preferences warning
suggests KTX syntax for the intentionally synchronous pending-journal commit.
No warning is treated as proof of device compatibility. At that coding stage the
candidate had not been installed and no additional device export was performed.

## In-App Export Device Result, 2026-10-08

The user separately authorized a same-debug-signer update and one in-app NFC
export, with explicit phone-side confirmation. Installing code 41 preserved the
original card/appearance preference hashes and NFC PID, but the export candidate
check failed before any export. A read-only context comparison identified
`Settings.Global.BOOT_COUNT` throwing `SecurityException`: shell UID 2000 cannot
use the supplied Context's `android` attribution when calling Settings. All ROM,
NFC package/agent/flag/UID and signer checks otherwise matched.

`ShellBootCount` now uses only the fixed read-only command
`/system/bin/settings get global boot_count`, with bounded transport and strict
nonnegative integer parsing. No permission, package or version check was loosened.
Normal app-side boot reads still use its own ContentResolver. The UserService
version was incremented so reconnect cannot retain the obsolete implementation.

Build `2.2.22-shizuku-snapshot`, local versionCode 42, passes 272 tests with no
skips, lint 0 errors/13 warnings and APK v2 verification. It was installed with
the same debug signer, without uninstalling or opening MainActivity. APK:
`dist/AimeSimulator-2.2.22-shizuku-snapshot-debug.apk`, SHA-256
`946FAC522FFBD9383E9B90661494760618EA5824B428B03A4A8439DBF3163B1E`.

The user confirmed **one** export on the phone. The application completed export,
parsed the archive, and atomically saved a 923-byte private `nfc-snapshot.json`
with mode 0600 under `no_backup`. Independent binary `run-as` read-back confirmed:

- Complete identity XML: 396 bytes, two records, exact bytes matching the prior
  independent export; SHA-256
  `c0244b70ecc53df8915df2b4ee1d981434166bdfe60ed96ec2383de2726c28e1`.
- Pending journal false; nonce and boot 185 match the stored snapshot. Shizuku
  server and UserService remained shell UID 2000.
- Pre/post state records are identical: NFC PID 7657, package enabled state,
  default wallet and Aime card/appearance preference hashes. Entire NFC dumps
  match after removing exactly two cyclic NFCSNOOP blocks on each side.
- No restore, NFC reload, settings write, raw NCI, Root call, second export or
  isolated-marker restore was performed. The full archive was not persisted;
  its hash differs from the earlier export, while the identity XML bytes match.

Private evidence is in ignored, ACL-restricted
`build/shizuku-snapshot-integration-20261008/device-20261008`, including snapshots,
state/dump comparisons and screenshots. It contains real identity data and must
not be uploaded. Phone-side temporary read-only probe/UI-dump files were removed;
the app's validated private snapshot is retained. This proves phone-side capture
after Shizuku startup, not clean unrooted deployment, autonomous Shizuku startup,
concurrent-write protection, safe NFC provisioning or stable original-account RF.

## Historical Local Provisioning Plan, 2026-10-08

This subsection records the earlier planning-only stage. Legal initialization
was subsequently integrated; current source/domain handling is described above.

`NfcProvisionPlan` now prepares a single-record change from a complete validated
XML baseline and its exact SHA-256. It accepts only the existing AimeHostService
or LabService record, matching primary-user UID and concrete old SC/IDm. The
requested SC is fixed to 88B4; the Lab identity is fixed and synthetic. Aime's
IDm is not restricted to one issuer prefix. All other records, attributes and
record order are preserved semantically. A missing target fails closed; creating
its first legal dynamic registration is a separate, unfinished integration step.

Forward checks require the exact original bytes. Selective rollback requires the
target's entire attribute set to still match the proposed record, not merely its
IDm. It restores original bytes if the full proposed table still matches; otherwise
it merges only the old target into a newly observed complete table, preserving
other services' observed changes, additions and deletions. Changed target owners,
identities or metadata cause rejection. An already-reverted target needs no write.
Each rollback candidate is bound to its exact observed preimage.

`NfcRestoreArchive` builds only manifest plus the fixed CE identity XML, using
the pinned public NFC certificate and ROM package version. It has no arbitrary
destination, DE fallback, preferences restore or device submission. Its output is
round-trip checked through the existing closed snapshot parser. This new manifest
is not identical to the historical Python experiment's manifest; acceptance by the
device restore service has not been tested for this Kotlin builder.

These are **local checks, not an atomic compare-and-swap or write authorization**.
The saved ROM's identity-cache writer holds its own process lock; the inherited
file restore path does not share that lock. An app mutex, journal or NFC-off state
does not prove exclusion of other writers. Another change can occur after capture
or verification and before restore. Selective merging avoids reverting already
observed foreign changes, but does not close that race.

Local validation passes 308 tests, with no failures or skips, and lint reports
0 errors/13 warnings. New cases cover Android 29/36, conflict and rollback behavior,
fixed archives and an opt-in private snapshot comparison using only a synthetic
Lab plan in memory. Independent .NET TAR/zlib inspection also verified the two
synthetic archives. No real-data-derived restore archive was saved by these tests.
No APK was assembled or installed for this step; the installed 2.2.22 remains the
export-only build. No Binder/UI write endpoint or additional phone operation was
added. Snapshot provenance/freshness, live ownership checks and durable operation
recovery still need a reviewed workflow before these local helpers can be used.

## Restore Isolation and Transport Follow-Up

Inspection of the saved ROM's actual ActivityManagerService confirms that the
cold-start restricted-backup branch requires UID >= 10000. NFC UID 1027 does not
qualify. Agent binding also reuses existing processes and clears stopped state;
core UIDs are excluded from normal agent-host termination. Neither a normal
restore nor a prior force-stop has been established as an exclusive write window.
See [the isolation audit](../tools/hcef-lab/WRITE_ISOLATION.md) for evidence and
limits. No such lifecycle experiment was run on the phone.

The binary command transport now includes stdin transfer in its timeout. A real
subprocess test reproduced the old pre-timeout blocked-write bug; another caught
delayed failure on excessive stdout. Concurrent bounded transfer, prompt failure,
child cleanup and interruption handling pass nine Windows and Linux cases. The
full suite now passes 317 cases with no skips; lint remains 0 errors/13 warnings.
No new APK or phone operation followed. An uncertain transport result remains
unknown at the workflow layer, not cancelled, safe to retry, or permission to reload.

## Historical Functional Prototype: 2.2.23

The user redirected development to completing functionality before extended
safety testing. The source now exposes a manual current-card provisioning flow,
not just the isolated Lab and export functions. Earlier sections describe earlier
builds; their statements that no NFC-write endpoint exists do not describe 2.2.23.
The ROM-specific preflight and basic XML/owner checks remain in place, without
waiting for a nonexistent restore-wide lock before implementing the prototype.

1. **Prepare current card:** read a fresh complete identity table, select only
   this app's AimeHostService record and the current card's original IDm,
   and prepare SC 88B4. An already identical record skips restore. A missing or
   placeholder record offers explicit legal 4000/02FE initialization first.
2. **Write prepared identity:** save original/proposed bytes and hashes under
   private `no_backup/nfc-provision-plan.json` before enabling the button. On
   confirmation, re-export/check the preimage, build the fixed CE archive and
   submit `bu restore -user 0`. The user confirms the Android restore screen.
   Another complete export must exactly match the proposed XML. An unchanged
   original is reported as such, not success. No reload follows automatically.
3. **Reload NFC:** a separate confirmation invokes the previously exercised
   shell `am crash --user 0 com.android.nfc` route and waits for a different PID.
   This establishes process replacement, not correct routing or RF behavior.
4. **Enable card emulation:** recheck the selected card/mode and reuse
   `HceSession.activatePersistedRoute`. The 2.2.24 candidate launches a dedicated
   foreground session process; see the local reconnection work below. Only getters and foreground enable run;
   illegal identity setters are not called. The saved-route-only setting is
   enabled before restore so returning to MainActivity cannot overwrite the
   restored mapping through its ordinary registration path.

The same session offers a separately confirmed previous-identity restore using
the selective rollback plan, exact read-back, and a separate reload. Operation
intent is journaled before IPC; connection and permission events never submit a
write. UserService version is now 4. There is no arbitrary package, file path or
command input. This is nevertheless an experimental system write, not an atomic
transaction; the known concurrency limitations remain.

The current recovery file is retained for manual recovery. Automatic rehydration
of a plan after app/UserService death is not yet implemented, so the in-app rollback
button currently requires the live session. Do not confuse persisted original
bytes with a completed crash-recovery feature. No new Android installation,
NFC restore, reload or RF test was performed during this coding step.

Functional validation: 341 tests pass without skips; lint 0 errors/14 warnings.
The additional warning suggests KTX syntax for a synchronous journal commit.
New tests exercise the normal prepare/write/read-back/reload flow, no-op identity,
initial registration, manual rollback, original-plan storage and UI step controls.
Tests use a simulated backend, not the device's restore implementation.

Local debug APK (same signer as installed 2.2.22), versionCode 43:
`dist/AimeSimulator-2.2.23-shizuku-provision-debug.apk`, SHA-256
`3380812D328C8A5B30F8DE8C3B809FCD8846DAE6A874405770DE657349263A70`.
APK v2 signing verifies. The tracked release version was not changed.

## Remaining NFC Work

1. Expand the code-49 foreground-process evidence to repeated sessions and other
   devices. Both post-reload activations and RF/reads passed in the CE cycle above;
   retained-session behavior under interruptions still needs separate testing.
   Do not repeat restore to repair a Binder.
2. Verify the original account on the actual target reader/cabinet. The new
   HINATA RF/three-block evidence below is not game-account acceptance.
3. Validate the new settled-plan recovery and daily card-switching behavior on
   the device. Complete unresolved-operation recovery separately. Validate clean
   unhooked startup and persistence across normal restarts.
4. After the functional path works, expand cancellation, expiry, disconnect,
   process-death, repeated-capture, concurrent-change and rollback testing.
   A current-user Binder service list still is not a complete global-file baseline.

## Device Round Trip, 2026-10-09

The user approved the same-debug-signer update to 2.2.23 (43), current-card
provisioning, a temporary prefix-02FE identity and restoration of the original
IDm. Both Android restore screens were accepted by the user; both NFC reloads
were separately approved. No physical card or default-wallet setting was written.

- Installation preserved the complete preference archive byte for byte. The
  first fresh prepare found the original IDm/88B4 already present and correctly
  skipped restore. Getter-only activation and HINATA Lite RF/reads passed.
- Changing only the routing mode to prefix-02FE produced a real plan. The app
  exported the complete table, saved the original/proposed XML, submitted its
  Kotlin-generated CE archive through the shell UserService and reported exact
  post-restore read-back. NFC PID changed from 7657 to 21059 on explicit reload.
- The same app process then failed activation twice with a DeadObjectException
  from NfcFCardEmulation.disableService, although dumpsys reported NFC on. Only
  restarting AimeSimulator, reconnecting Shizuku and preparing the already-saved
  identity enabled emulation. No second restore or NFC reload was needed.
- HINATA Lite read the expected prefix-02FE RF IDm, 88B4 and PMm
  00F1000000014300. Blocks 00/82/85 matched the original data; block 82 deliberately
  retained the original card IDm. Both FFFF and 88B4 queries passed; 4000 found
  no target.
- The process restart ended the prior UserService session, so returning to the
  original IDm used a **fresh forward plan**, not the in-session rollback button.
  Its original bytes matched the first plan's proposed hash, and its proposed
  full table was semantically identical to the initial original table. The user
  confirmed restore; exact read-back passed. A second approved reload changed
  PID 21059 to 26545; app-only restart avoided the known stale Binder problem.
- Final HINATA FFFF/88B4 scans again returned the original RF IDm and all three
  original blocks. Independent comparisons checked both plans/hashes, completion
  journals, original/prefix/final scans, unchanged profiles/selection/appearance,
  restored ORIGINAL routing mode, and unchanged default-wallet component.

The saved-route-only setting remains enabled. Advanced mode was enabled to
reach the route selector. MainActivity's existing PMm status inspection invokes
its Root status path; the provisioning/export/reload backend itself ran as shell
UID 2000. Thus this establishes the reduced-privilege functional transport on this
already-rooted device, **not** a fresh unrooted installation or a whole-app
no-Root-call audit. Shizuku startup, UI operation and app restarts still involved
desktop ADB. No game login, cold reboot or long-term stability test was performed.

The device's public framework-nfc.jar (SHA-256
99436ceaa6a505381d445ddb6ee822e9ff61fe65e811708acd4de332a327b00b)
was inspected locally. Its NfcFCardEmulation.recoverService calls getInstance
with the cached adapter context, then assigns its existing static sService back
to itself. getInstance returns a cached manager without refreshing that Binder.
This explains why adapter recovery alone did not repair NFC-F activation in the
observed process. It is a pinned-ROM finding, not a claim about all Android builds.

Private evidence and the independent verification scripts/results are in ignored
build/shizuku-provision-device-20261008. They include real identities, preferences,
raw RF logs and vendor framework code: do not publish or commit them. The device
round trip is functional evidence with a known workaround, not final acceptance
of session recovery, in-app rollback, clean rootless deployment or account stability.

## Local Reconnection Candidate, 2.2.24

`PersistedEmulationActivity` runs in the normal same-UID `:nfc_session` process,
is not exported, and receives only the prepared NFCID2 and card label. It uses
the existing getter-only activation path for the fixed AimeHostService/88B4
mapping. It does not load or write card storage. AimeHostService, the parent
provisioning UI and Shizuku ViewModel remain in their existing processes.

Pausing releases foreground emulation. Returning/back or moving the session
fully into the background finishes it; configuration changes do not. Destruction
of the last finishing session recycles only its own PID, guarded by the exact
process name. The next launch therefore avoids retaining this process's framework
NFC-F cache. The parent no longer acquires an NFC-F manager or kills itself just
to activate a prepared identity. Duplicate button taps do not launch two pages.

This is an experimental-entry fix candidate, not automatic recovery after actual
main/UserService death. The ordinary MainActivity activation path is unchanged.
The same-UID foreground activity/service interaction, real process recycling,
retained live UserService plan, and RF after NFC reload still require device tests.
There are no hidden API calls, new Root commands, automatic restores or reloads.

Local validation: 361 tests pass, no failures/errors/skips; lint 0 errors/14
warnings. Twenty additional API29/36 cases cover manifest isolation, getter-only
activation, identity mismatch, invalid input, failed enable, pause/rotation/exit,
process-name/last-activity guards and parent plan retention. Robolectric simulates
framework/process calls; these are not new device or RF evidence.

The local APK is `dist/AimeSimulator-2.2.24-nfc-session-debug.apk`, versionCode44,
SHA-256 `5BC21435085F2C4DCF91B0D8B72F4437D1D92AB1B9DA1648E39CF860913BE41C`.
APK v2 signing verifies with the same debug certificate as installed 2.2.23.
The tracked release version remains unchanged. No installation, phone operation,
identity write or NFC reload was performed during this code-fix step.

## Primary References

- [Shizuku API/UserService and shell limitations](https://github.com/RikkaApps/Shizuku-API)
- [Android 16 `bu` stdin restore](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/cmds/bu/src/com/android/commands/bu/Backup.java)

The Shizuku API/provider dependency is MIT; the full notice is bundled at
`app/src/main/assets/licenses/shizuku-api.txt`. No restore implementation was
copied from the supplied research archive.
