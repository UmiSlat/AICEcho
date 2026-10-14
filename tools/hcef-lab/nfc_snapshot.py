"""Offline inspection of a reviewed-ROM NFC export. Never connects to a device."""

import argparse
from dataclasses import dataclass, field
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import tarfile

from provision_archive import parse_xml

PACKAGE = "com.android.nfc"
PREFIX = f"apps/{PACKAGE}/"
IDENTITY = "dynamic_systemcode_nfcid2.xml"
SIGNER = "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025"
MAX_ARCHIVE = 8 * 1024 * 1024
MAX_FILE = 128 * 1024
MAX_ENTRIES = 64


@dataclass(frozen=True)
class Snapshot:
    identity: bytes = field(repr=False)
    report: dict


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def inspect_snapshot(raw: bytes) -> Snapshot:
    if len(raw) > MAX_ARCHIVE:
        raise ValueError("NFC export exceeds size limit")
    stream = io.BytesIO(raw)
    header = []
    for _ in range(9):
        line = stream.readline(513)
        if len(line) > 512 or not line.endswith(b"\n") or b"\r" in line:
            raise ValueError("Unsupported or truncated export header")
        header.append(line[:-1].decode("utf-8", errors="strict"))
    if (header[:2] != ["MIUI BACKUP", "2"]
            or not header[2].startswith(PACKAGE + " ")
            or any(ord(char) < 32 for char in header[2])
            or header[3:] != ["0", "0", "ANDROID BACKUP", "5", "0", "none"]):
        raise ValueError("Expected reviewed NFC MIUI v2 / uncompressed Android Backup v5")
    payload = stream.read()
    if len(payload) % 512:
        raise ValueError("Truncated tar block")
    files = {}
    end = 0
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
            for entry in archive:
                path = PurePosixPath(entry.name)
                parts = path.parts
                if (len(files) >= MAX_ENTRIES or not entry.name.startswith(PREFIX)
                        or str(path) != entry.name or any(part in (".", "..") for part in parts)
                        or "\\" in entry.name or any(ord(char) < 32 for char in entry.name)
                        or entry.name in files or not entry.isreg() or entry.linkname
                        or entry.pax_headers or entry.size > MAX_FILE or entry.size < 0
                        or entry.offset != end or entry.offset_data != end + 512):
                    raise ValueError("Unsupported scope, type, path, size or tar metadata")
                if (entry.name != PREFIX + "_manifest"
                        and (len(parts) < 4 or parts[2] not in ("f", "d_f", "sp", "d_sp"))):
                    raise ValueError("Unexpected NFC backup domain")
                data = archive.extractfile(entry).read()
                if len(data) != entry.size:
                    raise ValueError("Truncated file")
                files[entry.name] = data
                end = entry.offset_data + ((entry.size + 511) // 512) * 512
    except tarfile.TarError as error:
        raise ValueError("Invalid tar archive") from error
    if len(payload) - end < 1024 or any(payload[end:]):
        raise ValueError("Missing tar end or hidden trailing payload")
    manifest = files.get(PREFIX + "_manifest", b"").decode("ascii").splitlines()
    if (len(manifest) != 8 or manifest[:7] != ["1", PACKAGE, "36", "36", "", "0", "1"]
            or not re.fullmatch(r"[0-9a-fA-F]+", manifest[7]) or len(manifest[7]) % 2):
        raise ValueError("Unexpected NFC manifest")
    if digest(bytes.fromhex(manifest[7])) != SIGNER:
        raise ValueError("NFC manifest signer mismatch")
    # This inspected ROM stores the global file in CE. DE is not a fallback.
    identities = [name for name in files if PurePosixPath(name).name == IDENTITY]
    if identities != [PREFIX + "f/" + IDENTITY]:
        raise ValueError("Missing or ambiguous global CE identity file")
    identity = files[identities[0]]
    _, records = parse_xml(identity)
    users = sorted({int(record.get("uid")) // 100000 for record in records.values()})
    report = {
        "format": "aicecho-reviewed-nfc-snapshot/v1",
        "scope": "offline-archive-inspection",
        "archive_sha256": digest(raw),
        "archive_bytes": len(raw),
        "identity_domain": "f",
        "identity_sha256": digest(identity),
        "identity_bytes": len(identity),
        "service_count": len(records),
        "user_ids": users,
        "file_count": len(files),
        "live_freshness_verified": False,
        "safe_to_restore": False,
        "files": [{"path": name, "bytes": len(data), "sha256": digest(data)}
                  for name, data in files.items()],
        "warnings": [
            "The manifest certificate is metadata, not a cryptographic archive signature.",
            "A complete file in this archive does not prove current device state or atomicity.",
            "Other NFC files/preferences are inventoried, never included in a restore plan here.",
            "Contains plaintext card identities; keep archive and derived files private.",
        ],
    }
    return Snapshot(identity=identity, report=report)


def publish(snapshot: Snapshot, destination: Path) -> None:
    """Preserve exact XML; fail if a destination exists, without extracting tar paths."""
    destination.mkdir(mode=0o700, parents=False, exist_ok=False)
    report = (json.dumps(snapshot.report, indent=2) + "\n").encode("ascii")
    for name, content in (("identity.xml", snapshot.identity), ("snapshot.json", report)):
        fd = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    with args.archive.open("rb") as source:
        snapshot = inspect_snapshot(source.read(MAX_ARCHIVE + 1))
    if args.out:
        publish(snapshot, args.out)
    print(json.dumps(snapshot.report, indent=2))


if __name__ == "__main__":
    main()
