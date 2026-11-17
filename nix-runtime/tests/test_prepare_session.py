import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

spec = importlib.util.spec_from_file_location('prepare_session', Path(__file__).resolve().parents[1] / 'prepare-session.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrepareSessionTests(unittest.TestCase):
    def archive(self, directory, files):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as archive:
            for name, content in files.items():
                archive.writestr(name, content)
        apk = directory / 'bootstrap.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('assets/bootstrap-aarch64.zip', inner.getvalue())
        return apk

    def test_bad_hash_does_not_create_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            apk = self.archive(directory, {})
            with self.assertRaisesRegex(ValueError, 'checksum'):
                module.extract(apk, directory / 'root')
            self.assertFalse((directory / 'root').exists())

    def test_safe_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            apk = self.archive(directory, {'bin/tool': 'ok', 'EXECUTABLES.txt': 'bin/tool\n', 'SYMLINKS.txt': 'tool←bin/link\n'})
            with mock.patch.object(module, 'BOOTSTRAP_SHA256', hashlib.sha256(apk.read_bytes()).hexdigest()):
                module.extract(apk, directory / 'root')
            self.assertEqual((directory / 'root/bin/link').read_text(), 'ok')
            self.assertEqual((directory / 'root/bin/tool').stat().st_mode & 0o777, 0o700)

    def test_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            apk = self.archive(directory, {'../escape': 'bad'})
            with mock.patch.object(module, 'BOOTSTRAP_SHA256', hashlib.sha256(apk.read_bytes()).hexdigest()):
                with self.assertRaisesRegex(ValueError, 'unsafe'):
                    module.extract(apk, directory / 'root')
            self.assertFalse((directory / 'escape').exists())

    def test_existing_root_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            root = directory / 'root'
            root.mkdir()
            (root / 'keep').write_text('preserved')
            apk = self.archive(directory, {})
            with mock.patch.object(module, 'BOOTSTRAP_SHA256', hashlib.sha256(apk.read_bytes()).hexdigest()):
                with self.assertRaises(FileExistsError):
                    module.extract(apk, root)
            self.assertEqual((root / 'keep').read_text(), 'preserved')

    def test_symlink_parent_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            apk = self.archive(directory, {'EXECUTABLES.txt': '', 'SYMLINKS.txt': '/outside←escape\ntarget←escape/file\n'})
            with mock.patch.object(module, 'BOOTSTRAP_SHA256', hashlib.sha256(apk.read_bytes()).hexdigest()):
                with self.assertRaisesRegex(ValueError, 'symlink parent'):
                    module.extract(apk, directory / 'root')


if __name__ == '__main__':
    unittest.main()
