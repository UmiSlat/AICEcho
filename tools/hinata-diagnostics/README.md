# HINATA FeliCa diagnostics

This local WebHID tool separates FeliCa discovery, targeted System Code polling,
and Aime service reads. It also provides a read-only NFC-A/MIFARE comparison for
an emulated card. A loopback-only bridge can forward a manually supplied Access
Code to a local HINATA AimeIO DLL for the `02FE` compatibility experiment. The
tool does not send write, firmware, or configuration commands to the reader.

## Run

1. Close HINATA Client, maimai, and any other application using the reader.
2. Start the localhost diagnostics and relay server:

   ```powershell
   node tools/hinata-diagnostics/relay-server.mjs
   ```

   On Windows, you can also double-click `start-hinata-diagnostics.bat` in this
   directory. Keep its console window open while using the page.

3. Open <http://127.0.0.1:8765/> in desktop Chrome.
4. Select the HINATA USB device and run the full diagnostic while the phone is
   unlocked and the AICEcho HCE-F service is active.
5. Export the JSON report after the run.

WebHID requires a secure context. `localhost` and `127.0.0.1` are treated as
secure contexts by Chrome; opening `index.html` directly is not supported.

## Local game bridge

This is a reader-side proof for the case where Android can expose only an
`02FE` NFCID2 but blocks `012E`. It does not change the Android RF identity.
Instead, the local HINATA DLL receives a V2 AIC record and returns its real
10-byte Access Code through the AimeIO Aime-ID path rather than returning the
`02FE` IDm through the FeliCa path.

1. Add the loopback URL to the game's existing AimeIO section while keeping the
   HINATA DLL path:

   ```ini
   [AIMEIO]
   path=hinata.dll
   serverUrl=ws://127.0.0.1:8765/game
   ```

2. Keep the game closed, connect HINATA in the page, and run the FeliCa
   diagnostic. The bridge accepts only a fully read `02FE` candidate.
3. Disconnect HINATA in the page, then physically remove the phone from the
   reader before launching the game. Leaving it on the reader lets the DLL's
   physical-card backend win the race and invalidates the relay test. The page
   reports the DLL version when its remote backend is connected.
4. Enter the 20-digit Access Code from your own card backup and send it once.

The relay binds only to `127.0.0.1`, accepts browser submissions only from its
own page, validates the `02FE` IDm and protocol-v2 handshake, and does not log or
persist the Access Code. The field is cleared after a successful handoff. The
Access Code is intentionally not derived from the IDm and is never included in
an exported diagnostic report. The native DLL WebSocket rejects browser-origin
upgrades, and a ping/pong heartbeat removes half-open clients so the page does
not keep reporting a closed game as connected.

HINATA DLL 1.1.2 separately writes the complete remotely supplied Access Code
to `AMDaemon/hinata.log` at info level. Treat that downstream log as sensitive;
do not publish it and remove or secure it after testing. Remove `serverUrl` to
restore the previous game configuration.

Local validation with SDEZ 1.67 and HINATA DLL 1.1.2 confirmed that both the
fixed `02FE001145141919` route and the `02FE`-plus-original-suffix route returned
to the same existing account when the phone was removed and the saved Access
Code was supplied through this bridge. This result covers only the local
AimeIO/private-server path, not an unmodified cabinet's RF acceptance.

## Result meaning

| Result | Meaning |
| --- | --- |
| `FFFF` responds | The phone is visible to wildcard 212 kbps FeliCa polling. |
| `88B4` responds | The phone is discoverable under the Aime System Code. |
| `4000` responds, `88B4` does not | Android generic HCE-F works, but this does not match an Aime cabinet poll. |
| Blocks `00`, `82`, and `85` are read | Polling advanced to service `000B`; block `85` exposes the card-image System Code. |
| Poll responds but block reads fail | System Code routing works; the HCE service command path does not. |

## MIFARE comparison

Choose a local 1024-byte MIFARE Classic 1K dump, then read the card currently
held near HINATA. The page compares a masked UID, reports ATQA/SAK and card type,
and checks Sector 1 with the dump's Key A and Key B. If authentication succeeds,
it compares data blocks 4-6 without showing their contents.

This is intended to distinguish a wallet that copied only a UID from one that
actually personalized the protected sector. MIFARE authentication frames,
keys, and block responses are redacted from the on-screen raw log and exported
JSON. The tool never writes a block, key, access bit, or configuration value.

The complete run uses PN532 `InListPassiveTarget` at 212 kbps with Request Code
`01`, then reads blocks `00`, `82`, and `85` from service `000B` for each discovered
target. Blocks `00`/`82` and `85` use separate reads because one HINATA `E2` HID
report cannot carry the 71-byte PN532 response produced by a three-block read.
After each FeliCa or MIFARE probe, the tool sends PN532 `InRelease(0)` so the
phone is not left in an active RF link while changing simulation modes. Raw
HINATA `E2`/PN532 frames are retained in the exported report.

## Local maimai limitation

The local game launch script injects `mai2hook.dll` into `amdaemon.exe` and the
hook loads `hinata.dll` through the segatools AimeIO interface. That path can
validate game-side card handling, but it does not reproduce the original Sega
reader's RF polling or System Code filter. A successful bridge test proves that
the local game can preserve account identity through AimeIO; it does not prove
that an unmodified official cabinet will accept `02FE` over RF.

## Test

```powershell
node --test tools/hinata-diagnostics/*.test.mjs
```
