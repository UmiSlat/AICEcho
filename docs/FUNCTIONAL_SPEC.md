# Functional specification

This document describes observable behavior. It intentionally avoids prescribing
the structure of any earlier implementation.

## Card profiles

A profile contains a stable identifier, a user-visible label, a 16-hex-digit IDm,
an optional 20-digit printed Access Code, and optional 16-byte SPAD0 and ID-block captures. Profiles and the selected profile
survive process restarts. Deleting or reordering profiles must not select a different
profile accidentally.

The Access Code is display-only local metadata and does not alter HCE-F registration
or the emulated card image. Supported physical-card capture may fill it into the
editor, but it is stored only after the user explicitly saves the profile.

Each profile exposes an explicit, scrollable Details dialog. Ordinary mode shows
the name and Access Code; advanced mode additionally shows the stable profile
identifier, original IDm, SPAD0, ID block, System Code,
and PMm. Values are selectable. The list/status visibility switches do not redact
this deliberately opened dialog; advanced details warn about sensitive card data.

## Ordinary and advanced UI

Advanced mode is a persistent, device-local Settings switch, off by default.
Ordinary mode keeps card management, backup/import, the default-wallet controls,
and the optional reader compatibility fix. It hides raw identifiers, data blocks,
and detailed diagnostic output. Neither UI mode offers alternate NFCID2 routes.
Enabling or disabling this UI mode does not activate NFC, change the selection,
change emulation-method/patch preferences, or remove any card data.

Version 3.0 exposes an emulation-method selector separately from advanced mode.
Fresh installations default to saved-identity/Shizuku operation; upgrades preserve
the existing method, card selection and raw captures. Saved-identity mode has
explicit Start emulation and Set up card commands on Cards, not duplicated on
Status. Status keeps method selection, device checks and wallet controls.
Page sections are unframed; card containers are reserved for repeated profiles.
Its MainActivity neither
registers NFC identities nor automatically inspects the Root PMm module. The
module route retains its existing foreground registration behavior.

The setup page shows the relevant manual provisioning steps. Technical identity
fields, baseline source, CE/DE destination and Lab/snapshot diagnostics require
advanced mode and appear directly in one flat diagnostics section, without a
second disclosure control. Ordinary setup shows the current card and next manual
step, not the whole list of inactive actions. Optional settled-plan recovery is
advanced-only; required unfinished-plan recovery remains visible in ordinary mode. A
changed card or original IDm disables write/activation for an older prepared identity.
Reopening a settled plan requires explicit confirmation and a fresh table export;
it does not submit a restore or reload. Unresolved same-boot operations stay
blocked, and preparing a new plan replaces the single retained plan.

Saved-identity emulation uses a separate same-UID foreground activity. It checks
the expected IDm/88B4 mapping, enables the existing main-process card service,
and releases it on pause. Leaving the session recycles only the session process;
rotation does not. A mismatch provides a return-to-setup action, never an automatic
identity write. Code 49 passed both post-reload activations and RF in the single
Xiaomi/CE cycle; other devices and interrupted-session recovery remain unverified.

The device-check page remains available without a selected card in ordinary mode.
It shows hardware, declared emulation support, current-card registration, Shizuku
connection and a readiness result that does not claim RF success. API level,
backup service/agent details, runtime-field inspection and full-backup probes are
advanced-only. Entering or switching UI mode never runs these explicit probes.

The ordinary editor shows name and Access Code. Manual creation still requires
IDm because Access Code cannot provide it; editing an existing profile or saving
an NFC-F capture hides but preserves the IDm and raw blocks. Advanced editing
shows every field. Validation, explicit save and cancel retain their behavior.
The reader result is scrollable, with raw NFC-F captures or MIFARE UID visible
only in advanced mode. All captured result fields are returned in either mode.
Missing raw blocks remain missing rather than being fabricated. UI mode is not
part of the card backup format, and hiding parameters is not encryption.

## Bottom navigation

The Cards/Status/Settings navigation uses its original height and preserves only
the reported system-bar and cutout safe area. The extra 80dp rotation-suggestion
clearance has been withdrawn at the user's request. Repeated insets and navigation
mode changes do not accumulate padding. The editor keyboard fix remains separate.
No rotation setting is changed; floating system rotation suggestions may still
overlap the original layout and remain a device-specific verification item.

## Backup and import

The Settings page exports all profiles as an unencrypted, versioned JSON document
through Android's Storage Access Framework. The document includes the fixed system
code and PMm metadata, export time, selected-profile identifier, and a SHA-256
integrity value calculated over a canonical representation of every field. The
checksum detects accidental damage; it is not authentication or encryption.

Import accepts at most 512 KiB and 256 profiles. It rejects malformed UTF-8/JSON,
unsupported versions, invalid field lengths, duplicate stable profile identifiers,
an invalid selected-profile reference, and an integrity mismatch before changing
local storage. The preview offers these explicit operations:

- Merge keeps local ordering, replaces records with the same stable identifier,
  and appends new records.
- Replace removes every local profile and installs only the imported records.

