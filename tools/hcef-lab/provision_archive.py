"""Offline, single-record NFC-F provisioning plans. Never invokes a device tool."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tarfile
import xml.etree.ElementTree as ET
import zlib


PACKAGE = "com.android.nfc"
XML_NAME = "dynamic_systemcode_nfcid2.xml"
HEADER = b"ANDROID BACKUP\n5\n1\nnone\n"
MANIFEST_PATH = f"apps/{PACKAGE}/_manifest"
# Format follows Android's full-restore reader. Acceptance still depends on the ROM.
MANIFEST = f"1\n{PACKAGE}\n1\n36\n\n0\n1\n00\n".encode("ascii")
MAX_XML = 128 * 1024
MAX_ARCHIVE = 256 * 1024
MAX_RECORDS = 128
TARGETS = frozenset({
    "io.github.umislat.aimesimulator/io.github.umislat.aimesimulator.nfc.AimeHostService",
    "io.github.umislat.hceflab/io.github.umislat.hceflab.LabService",
})


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def component_key(value: str) -> str:
    parts = value.split("/")
    if len(parts) != 2:
        raise ValueError("Invalid component")
    package, name = parts
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", package, re.ASCII):
        raise ValueError("Invalid component package")
    if name.startswith("."):
        name = package + name
    if not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", name, re.ASCII):
        raise ValueError("Invalid component class")
    return package + "/" + name


def hex_value(value: str, length: int) -> str:
    if not re.fullmatch(r"[0-9a-fA-F]{" + str(length) + "}", value):
        raise ValueError(f"Expected exactly {length} hexadecimal characters")
    return value.upper()


def parse_xml(raw: bytes) -> tuple[ET.Element, dict[str, ET.Element]]:
    if not raw or len(raw) > MAX_XML:
        raise ValueError("XML size outside allowed range")
    text = raw.decode("utf-8-sig", errors="strict")
    # This format needs no DTD, entity declarations, comments or processing instructions.
    without_header = re.sub(r"^\s*<\?xml\s+[^?]*\?>", "", text, count=1)
    if "<!" in text or "\x00" in text or "<?" in without_header:
        raise ValueError("XML declarations/comments are not supported")
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_pis=True))
    root = ET.fromstring(text, parser=parser)
    if root.tag != "services" or root.attrib or (root.text or "").strip():
        raise ValueError("Unsupported services root")
    if len(root) > MAX_RECORDS:
        raise ValueError("Too many service records")
    records: dict[str, ET.Element] = {}
    for child in root:
        if (child.tag != "service" or len(child) or (child.text or "").strip()
                or (child.tail or "").strip()):
            raise ValueError("Unsupported service structure")
        if set(child.attrib) - {"component", "uid", "system-code", "nfcid2", "description"}:
            raise ValueError("Unknown service attribute; preserve source and review its schema")
        component = component_key(child.get("component", ""))
        uid = child.get("uid", "")
        if not re.fullmatch(r"0|[1-9][0-9]{0,9}", uid) or int(uid) > 2147483647:
            raise ValueError("Invalid service UID")
        if component in records:
            raise ValueError("Duplicate component, including abbreviated aliases")
        if "system-code" not in child.attrib and "nfcid2" not in child.attrib:
            raise ValueError("Empty dynamic record")
        for field, size in (("system-code", 4), ("nfcid2", 16)):
            value = child.get(field)
            if value is not None and value not in ("NULL", "RANDOM"):
                hex_value(value, size)
            elif value == "RANDOM" and field != "nfcid2":
                raise ValueError("RANDOM is not a System Code")
        records[component] = child
    return root, records


def make_archive(xml: bytes, domain: str) -> bytes:
    if domain not in ("f", "d_f"):
        raise ValueError("Select exactly one verified file domain: f or d_f")
    parse_xml(xml)
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, data in ((MANIFEST_PATH, MANIFEST),
                           (f"apps/{PACKAGE}/{domain}/{XML_NAME}", xml)):
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            entry.mode = 0o600
            entry.mtime = 0
            entry.uid = entry.gid = 0
            archive.addfile(entry, io.BytesIO(data))
    return HEADER + zlib.compress(stream.getvalue())


def inspect_archive(raw: bytes) -> tuple[str, bytes]:
    """Accept only this tool's two-entry USTAR shape; no extraction to disk."""
    if len(raw) > MAX_ARCHIVE or not raw.startswith(HEADER):
        raise ValueError("Unsupported archive size/header")
    decoder = zlib.decompressobj()
    payload = decoder.decompress(raw[len(HEADER):], MAX_ARCHIVE + 1)
    if (len(payload) > MAX_ARCHIVE or not decoder.eof or decoder.unused_data
            or decoder.unconsumed_tail):
        raise ValueError("Oversized, truncated or trailing compressed payload")
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
        entries = archive.getmembers()
        if len(entries) != 2 or any(not e.isreg() or e.pax_headers for e in entries):
            raise ValueError("Only manifest and one regular identity file are permitted")
        if (entries[0].name != MANIFEST_PATH or entries[0].size != len(MANIFEST)
                or archive.extractfile(entries[0]).read() != MANIFEST):
            raise ValueError("Unexpected restore manifest")
        domains = {f"apps/{PACKAGE}/{d}/{XML_NAME}": d for d in ("f", "d_f")}
        domain = domains.get(entries[1].name)
        if domain is None or entries[1].size > MAX_XML:
            raise ValueError("Unexpected file destination/size")
        xml = archive.extractfile(entries[1]).read()
    parse_xml(xml)
    # Reject hidden extra tar data and metadata differences, not merely visible names.
    canonical = zlib.decompress(make_archive(xml, domain)[len(HEADER):])
    if payload != canonical:
        raise ValueError("Noncanonical tar metadata or trailing data")
    return domain, xml


