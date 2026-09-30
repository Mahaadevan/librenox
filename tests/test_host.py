import tempfile
import unittest
import zipfile
from pathlib import Path

import minecraft_server_hosting_tool as host


class HostPureFunctionTests(unittest.TestCase):
    def test_required_java(self):
        self.assertEqual(host.required_java("1.20.4"), 17)
        self.assertEqual(host.required_java("1.20.6"), 21)
        self.assertEqual(host.required_java("26.3"), 25)

    def test_hash_file(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "data"
            path.write_bytes(b"minecraft")
            self.assertEqual(
                host.hash_file(path),
                "9970626666560a32465d4ce10d28f3233365af833e15eed59884d9477862c379",
            )

    def test_zip_path_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "unsafe.zip"
            destination = Path(folder) / "out"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("../escape.txt", "bad")
            with self.assertRaises(RuntimeError):
                host.extract(archive, destination)
            self.assertFalse((Path(folder).parent / "escape.txt").exists())

    def test_checksum_mismatch_deletes_file(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "data"
            path.write_bytes(b"tampered")
            with self.assertRaises(RuntimeError):
                host._verify_download(path, "0" * 64, "test-download")
            self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
