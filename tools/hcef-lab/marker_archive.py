"""Generate a full-file restore fixture for Restore Path Lab only, never system NFC."""

from __future__ import annotations

import argparse
import hashlib
import io
import os
from pathlib import Path
import re
import tarfile
import zlib

PACKAGE = "io.github.umislat.restorepathlab"
HEADER = b"ANDROID BACKUP\n5\n1\nnone\n"


def build_marker_archive(certificate: bytes, token: str) -> bytes:
    # Public DER certificate exported from the APK's signer, not a private key or digest.
    if not 128 <= len(certificate) <= 16384 or certificate[0] != 0x30:
        raise ValueError("Supply the signing certificate in DER format")
    if not re.fullmatch(r"[0-9a-f]{32}", token):
        raise ValueError("Marker token must be exactly 32 lowercase hex characters")
    manifest = f"1\n{PACKAGE}\n1\n36\n\n0\n1\n{certificate.hex()}\n".encode("ascii")
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        files = [(f"apps/{PACKAGE}/_manifest", manifest)]
        for domain in ("f", "d_f"):
            files.append((f"apps/{PACKAGE}/{domain}/restore_probe.txt",
                          f"restored-{domain}:{token}\n".encode("ascii")))
        for path, data in files:
            entry = tarfile.TarInfo(path)
            entry.size = len(data)
            entry.mode = 0o600
            archive.addfile(entry, io.BytesIO(data))
    return HEADER + zlib.compress(buffer.getvalue())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate", type=Path, required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        with args.certificate.open("rb") as source:
            certificate = source.read(16385)
        archive = build_marker_archive(certificate, args.token)
        fd = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(archive)
            output.flush()
            os.fsync(output.fileno())
    except (ValueError, OSError) as error:
        parser.exit(2, f"Fixture refused: {error}\n")
    print(f"Fixture package: {PACKAGE}")
    print(f"Public certificate SHA-256: {hashlib.sha256(certificate).hexdigest()}")
    print(f"Archive SHA-256: {hashlib.sha256(archive).hexdigest()}")
    print("No device operation performed. This fixture cannot restore NFC system files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
