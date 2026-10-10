# Original Identity Entry-Point Audit

Date: 2026-10-08. Device: Xiaomi 15 Pro, Android 16 / HyperOS
OS3.0.308.0.WOBCNXM, ST54L. This is an audit of this ROM, not a universal
impossibility claim. The original-account goal remains open.

## Evidence

The installed `/system/app/Nfc_st/Nfc_st.apk` and locally reviewed APK matched
SHA-256 `6F6E8FF4C3DBB447200B78A8CF8A1843DD6841D11E404F3EAE05F22FE683A62F`.
Relevant classes were disassembled locally, including the NFC-F Binder service,
registered-service/cache managers, foreground dispatcher, shell command handler,
backup agent and Xiaomi RF settings activity. These generated files are private
build artifacts and are not redistributed as project source.

A separate read-only tool also ran as shell UID 2000 on the same boot as the RF
test. Its DEX SHA-256 was
`FF38518737461A28290A0FF4C30F646F94DA487CEDA7837FE26A2D3C2A1866AF`.
It enumerated method signatures, ran local format validators and called getters
only. It never called setters, service enable/disable, vendor callbacks or NCI.

Observed results:

- The runtime NFC-F interface exposed nine methods, matching the reviewed
  service. There was no additional identity-registration method in that interface.
- Local `isValidNfcid2`: synthetic `02FE6C6162323031` accepted,
  synthetic `012E6C6162323031` rejected. These are local class checks, not a
  registration attempt or proof of code running in another process.
- Local `isValidSystemCode`: `4000` accepted, `88B4` and `40FF` rejected.
- `getNfcFServices(0)` found Lab UID 10394 with the expected synthetic settings;
  other applications' identifiers were not printed by the tool.
- Individual Lab NFCID2/System Code getters returned null to shell. NFC service
  logs independently recorded `UID mismatch` for both getters.
- NFC state was on and maximum T3T registrations was 16. This does not establish
  that 16 foreground HCE-F services can operate simultaneously.
- Before/after registered HCE-F services, T3T cache and routing sections were
  identical. No foreground Lab route was active. NFC/HAL PIDs were unchanged.

## Entry-Point Findings

| Candidate | Evidence and consequence |
| --- | --- |
| Direct `INfcFCardEmulation` setter | The Binder service passes `Binder.getCallingUid()` to the cache. The cache checks service ownership, then format, before persisting. Shell cannot supply another UID argument to this Binder method. Even a matching owner still faces format validation. |
| Public Java `NfcService.registerT3tIdentifier` | Internal service method assembling bytes and posting a handler message, not a method exposed by the inspected adapter or NFC-F Binder interfaces. Loading its class in another process does not access the running service singleton. |
| Extras/vendor Binder | Both getters return null in this APK. This does not rule out separate ST services, but is not a usable alternate identity path. |
| `cmd nfc` | Four non-root commands: help/status/enable/disable. Remaining commands require UID 0; the complete inspected dispatcher has no NFC-F identity-registration command. HCE-A AID-group commands do not create NFC-F mappings. |
| Persisted dynamic record | Restore reads `files/dynamic_systemcode_nfcid2.xml`; cache reconstruction checks component/UID and applies saved values without repeating the setter's format check. This explains provision-once reuse, not a writable shell API. Shell directory/stat checks returned Permission denied. No file contents were accessed or changed. |
| Backup agent | The inspected key/value helper registers only two preference files, not the dynamic NFC-F file. No backup/restore operation was attempted; other restore transports were not audited. |
| Xiaomi RF settings Activity | The inspected activity reads RF tuning/configuration views, not an NFC-F registration interface. Its presence/export status alone is not proof of arbitrary card personalization. No activity was launched. |
| `notifyTestHceData` | The inspected wrapper forwards synthetic data into host dispatch. It neither registers the ID nor proves RF activation/delivery. It was not invoked, and is not used to manufacture an RF success. |

## Dispatch Boundary

This APK's `HostNfcFEmulationManager` extracts the request NFCID2, resolves it in
the foreground cache, and only falls back to an already active service. Without
a resolved or active service it returns. Deactivation clears that active state.
The foreground cache is keyed by the registered service's NFCID2.

Thus the actual ROM confirms the Java mapping obstacle; a controller-only ID
change does not add that mapping. The native constraint initially had only pinned
AOSP evidence; the following offline audit now adds evidence from the library
embedded in the saved device APK. This is still not a live-memory inspection.

