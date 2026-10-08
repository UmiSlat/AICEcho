import io
from pathlib import Path
import sys
import tarfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import marker_archive as m


class MarkerArchiveTest(unittest.TestCase):
    def test_fixed_package_and_marker_only(self):
        certificate = b'\x30' + b'\x00' * 255
        token = '0123456789abcdef' * 2
        raw = m.build_marker_archive(certificate, token)
        self.assertTrue(raw.startswith(m.HEADER))
        with tarfile.open(fileobj=io.BytesIO(zlib.decompress(raw[len(m.HEADER):]))) as archive:
            expected = [f'apps/{m.PACKAGE}/_manifest'] + [
                f'apps/{m.PACKAGE}/{d}/restore_probe.txt' for d in ('f', 'd_f')]
            self.assertEqual(archive.getnames(), expected)
            manifest = archive.extractfile(expected[0]).read().decode().splitlines()
            self.assertEqual(manifest[1], m.PACKAGE)
            self.assertEqual(manifest[7], certificate.hex())
            for index, domain in enumerate(('f', 'd_f'), 1):
                self.assertEqual(archive.extractfile(expected[index]).read(),
                                 f'restored-{domain}:{token}\n'.encode())
            self.assertTrue(all(e.isreg() and e.mode == 0o600 for e in archive))

    def test_bad_certificate_shape(self):
        for certificate in (b'', b'00', b'\x30' * 16385, b'\x01' * 256):
            with self.subTest(size=len(certificate)), self.assertRaises(ValueError):
                m.build_marker_archive(certificate, '0' * 32)

    def test_marker_token_is_not_arbitrary_content(self):
        for token in ('', 'x' * 32, '../escape', 'A' * 32, '0' * 31, '0' * 32 + '\n'):
            with self.subTest(token=token), self.assertRaises(ValueError):
                m.build_marker_archive(b'\x30' * 256, token)


if __name__ == '__main__':
    unittest.main()
