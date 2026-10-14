"""Offline validation of the pinned owned-Lab export, never extracts or restores."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile

PACKAGE = "io.github.umislat.aicecho.restorepathlab"
PREFIX = f"apps/{PACKAGE}/"
SIGNER = "3bf1577c690a4a57534afe7338b9534fdba659deedce1e6f3ed408ae750545dc"
HEADER = (f"MIUI BACKUP\n2\n{PACKAGE} Restore Path Lab\n0\n0\n"
          "ANDROID BACKUP\n5\n0\nnone\n").encode("ascii")
MAX_BYTES = 1024 * 1024
NAMES = ("_manifest", "f/restore_probe.txt", "d_f/restore_probe.txt", "sp/restore_lab.xml")


def verify(raw: bytes, token: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{32}", token):
        raise ValueError("Expected an independently recorded 32-hex marker token")
    if len(raw) > MAX_BYTES or not raw.startswith(HEADER):
        raise ValueError("Unsupported owned-Lab export size/header")
    payload = raw[len(HEADER):]
    if len(payload) % 512:
        raise ValueError("Truncated tar block")
    files = {}
    end = 0
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
            for entry in archive:
                if (entry.name not in {PREFIX + name for name in NAMES}
                        or entry.name in files or not entry.isreg() or entry.pax_headers
                        or entry.linkname or entry.size > 16 * 1024):
                    raise ValueError("Unexpected, duplicate or oversized archive entry")
                # Extended tar headers and gaps are outside this fixed fixture format.
                if entry.offset != end or entry.offset_data != end + 512:
                    raise ValueError("Unexpected tar metadata")
                data = archive.extractfile(entry).read()
                if len(data) != entry.size:
                    raise ValueError("Truncated archive entry")
                files[entry.name] = data
                end = entry.offset_data + ((entry.size + 511) // 512) * 512
    except tarfile.TarError as error:
        raise ValueError("Invalid tar archive") from error
    if set(files) != {PREFIX + name for name in NAMES}:
        raise ValueError("Missing owned-Lab file")
    if len(payload) - end < 1024 or any(payload[end:]):
        raise ValueError("Missing tar end or hidden trailing data")
    manifest = files[PREFIX + "_manifest"].decode("ascii").splitlines()
    if len(manifest) != 8 or manifest[:7] != ["1", PACKAGE, "1", "36", "", "0", "1"]:
        raise ValueError("Unexpected package/version/APK/signature manifest")
    if not re.fullmatch(r"[0-9a-fA-F]+", manifest[7]) or len(manifest[7]) % 2:
        raise ValueError("Invalid signer encoding")
    if hashlib.sha256(bytes.fromhex(manifest[7])).hexdigest() != SIGNER:
        raise ValueError("Owned-Lab signer mismatch")
    for domain in ("f", "d_f"):
        if files[PREFIX + f"{domain}/restore_probe.txt"] != f"restored-{domain}:{token}\n".encode():
            raise ValueError(f"Exact {domain} marker mismatch")
    return {
        "verifiedOwnedLabExport": True,
        "nfcSnapshotVerified": False,
        "archiveBytes": len(raw),
        "archiveSha256": hashlib.sha256(raw).hexdigest(),
        "files": [{"name": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                  for name, data in files.items()],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--token", required=True)
    args = parser.parse_args()
    with args.archive.open("rb") as source:
        raw = source.read(MAX_BYTES + 1)
    print(json.dumps(verify(raw, args.token), indent=2))


if __name__ == "__main__":
    main()