Replace never restores the backed-up selection automatically. Merge preserves the
current selection only when that record was not changed by the import. Otherwise
the foreground HCE-F service is disabled and the user must select a profile again.

NFCID2 always uses the original profile IDm. There is no generated, fixed or
prefix-substitution compatibility route, selector, or identity-retirement notice.
Obsolete identity-mode preferences are ignored without changing profiles,
selection or the Shizuku/module method. Old system registrations are not rewritten
on upgrade; a mismatched saved identity requires explicit setup. Internal public-API
initialization may still use the legal 4000/02FE placeholder when a registration
is missing, before provisioning the original IDm/88B4. It is not an emulation mode.
See [card-image generation](CARD_IMAGE.md).

The system code is `88B4`. HCE-F service metadata statically declares the advertised
PMm as `00F1000000014300`; vendor behavior still requires external verification.

## HCE-F protocol

The service validates frame length, service indexes and block-list elements.
Read Without Encryption (`06`) on service `000B` returns `07` with the request
NFCID2, status, block count and 16-byte blocks. Missing blocks return an error.
Writes (`08`) return `09` with read-only failure `01/A8`; no data is modified.
Unsupported commands and malformed frames are ignored. Without a selected profile
the service returns no application response, rather than a built-in identity.

The image preserves captured `00/82` exactly. Missing SPAD0 defaults to zeros;
missing ID block defaults to the original profile IDm and zero suffix.
Device metadata `83` contains the original profile IDm and configured PMm; `85`
contains the configured System Code; `88` declares the implemented read-only
policy. Scratch defaults, REG and service metadata use documented defaults.
Unimplemented key/authentication/counter/state blocks are not fabricated.
The image cache follows the full profile so card selection or edits refresh metadata.
The new image is not a complete physical-card implementation and has not yet
passed reader/cabinet regression tests.

The service uses Android-compatible static placeholder routing metadata. The
module route dynamically registers the selected NFCID2 and `88B4`; the Shizuku
route enables a separately provisioned exact mapping without setters. Changing
the identifier requires provisioning that new combination before it can activate.

## Physical-card reading

The single reader flow enables both NFC-F and NFC-A and dispatches by detected
card technology.

For every NFC-F card, including Amusement IC, the reader captures IDm and system
code and tries to read blocks `00` and `82` from service `000B` in one command.
If combined reading fails, it reads the blocks separately. SPAD0 and the ID block
are preserved as raw bytes; no SPAD0 decryption, substitution table, or
IDm/PMm-based Amusement IC classification is included. Missing blocks do not
prevent creating an IDm-only profile.

The result screen offers an explicit Use in card profile action. The editor
receives only the captured fields; a FeliCa Access Code must be entered manually
from the card or left empty. Existing profile and backup Access Codes remain
unchanged. Entering an Access Code never synthesizes SPAD0 or another card field.

For a legacy NFC-A MIFARE Classic Aime card, it authenticates sector 0 with the
Aime key as Key B and reads the packed-decimal Access Code from block 2. Since
this card type does not supply a FeliCa IDm, one must be entered before saving.

Opening the editor does not persist anything. Profile storage changes only after
the user presses Save and all fields pass validation. Reading and profile
creation never modify the physical card.

The card editor fits the currently available window rather than fixing its
height to the display. Title, description and fields share a scrollable area;
Save/Cancel remain outside it. API 30+ reserves system-bar, cutout and IME space
once at the outer dialog container and disables window panning/resizing.
API 29 uses window-managed fitting/resizing without adding IME padding again.
The expanded editing sheet is not draggable, so form scrolling cannot collapse
it while the keyboard is open.

## Android service registration

The UI updates the in-process profile selection before activation and persists it
asynchronously. Activation disables the foreground HCE-F service, registers NFCID2
and system code, then enables the service. Registration failures report their exact
stage and restore the previous selection without corrupting the stored profile list.
The selected profile is reactivated when the activity returns to the foreground.
If the NFC Binder dies while the NFC process is restarting, the UI waits and retries
for a bounded interval instead of crashing or reporting an immediate permanent failure.

## Framework hooks

The LSPosed entry point uses libxposed API 101 and is scoped only to
`com.android.nfc`. It accepts hexadecimal NFCID2 and system-code strings of the
expected lengths while rejecting reserved system codes. On Android 14 and older,
the NFC application loads `libpmm.so` only when the legacy PMm property is enabled.

The native hook replaces PMm with `00 F1 00 00 00 01 43 00`. Legacy systems hook
`nfa_dm_check_set_config`. The ST HAL path modifies only CORE_SET_CONFIG parameters
encoded as `51 08 <PMm>` or `40 12 <system-code><IDm><PMm>`.
These hooks are compatibility fallbacks for vendor stacks that ignore or replace
the standard HCE-F `t3tPmm-filter`, rather than a requirement of the card image.

## KernelSU lifecycle

The module stores user intent separately from runtime status, serializes injection,
injects at most once per observed HAL PID, and reinjects after an external HAL
restart. Enabling sets a runtime vendor property and loads the hook when needed;
disabling clears that property so subsequent configurations pass through unchanged.
The switch does not restart the HAL or NFC framework service. Commands emit
machine-readable status fields.