@dataclass(frozen=True)
class Plan:
    original: bytes
    proposed: bytes
    forward: bytes
    rollback: bytes
    report: dict


def make_plan(*, baseline: bytes, baseline_sha256: str, domain: str,
              component: str, uid: int, expected_sc: str, expected_idm: str,
              target_sc: str, target_idm: str) -> Plan:
    if not re.fullmatch(r"[0-9a-fA-F]{64}", baseline_sha256):
        raise ValueError("A reviewed baseline SHA-256 is required")
    if digest(baseline) != baseline_sha256.lower():
        raise ValueError("Baseline hash mismatch")
    key = component_key(component)
    if key not in TARGETS or type(uid) is not int or not 10000 <= uid < 100000:
        raise ValueError("Only existing AimeSimulator/Lab services for primary-user app UIDs")
    expected_sc, target_sc = hex_value(expected_sc, 4), hex_value(target_sc, 4)
    expected_idm, target_idm = hex_value(expected_idm, 16), hex_value(target_idm, 16)
    root, records = parse_xml(baseline)
    service = records.get(key)
    if service is None or service.get("uid") != str(uid):
        raise ValueError("Target is absent or has a different owner UID")
    before = dict(service.attrib)
    if (before.get("system-code", "").upper() != expected_sc
            or before.get("nfcid2", "").upper() != expected_idm):
        raise ValueError("Target does not match the expected previous identity")
    if (expected_sc, expected_idm) == (target_sc, target_idm):
        raise ValueError("No identity change requested")
    untouched = {k: dict(v.attrib) for k, v in records.items() if k != key}
    service.set("system-code", target_sc)
    service.set("nfcid2", target_idm)
    proposed = ET.tostring(root, encoding="utf-8", xml_declaration=True)
    _, checked = parse_xml(proposed)
    if {k: dict(v.attrib) for k, v in checked.items() if k != key} != untouched:
        raise ValueError("Unrelated records changed")
    forward, rollback = make_archive(proposed, domain), make_archive(baseline, domain)
    if inspect_archive(forward) != (domain, proposed) or inspect_archive(rollback) != (domain, baseline):
        raise ValueError("Archive round-trip mismatch")
    report = {
        "format": "aimesim-offline-provision-plan/v1",
        "state": "offline-unverified-do-not-auto-restore",
        "target_package": PACKAGE,
        "domain": domain,
        "changed_component": key,
        "before": before,
        "after": dict(checked[key].attrib),
        "preserved_service_count": len(untouched),
        "baseline_sha256": digest(baseline),
        "proposed_sha256": digest(proposed),
        "forward_sha256": digest(forward),
        "rollback_sha256": digest(rollback),
        "device_verified": False,
        "warnings": [
            "Hashes prove local bytes only, not live device state, ownership or freshness.",
            "Full-file restore can overwrite later changes; revalidate live state before either direction.",
            "Rollback is a prepared file, not guaranteed device recovery or permission to restore.",
            "Backup-agent callbacks and NFC reload may affect other NFC or wallet functions.",
            "Files contain plaintext card identities. Keep private; do not commit or upload.",
        ],
    }
    return Plan(baseline, proposed, forward, rollback, report)


def write_plan(plan: Plan, destination: Path) -> None:
    """Publish to a new directory only; the completion marker is written last."""
    contents = {
        "baseline.xml": plan.original,
        "proposed.xml": plan.proposed,
        "forward.ab": plan.forward,
        "rollback.ab": plan.rollback,
        "plan.json": (json.dumps(plan.report, indent=2, ensure_ascii=True) + "\n").encode("utf-8"),
    }
    destination.mkdir(mode=0o700, parents=False, exist_ok=False)
    for name, data in contents.items():
        fd = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
    with (destination / "OFFLINE_PLAN_COMPLETE").open("x", encoding="ascii") as marker:
        marker.write("Local generation only. Device state and recovery are unverified.\n")


def read_limited(path: Path) -> bytes:
    with path.open("rb") as source:
        data = source.read(MAX_XML + 1)
    if len(data) > MAX_XML:
        raise ValueError("Baseline XML exceeds size limit")
    return data


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--baseline-sha256", required=True)
    parser.add_argument("--domain", choices=("f", "d_f"), required=True)
    parser.add_argument("--component", required=True)
    parser.add_argument("--uid", type=int, required=True)
    parser.add_argument("--expected-sc", required=True)
    parser.add_argument("--expected-idm", required=True)
    parser.add_argument("--target-sc", required=True)
    parser.add_argument("--target-idm", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        plan = make_plan(baseline=read_limited(args.baseline),
                         baseline_sha256=args.baseline_sha256, domain=args.domain,
                         component=args.component, uid=args.uid,
                         expected_sc=args.expected_sc, expected_idm=args.expected_idm,
                         target_sc=args.target_sc, target_idm=args.target_idm)
        write_plan(plan, args.out)
    except (ValueError, OSError, ET.ParseError, tarfile.TarError, zlib.error) as error:
        parser.exit(2, f"Plan refused: {error}\n")
    print(f"Offline plan: {args.out}")
    print(f"Unchanged other services: {plan.report['preserved_service_count']}")
    print("No device was contacted. Review plan.json; do not restore without live checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
