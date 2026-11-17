import hashlib
import importlib.util
import io
import json
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

    def test_guest_identity(self):
        for username in ('nix', 'pschmitt'):
            with self.subTest(username=username), tempfile.TemporaryDirectory() as tmp:
                directory = Path(tmp)
                package = '/nix/store/' + 'a' * 32 + '-session'
                config = dict(schema=1, system='aarch64-linux', pageSize=4096,
                              packages=[package], path=package + '/bin')
                config.update({key: package for key in ('shell', 'nix', 'python', 'zsh', 'certificate', 'terminfo')})
                manifest = directory / 'manifest.json'
                manifest.write_text(json.dumps(config))
                certificate = directory / 'cert.pem'
                certificate.touch()

                def extract(_apk, root):
                    for name in ('fixture-busybox-static-test', 'fixture-nix-static-test'):
                        (root / 'nix/store' / name).mkdir(parents=True)

                with mock.patch.object(module, 'extract', extract):
                    if username == 'nix':
                        module.prepare(directory, None, manifest, certificate)
                    else:
                        module.prepare(directory, None, manifest, certificate, username)
                home = f'/home/{username}'
                self.assertTrue((directory / 'root' / home.lstrip('/')).is_dir())
                fields = (directory / 'root/etc/passwd').read_text().strip().split(':')
                self.assertEqual(fields[0], username)
                self.assertEqual(fields[5], home)
                self.assertTrue((directory / 'root/etc/group').read_text().startswith(username + ':'))
                environment = (directory / 'session.env').read_text()
                self.assertIn(f'SESSION_USER={username}\n', environment)
                self.assertIn(f'SESSION_HOME={home}\n', environment)

    def test_invalid_username_rejected_before_extraction(self):
        for username in ('../outside', '/root', 'a:b', '', 'a b', 'x' * 33):
            with self.subTest(username=username), self.assertRaisesRegex(ValueError, 'username'):
                module.prepare(None, None, None, None, username)

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
