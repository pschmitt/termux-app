#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch, call

spec = importlib.util.spec_from_file_location('system_control', Path(__file__).resolve().parents[1] / 'system-control.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.runtime = Path(self.temp.name)
        self.name = 'a' * 32 + '-termuxpp-system-test'
        self.candidate = self.runtime / 'system-candidates' / self.name
        self.candidate.mkdir(parents=True)
        (self.candidate / 'generation').write_text('/nix/store/' + self.name + '\n')
        self.home = '/nix/store/' + 'b' * 32 + '-home-manager-generation'
        self.data = {'formatVersion': 1, 'stateVersion': '1', 'runtime': 'droid-nix',
                     'homeManager': self.home, 'activationSupported': False}
        self.save()

    def save(self):
        (self.candidate / 'manifest.json').write_text(json.dumps(self.data))

    def test_fixed_check_in_fresh_candidate_session(self):
        runner = Mock(return_value=0)
        self.assertEqual(module.check(self.runtime, self.name, runner=runner), 0)
        runner.assert_called_once_with([
            '/system/bin/sh', str(self.runtime / 'session.sh'), '--system', self.name, '--',
            self.home + '/home-path/bin/full-nix-check-runtime', '--isolated', '--generation', self.home,
        ], timeout=1200)
        self.assertFalse((self.runtime / 'current-system').exists())
        self.assertEqual(len(list(self.candidate.iterdir())), 2)

    def test_thorough_and_failed_gate_status(self):
        runner = Mock(return_value=7)
        self.assertEqual(module.check(self.runtime, self.name, thorough=True, runner=runner), 7)
        self.assertEqual(runner.call_args.args[0][-1], '--thorough')

    def test_bad_names_never_launch(self):
        runner = Mock()
        for name in ['../escape', '/absolute', '', self.name + '/nested']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                module.check(self.runtime, name, runner=runner)
        runner.assert_not_called()

    def test_no_home_manager_never_launches(self):
        self.data['homeManager'] = None
        self.save()
        runner = Mock()
        with self.assertRaisesRegex(ValueError, 'Home Manager'):
            module.check(self.runtime, self.name, runner=runner)
        runner.assert_not_called()

    def test_manifest_paths_are_not_commands(self):
        self.data['homeManager'] = '$(false)'
        self.save()
        with self.assertRaises(ValueError):
            module.check(self.runtime, self.name, runner=Mock())

    def test_mismatched_generation_never_launches(self):
        (self.candidate / 'generation').write_text('/nix/store/' + 'c' * 32 + '-other\n')
        with self.assertRaisesRegex(ValueError, 'does not match'):
            module.check(self.runtime, self.name, runner=Mock())

    def test_unsupported_manifest(self):
        self.data['runtime'] = 'other'
        self.save()
        with self.assertRaisesRegex(ValueError, 'unsupported'):
            module.check(self.runtime, self.name, runner=Mock())

    def test_symlinked_candidate(self):
        target = self.runtime / 'retained'
        self.candidate.rename(target)
        self.candidate.symlink_to(target, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            module.check(self.runtime, self.name, runner=Mock())

    def test_real_exit_status(self):
        self.assertEqual(module.run_session([sys.executable, '-I', '-c', 'raise SystemExit(7)'], timeout=10), 7)

    def test_timeout_stops_only_owned_group(self):
        process = Mock(pid=12345)
        process.wait.side_effect = [subprocess.TimeoutExpired('test', 1), 0]
        previous = signal.getsignal(signal.SIGTERM)
        with patch.object(module.subprocess, 'Popen', return_value=process) as popen, patch.object(module.os, 'killpg') as kill:
            with self.assertRaises(subprocess.TimeoutExpired):
                module.run_session(['fixed-command'], timeout=1)
            popen.assert_called_once_with(['fixed-command'], start_new_session=True)
            kill.assert_called_once_with(12345, signal.SIGTERM)
        self.assertEqual(signal.getsignal(signal.SIGTERM), previous)

    def test_timeout_escalates_after_grace(self):
        process = Mock(pid=12345)
        process.wait.side_effect = [subprocess.TimeoutExpired('test', 1),
                                    subprocess.TimeoutExpired('test', 10), 0]
        with patch.object(module.subprocess, 'Popen', return_value=process), patch.object(module.os, 'killpg') as kill:
            with self.assertRaises(subprocess.TimeoutExpired):
                module.run_session(['fixed-command'], timeout=1)
            self.assertEqual(kill.call_args_list, [call(12345, signal.SIGTERM), call(12345, signal.SIGKILL)])

    def test_cancellation_cleans_up_and_restores_handler(self):
        for exception in [KeyboardInterrupt(), module.ControllerTerminated()]:
            process = Mock(pid=12345)
            process.wait.side_effect = [exception, 0]
            previous = signal.getsignal(signal.SIGTERM)
            with patch.object(module.subprocess, 'Popen', return_value=process), patch.object(module.os, 'killpg') as kill:
                with self.assertRaises(type(exception)):
                    module.run_session(['fixed-command'], timeout=1)
                kill.assert_called_once_with(12345, signal.SIGTERM)
            self.assertEqual(signal.getsignal(signal.SIGTERM), previous)


if __name__ == '__main__':
    unittest.main()
