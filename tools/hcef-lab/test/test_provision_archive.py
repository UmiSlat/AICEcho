from __future__ import annotations

import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provision_archive as p


LAB = "io.github.umislat.hceflab/io.github.umislat.hceflab.LabService"
ORIGINAL = b'''<?xml version="1.0" encoding="utf-8"?>
<services>
 <service component="io.github.umislat.hceflab/.LabService" uid="10394" system-code="4000" nfcid2="02FE6C6162323031" />
 <service component="example.other/.Service" uid="10555" system-code="4ABC" nfcid2="02FE112233445566" description="keep &amp; preserve" />
 <service component="example.partial/.Service" uid="10556" nfcid2="RANDOM" />
</services>
'''


class ProvisionArchiveTest(unittest.TestCase):
    def plan(self, baseline=ORIGINAL, **changes):
        args = dict(baseline=baseline, baseline_sha256=p.digest(baseline),
                    domain="f", component=LAB, uid=10394,
                    expected_sc="4000", expected_idm="02FE6C6162323031",
                    target_sc="88B4", target_idm="012E6C6162323032")
        args.update(changes)
        return p.make_plan(**args)

    def test_exact_rollback_and_single_record_change(self):
        plan = self.plan()
        self.assertEqual(p.inspect_archive(plan.rollback), ("f", ORIGINAL))
        _, before = p.parse_xml(ORIGINAL)
        _, after = p.parse_xml(plan.proposed)
        self.assertEqual(before.keys(), after.keys())
        for key in before:
            if key != LAB:
                self.assertEqual(before[key].attrib, after[key].attrib)
        self.assertEqual(after[LAB].get("nfcid2"), "012E6C6162323032")
        self.assertEqual(after[LAB].get("system-code"), "88B4")
        self.assertEqual(after[LAB].get("component"), before[LAB].get("component"))
        self.assertEqual(plan.report["preserved_service_count"], 2)

    def test_domain_is_explicit_and_single(self):
        for domain in ("f", "d_f"):
            with self.subTest(domain=domain):
                plan = self.plan(domain=domain)
                self.assertEqual(p.inspect_archive(plan.forward), (domain, plan.proposed))
        for domain in ("", "f,d_f", "sp", "../f", "r"):
            with self.subTest(domain=domain), self.assertRaises(ValueError):
                self.plan(domain=domain)

    def test_deterministic_and_independent_tar_reader(self):
        first, second = self.plan(), self.plan()
        self.assertEqual(first.forward, second.forward)
        with tarfile.open(fileobj=io.BytesIO(zlib.decompress(first.forward[len(p.HEADER):]))) as archive:
            self.assertEqual(archive.getnames(), [p.MANIFEST_PATH,
                             f"apps/{p.PACKAGE}/f/{p.XML_NAME}"])
            self.assertEqual(archive.extractfile(p.MANIFEST_PATH).read(), p.MANIFEST)
            xml = ET.fromstring(archive.extractfile(archive.getmembers()[1]).read())
            self.assertEqual(xml[0].get("system-code"), "88B4")
            self.assertTrue(all(e.mode == 0o600 and e.uid == 0 and e.gid == 0 for e in archive))

    def test_baseline_hash_and_previous_identity_required(self):
        for changes in ({"baseline_sha256": "0" * 64}, {"baseline_sha256": "oops"},
                        {"expected_sc": "4001"}, {"expected_idm": "02FE000000000000"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.plan(**changes)

    def test_noop_rejected(self):
        with self.assertRaises(ValueError):
            self.plan(target_sc="4000", target_idm="02FE6C6162323031")

    def test_bad_target_and_owner_rejected(self):
        for changes in ({"uid": 10395}, {"uid": 2000}, {"uid": 110394},
                        {"uid": True}, {"component": "example.other/.Service"},
                        {"component": next(t for t in p.TARGETS if t != LAB)}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.plan(**changes)

    def test_invalid_identity_inputs(self):
        for field, values in {
            "target_idm": ["RANDOM", "NULL", "0" * 15, "G" * 16, " 02FE6C6162323031"],
            "target_sc": ["0", "88B4 ", "<x/>", "NULL"],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    self.plan(**{field: value})

    def test_lowercase_is_normalized(self):
        plan = self.plan(expected_idm="02fe6c6162323031", target_sc="88b4", target_idm="012e6c6162323032")
        self.assertEqual(plan.report["after"]["nfcid2"], "012E6C6162323032")

    def test_duplicate_canonical_component_rejected(self):
        duplicate = ORIGINAL.replace(b"</services>",
            f'<service component="{LAB}" uid="10394" system-code="4000" /></services>'.encode())
        with self.assertRaises(ValueError):
            self.plan(duplicate)

    def test_unsafe_xml_rejected(self):
        for raw in (b'', b'\xff', b'<services', b'<!DOCTYPE services><services/>',
                    b'<services><!--comment--></services>', b'<services><?test x?></services>',
                    b'<?test x?><services/>', b'<services/><?test x?>',
                    b'<services xmlns="urn:test"/>', b'<services version="2"/>',
                    b'<services>text</services>', b'<services><unknown/></services>',
                    b'\x00' + ORIGINAL, b' ' * (p.MAX_XML + 1)):
            with self.subTest(raw=raw[:40]), self.assertRaises((ValueError, ET.ParseError)):
                p.parse_xml(raw)

    def test_unknown_attributes_or_children_not_silently_lost(self):
        for raw in (ORIGINAL.replace(b'uid="10394"', b'uid="10394" future="x"'),
                    ORIGINAL.replace(b'uid="10394"', b'uid="-1"'),
                    ORIGINAL.replace(b'uid="10394"', b'uid="999999999999"'),
                    ORIGINAL.replace(b'<services>', b'<services><service><nested/></service>')):
            with self.subTest(raw=raw[:80]), self.assertRaises(ValueError):
                self.plan(raw)

    def test_limits(self):
        raw = b'<services>' + b''.join(
            f'<service component="example.s{i}/.Service" uid="12345" system-code="4000"/>'.encode()
            for i in range(p.MAX_RECORDS + 1)) + b'</services>'
        with self.assertRaises(ValueError):
            p.parse_xml(raw)

    def test_extra_file_and_wrong_domain_rejected(self):
        for name in (f"apps/{p.PACKAGE}/sp/RoutingOptionPrefs.xml", "../escape",
                     f"apps/other.package/f/{p.XML_NAME}", p.MANIFEST_PATH):
            with self.subTest(name=name):
                stream = io.BytesIO()
                with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
                    for path, raw in ((p.MANIFEST_PATH, p.MANIFEST), (name, ORIGINAL)):
                        e = tarfile.TarInfo(path); e.size = len(raw); e.mode = 0o600
                        archive.addfile(e, io.BytesIO(raw))
                with self.assertRaises(ValueError):
                    p.inspect_archive(p.HEADER + zlib.compress(stream.getvalue()))

    def test_symlink_rejected(self):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            m = tarfile.TarInfo(p.MANIFEST_PATH); m.size = len(p.MANIFEST)
            archive.addfile(m, io.BytesIO(p.MANIFEST))
            e = tarfile.TarInfo(f"apps/{p.PACKAGE}/f/{p.XML_NAME}")
            e.type = tarfile.SYMTYPE; e.linkname = "somewhere"
            archive.addfile(e)
        with self.assertRaises(ValueError):
            p.inspect_archive(p.HEADER + zlib.compress(stream.getvalue()))

    def test_bad_compression_truncation_and_trailing_data_rejected(self):
        valid = self.plan().forward
        for raw in (valid[:-2], valid + b'trailing', valid.replace(b'none\n', b'AES-256\n', 1),
                    p.HEADER + zlib.compress(b'x' * (p.MAX_ARCHIVE + 1)),
                    p.HEADER + zlib.compress(zlib.decompress(valid[len(p.HEADER):]) + b'x')):
            with self.subTest(size=len(raw)), self.assertRaises((ValueError, zlib.error)):
                p.inspect_archive(raw)

    def test_publish_is_private_complete_and_never_overwrites(self):
        plan = self.plan()
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp) / "plan"
            p.write_plan(plan, dest)
            self.assertEqual((dest / "baseline.xml").read_bytes(), ORIGINAL)
            self.assertFalse(json.loads((dest / "plan.json").read_bytes())["device_verified"])
            self.assertTrue((dest / "OFFLINE_PLAN_COMPLETE").is_file())
            with self.assertRaises(FileExistsError):
                p.write_plan(plan, dest)
            self.assertEqual((dest / "forward.ab").read_bytes(), plan.forward)

    def test_partial_publish_has_no_complete_marker(self):
        plan = self.plan()
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp) / "plan"
            with patch.object(p.os, 'fsync', side_effect=OSError('simulated disk error')):
                with self.assertRaises(OSError):
                    p.write_plan(plan, dest)
            self.assertFalse((dest / "OFFLINE_PLAN_COMPLETE").exists())
            with self.assertRaises(FileExistsError):
                p.write_plan(plan, dest)

    def test_cli_offline_generation_and_refusal(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, dest = Path(tmp) / 'input.xml', Path(tmp) / 'output'
            source.write_bytes(ORIGINAL)
            cmd = [sys.executable, '-B', str(Path(p.__file__)), '--baseline', str(source),
                   '--baseline-sha256', p.digest(ORIGINAL), '--domain', 'f', '--component', LAB,
                   '--uid', '10394', '--expected-sc', '4000', '--expected-idm', '02FE6C6162323031',
                   '--target-sc', '88B4', '--target-idm', '012E6C6162323032', '--out', str(dest)]
            success = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
            self.assertEqual(success.returncode, 0, success.stderr)
            self.assertNotIn('012E6C6162323032', success.stdout)
            duplicate = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
            self.assertEqual(duplicate.returncode, 2)


if __name__ == "__main__":
    unittest.main()