## Native Library Follow-Up

The saved APK contains `lib/arm64-v8a/libstnfc_nci_jni.so`; the reviewed
`NativeNfcManager.loadLibrary()` uses `stnfc_nci_jni`. The extracted ELF64 AArch64
library is 3,014,080 bytes, SHA-256
`7367D5CB9F810E189C874ACDEAC7BFBECCFC4E7063E51932400AA1C556C653F3`,
build ID `941fe1051a6e5ef8eee4511a0d213e6e`. NDK LLVM tools were used locally,
without executing the library or accessing the phone.

Targeted disassembly and relocation checks establish the following for this file:

| Location (ELF virtual address) | Observed behavior |
| --- | --- |
| `RoutingManager::registerT3tIdentifier`, `0x18c590` | Requires an 18-byte input and splits it into two-byte SC, eight-byte NFCID2 and eight-byte PMm before calling `NFA_CeRegisterFelicaSystemCodeOnDH`. No NFCID2-prefix test appears before that call. |
| `NFA_CeRegisterFelicaSystemCodeOnDH`, `0x1b6e1c` | Copies the eight-byte NFCID2 into an internal registration message and posts it to the stack. This is an in-process function, not a shell/Binder entry point. |
| `nfa_ce_api_reg_listen`, `0x1bdedc` branch | Stores the message's NFCID2 in the selected host-listener entry at relative offset `0x1c`; SC is stored at `0x24`. |
| `nfa_ce_activate_ntf`, `0x1bcc28` | For protocol 3, checks in-use T3T entries and compares the full eight-byte activated NFCID2 against each registered NFCID2. The first entry's compare is at `0x1bcf04`; matches set the activation-pending flag. |
| Activation selection, `0x1bd0c8` onward | Selects a matching host entry or the separate off-host entry. Without either it takes the no-listener path, not an arbitrary host-service fallback. |

The relocation at `0x2d1d80` resolves to `nfa_ce_cb`, connecting the observed
loads/stores to the same stack control block. The listener stride is `0x30`:
the first stored NFCID2 is at control-block offset `0x12c`, followed by offsets
`0x15c`, `0x18c`, `0x1bc` and `0x1ec`. Activation compares these as 64-bit values
against the copied activation NFCID2. The host match does not compare SC, which
is consistent with the observed SC-only experiment, but is not by itself an RF test.
These offsets identify evidence in this exact file; they are not patch instructions
or a portable memory layout.

This narrows the next step: original NFCID2 needs a registration path that updates
both native host listeners and the Java foreground mapping. The reviewed native
registration code does not itself show the Java 02FE-only admission check, so
the finding is not evidence that the hardware cannot represent an original IDm.
Loading this library in a shell process would create that process's state, not
modify the NFC service's existing listener table. The off-host branch is not an
entry to the app's `HostNfcFService` and does not establish an eSE provisioning API.

Limits: this is targeted static analysis of the library embedded in a previously
hash-matched device APK. The currently loaded native-library path/hash and any
runtime modifications were not rechecked; no unmatched-IDm RF experiment was run.
Other ST services and the rest of the binary were not exhaustively audited.
Private extraction and disassembly are in `build/hcef-identity-audit-20261008/native`.

## Backend Implications

1. The app owns legal service registration, foreground enable/disable, identity
   validation and block responses. Do not proxy its setters through shell.
2. The limited shell backend handles only the verified controller transition and
   recovery. It must not claim original-IDm support, or expose arbitrary commands.
3. Capability reporting must distinguish format acceptance, saved registration,
   foreground route, observed RF identity, successful reads and account identity.
   A null shell getter is not proof that an app's saved registration is absent.
4. Keep real-card identity/account validation as an open gate. A subsequent
   user-confirmed persisted-file restore established synthetic 012E/88B4 mapping
   and real RF/blocks, with read-only Root snapshots and a verified rollback.
   See [the controlled device record](PROVISIONING.md); it is not a normal setter
   or a proven rootless snapshot mechanism. Keep the existing provision-once
   option separate; do not silently replace the original identity with 02FE.
5. Independent recovery now has a desktop-tested synthetic-session prototype;
   Android lifecycle validation and a bounded app IPC contract remain open before
   integrating the shell path into production. Account and unmodified-cabinet
   verification still cannot be replaced by synthetic RF or recovery tests.

Local evidence: `build/hcef-identity-audit-20261008`. Temporary device DEX removed;
no persistent audit service, permission grant, Root use or production-app change.
