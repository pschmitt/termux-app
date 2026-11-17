#!/usr/bin/env python3
"""Exercise the native candidate descriptor without Android or a launcher."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'system-session.sh'


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.runtime = Path(self.temp.name)
        self.name = 'a' * 32 + '-termuxpp-system-test'
        self.candidate = self.runtime / 'system-candidates' / self.name
        (self.candidate / 'etc').mkdir(parents=True)
        self.values = ['guest', '/home/guest', '/nix/store/' + 'b' * 32 + '-system-path/bin',
                       '/nix/store/' + 'c' * 32 + '-bash/bin/bash',
                       '/nix/store/' + 'd' * 32 + '-cert/etc/ca.crt',
                       '/nix/store/' + 'e' * 32 + '-ncurses/share/terminfo']
        self.write()

    def write(self, suffix=''):
        (self.candidate / 'session').write_text('\n'.join(self.values) + '\n' + suffix)

    def load(self, name=None):
        return subprocess.run([os.environ.get('TEST_SHELL', 'bash'), '-c', '''
. "$1" || exit 1
session_user=guest
session_home=/home/guest
load_system_candidate "$2" "$3" || exit 1
printf '%s\\n' "$SESSION_PATH" "$SESSION_SHELL" "$SESSION_CERTIFICATE" "$SESSION_TERMINFO" "$session_etc"
''', 'test', str(HELPER), str(self.runtime), self.name if name is None else name],
                              env={'PATH': os.environ['PATH']}, text=True, capture_output=True)

    def test_valid(self):
        result = self.load()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), self.values[2:] + [str(self.candidate / 'etc')])

    def test_wrong_identity(self):
        for index, value in [(0, 'other'), (1, '/home/other')]:
            original = self.values[index]
            self.values[index] = value
            self.write()
            self.assertNotEqual(self.load().returncode, 0)
            self.values[index] = original

    def test_extra_and_truncated_records(self):
        for suffix in ['extra\n', 'extra']:
            self.write(suffix)
            self.assertNotEqual(self.load().returncode, 0)
        (self.candidate / 'session').write_text('\n'.join(self.values))
        self.assertNotEqual(self.load().returncode, 0)

    def test_paths_are_data_not_shell(self):
        marker = self.runtime / 'executed'
        for value in ['/bin/sh', '/nix/store/x/../escape', '/nix/store/x:bad',
                      f'$(touch {marker})', '/nix/store/$(false)', '/nix/store/a b']:
            self.values[2] = value
            self.write()
            self.assertNotEqual(self.load().returncode, 0, value)
        self.assertFalse(marker.exists())

    def test_candidate_name(self):
        for name in ['', '../escape', '/absolute', '.hidden', 'bad/name', 'a b']:
            self.assertNotEqual(self.load(name).returncode, 0, name)

    def test_directory_symlink(self):
        (self.candidate / 'etc').rmdir()
        (self.candidate / 'etc').symlink_to(self.runtime, target_is_directory=True)
        self.assertNotEqual(self.load().returncode, 0)

    def test_missing_descriptor(self):
        (self.candidate / 'session').unlink()
        self.assertNotEqual(self.load().returncode, 0)


if __name__ == '__main__':
    unittest.main()
