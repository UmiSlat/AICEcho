import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import nfc_snapshot as n
import provision_archive as p


class NfcSnapshotTest(unittest.TestCase):
    identity = (b'<?xml version="1.0" encoding="utf-8"?>\n<services>'
                b'<service component="io.github.umislat.hceflab/.LabService" uid="10394" '
                b'system-code="4000" nfcid2="02FE6C6162323031" />'
                b'<service component="example.other/.Service" uid="10123" '
                b'system-code="4001" nfcid2="02FE001122334455" />'
                b'</services>\n')
    header = b"MIUI BACKUP\n2\ncom.android.nfc NFC Service\n0\n0\nANDROID BACKUP\n5\n0\nnone\n"

    def fixture(self, mutate=None, xml=None):
        cert = (Path(__file__).parent / "fixtures/miui-nfc-signer.hex").read_text().strip()
        manifest = f"1\ncom.android.nfc\n36\n36\n\n0\n1\n{cert}\n".encode()
        entries = [(n.PREFIX + "_manifest", manifest, tarfile.REGTYPE),
                   (n.PREFIX + "f/" + n.IDENTITY, self.identity if xml is None else xml, tarfile.REGTYPE),
                   (n.PREFIX + "sp/NfcServicePrefs.xml", b"<map />", tarfile.REGTYPE)]
        if mutate:
            mutate(entries)
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for name, data, kind in entries:
                entry = tarfile.TarInfo(name)
                entry.type = kind
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        return self.header + stream.getvalue()

    def test_preserves_bytes_and_other_records_without_claiming_freshness(self):
        result = n.inspect_snapshot(self.fixture())
        self.assertEqual(result.identity, self.identity)
        self.assertEqual(result.report["service_count"], 2)
        self.assertFalse(result.report["live_freshness_verified"])
        self.assertFalse(result.report["safe_to_restore"])
        self.assertNotIn("02FE", json.dumps(result.report))

    def test_missing_or_ambiguous_domain_fails(self):
        for mutate in (lambda e: e.pop(1),
                       lambda e: e.append((n.PREFIX + "d_f/" + n.IDENTITY, self.identity, tarfile.REGTYPE)),
                       lambda e: e.__setitem__(1, (n.PREFIX + "d_f/" + n.IDENTITY, self.identity, tarfile.REGTYPE))):
            with self.assertRaises(ValueError):
                n.inspect_snapshot(self.fixture(mutate))

    def test_duplicate_foreign_traversal_link_and_domain_fail(self):
        for name, kind in ((n.PREFIX + "_manifest", tarfile.REGTYPE),
                           ("apps/example.other/f/secret", tarfile.REGTYPE),
                           (n.PREFIX + "f/../secret", tarfile.REGTYPE),
                           (n.PREFIX + "f/./secret", tarfile.REGTYPE),
                           (n.PREFIX + "f/x", tarfile.SYMTYPE),
                           (n.PREFIX + "a/base.apk", tarfile.REGTYPE)):
            with self.subTest(name=name, kind=kind), self.assertRaises(ValueError):
                n.inspect_snapshot(self.fixture(lambda e: e.append((name, b"", kind))))

    def test_version_and_signer_fail(self):
        for change in (lambda b: b.replace(b"\n36\n", b"\n35\n", 1),
                       lambda b: b.rsplit(b"\n", 2)[0] + b"\n00\n"):
            def mutate(entries):
                name, data, kind = entries[0]
                entries[0] = name, change(data), kind
            with self.assertRaises(ValueError):
                n.inspect_snapshot(self.fixture(mutate))

    def test_corrupt_headers_and_payload_fail(self):
        raw = self.fixture()
        for bad in (raw[:-1], raw + b"x" * 512, b"x" * (n.MAX_ARCHIVE + 1),
                    raw.replace(b"\nnone\n", b"\nAES-256\n"),
                    raw.replace(b"\n0\nnone\n", b"\n1\nnone\n"),
                    raw.replace(b"com.android.nfc NFC", b"com.android.nfcX NFC", 1)):
            with self.subTest(length=len(bad)), self.assertRaises(ValueError):
                n.inspect_snapshot(bad)

    def test_oversized_file_and_entry_count_fail(self):
        with self.assertRaises(ValueError):
            n.inspect_snapshot(self.fixture(lambda e: e.append((n.PREFIX + "f/large", b"x" * (n.MAX_FILE + 1), tarfile.REGTYPE))))
        with self.assertRaises(ValueError):
            n.inspect_snapshot(self.fixture(lambda e: e.extend((n.PREFIX + f"f/{i}", b"", tarfile.REGTYPE)
                                                              for i in range(n.MAX_ENTRIES))))

    def test_unsupported_xml_is_not_silently_rebuilt(self):
        for xml in (b"", b"<!DOCTYPE services><services />", self.identity.replace(b'uid="10394"', b'uid="10394" unknown="x"')):
            with self.assertRaises(ValueError):
                n.inspect_snapshot(self.fixture(xml=xml))

    def test_publish_is_new_directory_and_exact_bytes(self):
        result = n.inspect_snapshot(self.fixture())
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "snapshot"
            n.publish(result, path)
            self.assertEqual((path / "identity.xml").read_bytes(), self.identity)
            with self.assertRaises(FileExistsError):
                n.publish(result, path)

    def test_snapshot_feeds_existing_single_record_planner(self):
        result = n.inspect_snapshot(self.fixture())
        plan = p.make_plan(baseline=result.identity, baseline_sha256=result.report["identity_sha256"],
                           domain="f", component="io.github.umislat.hceflab/.LabService", uid=10394,
                           expected_sc="4000", expected_idm="02FE6C6162323031",
                           target_sc="88B4", target_idm="012E6C6162323031")
        self.assertEqual(p.inspect_archive(plan.rollback), ("f", self.identity))
        self.assertEqual(plan.report["preserved_service_count"], 1)
        self.assertFalse(plan.report["device_verified"])


if __name__ == "__main__":
    unittest.main()
