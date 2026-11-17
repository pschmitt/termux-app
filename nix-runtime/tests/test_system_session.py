#!/usr/bin/env python3
"""Exercise the native candidate descriptor without Android or a launcher."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
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

    def launch(self, *arguments):
        tools = self.runtime / 'tools'
        tools.mkdir()
        for name, value in [('uname', 'aarch64'), ('getconf', '4096')]:
            executable = tools / name
            executable.write_text(f'#!{sys.executable}\nprint({value!r})\n')
            executable.chmod(0o700)
        for name in ['env', 'readlink']:
            (tools / name).symlink_to(shutil.which(name))
        source = HELPER.with_name('session.sh').read_text()
        script = self.runtime / 'session.sh'
        script.write_text(source.replace('/system/bin/', str(tools) + '/'))
        shutil.copyfile(HELPER, self.runtime / HELPER.name)
        values = {
            'SESSION_USER': 'guest', 'SESSION_HOME': '/home/guest',
            'SESSION_PATH': '/legacy/bin', 'SESSION_SHELL': self.values[3],
            'SESSION_CERTIFICATE': '/legacy/cert', 'SESSION_TERMINFO': '/legacy/terminfo',
            'BOOTSTRAP_NIX': '/bootstrap/nix', 'BOOTSTRAP_BUSYBOX': '/bootstrap/busybox',
            'ANDROID_CA': '/native/cert',
        }
        (self.runtime / 'session.env').write_text(''.join(
            f'{key}={shlex.quote(value)}\n' for key, value in values.items()))
        shell = self.runtime / 'root' / self.values[3].lstrip('/')
        shell.parent.mkdir(parents=True)
        shell.touch(mode=0o700)
        (self.runtime / 'root/home/guest').mkdir(parents=True)
        launcher = self.runtime / 'launcher'
        launcher.write_text(f'#!{sys.executable}\n' + '''
import json, os, sys
print(json.dumps({'args': sys.argv[1:], 'path': os.environ['PATH'],
                  'certificate': os.environ['NIX_SSL_CERT_FILE'],
                  'home': os.environ['HOME']}))
''')
        launcher.chmod(0o700)
        return subprocess.run([os.environ.get('TEST_SHELL', 'bash'), str(script), *arguments],
                              env={'PATH': os.environ['PATH']}, text=True, capture_output=True)

    def test_candidate_session_uses_candidate_binding(self):
        result = self.launch('--system', self.name, '--', 'echo', 'probe')
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertIn(str(self.candidate / 'etc') + ':/etc!', data['args'])
        self.assertNotIn(str(self.runtime / 'root/etc') + ':/etc!', data['args'])
        self.assertEqual(data['path'], '/home/guest/.nix-profile/bin:' + self.values[2])
        self.assertEqual(data['certificate'], self.values[4])
        self.assertEqual(data['home'], '/home/guest')
        self.assertEqual(data['args'][-2:], ['echo', 'probe'])

    def test_default_session_ignores_prepared_candidate(self):
        result = self.launch('--', 'echo', 'probe')
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertIn(str(self.runtime / 'root/etc') + ':/etc!', data['args'])
        self.assertEqual(data['path'], '/home/guest/.nix-profile/bin:/legacy/bin')
        self.assertEqual(data['certificate'], '/legacy/cert')

    def test_bootstrap_ignores_prepared_candidate(self):
        result = self.launch('--bootstrap', '--', 'echo', 'probe')
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertIn(str(self.runtime / 'root/etc') + ':/etc!', data['args'])
        self.assertEqual(data['path'], '/bootstrap/nix/bin:/bootstrap/busybox/bin:/bin')
        self.assertEqual(data['certificate'], '/android/native/cert')

    def test_bad_candidate_does_not_start_launcher(self):
        result = self.launch('--system', '../escape', '--', 'echo', 'probe')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    def test_default_session_loads_selected_system(self):
        (self.runtime / 'current-system').write_text(self.name + '\n')
        result = self.launch('--', 'echo', 'probe')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(str(self.candidate / 'etc') + ':/etc!', json.loads(result.stdout)['args'])

    def test_legacy_bypasses_selected_system(self):
        (self.runtime / 'current-system').write_text('missing-candidate\n')
        result = self.launch('--legacy', '--', 'echo', 'probe')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(str(self.runtime / 'root/etc') + ':/etc!', json.loads(result.stdout)['args'])

    def test_bootstrap_bypasses_broken_selected_system(self):
        (self.runtime / 'current-system').write_text('../invalid\n')
        result = self.launch('--bootstrap', '--', 'echo', 'probe')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['path'], '/bootstrap/nix/bin:/bootstrap/busybox/bin:/bin')

    def test_malformed_selection_fails_closed(self):
        (self.runtime / 'current-system').write_text(self.name + '\nextra')
        result = self.launch('--', 'echo', 'probe')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    def test_empty_selection_uses_original_session(self):
        (self.runtime / 'current-system').write_text('\n')
        result = self.launch('--', 'echo', 'probe')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['path'], '/home/guest/.nix-profile/bin:/legacy/bin')

    def test_dangling_selection_is_rejected(self):
        (self.runtime / 'current-system').symlink_to(self.runtime / 'missing')
        result = self.launch('--', 'echo', 'probe')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    def test_special_selection_is_rejected(self):
        os.mkfifo(self.runtime / 'current-system')
        result = self.launch('--', 'echo', 'probe')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    def test_missing_candidate_argument(self):
        result = self.launch('--system')
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main()
