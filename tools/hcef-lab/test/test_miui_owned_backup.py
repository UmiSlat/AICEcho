import io
from pathlib import Path
import sys
import tarfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_miui_owned_backup as v


class MiuiOwnedBackupTest(unittest.TestCase):
    token = "0123456789abcdef" * 2

    def fixture(self, mutate=None):
        root = Path(__file__).resolve().parents[3]
        cert = (root / "app/src/test/resources/restore-probe-signer.hex").read_text().strip()
        manifest = f"1\n{v.PACKAGE}\n1\n36\n\n0\n1\n{cert}\n".encode()
        entries = [(v.PREFIX + "_manifest", manifest, tarfile.REGTYPE),
                   (v.PREFIX + "f/restore_probe.txt", f"restored-f:{self.token}\n".encode(), tarfile.REGTYPE),
                   (v.PREFIX + "d_f/restore_probe.txt", f"restored-d_f:{self.token}\n".encode(), tarfile.REGTYPE),
                   (v.PREFIX + "sp/restore_lab.xml", b"<map />", tarfile.REGTYPE)]
        if mutate:
            mutate(entries)
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for name, data, kind in entries:
                entry = tarfile.TarInfo(name)
                entry.type = kind
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        return v.HEADER + stream.getvalue()

    def test_exact_ce_de_and_signer(self):
        result = v.verify(self.fixture(), self.token)
        self.assertTrue(result["verifiedOwnedLabExport"])
        self.assertFalse(result["nfcSnapshotVerified"])
        self.assertEqual([f["bytes"] for f in result["files"]][1:3], [44, 46])

    def test_wrong_token_or_marker(self):
        for token in ("", "A" * 32, "0" * 32, self.token + "\n"):
            with self.subTest(token=token), self.assertRaises(ValueError):
                v.verify(self.fixture(), token)

    def test_missing_or_duplicate(self):
        for change in (lambda e: e.pop(2), lambda e: e.append(e[1])):
            with self.assertRaises(ValueError):
                v.verify(self.fixture(change), self.token)

    def test_other_package_path_or_link(self):
        for name, kind in (("apps/com.android.nfc/f/example", tarfile.REGTYPE),
                           (v.PREFIX + "f/../example", tarfile.REGTYPE),
                           (v.PREFIX + "f/restore_probe.txt", tarfile.SYMTYPE)):
            with self.subTest(name=name, kind=kind), self.assertRaises(ValueError):
                v.verify(self.fixture(lambda e: e.__setitem__(1, (name, e[1][1], kind))), self.token)

    def test_wrong_manifest_or_certificate(self):
        for change in (lambda b: b.replace(b"\n36\n", b"\n35\n"),
                       lambda b: b.rsplit(b"\n", 2)[0] + b"\n00\n"):
            def mutate(entries):
                n, data, t = entries[0]
                entries[0] = n, change(data), t
            with self.assertRaises(ValueError):
                v.verify(self.fixture(mutate), self.token)

    def test_size_header_truncation_and_trailing_data(self):
        raw = self.fixture()
        for bad in (b"x" * (v.MAX_BYTES + 1), raw.replace(b"\nnone\n", b"\nAES-256\n", 1),
                    raw[:-1], raw[:len(v.HEADER) + 512], raw + b"x" * 512):
            with self.subTest(length=len(bad)), self.assertRaises(ValueError):
                v.verify(bad, self.token)


if __name__ == "__main__":
    unittest.main()
