#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import signal
import socket
import subprocess
import time
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
                     'homeManager': self.home, 'activationSupported': True}
        self.save()
        self.baseline = '/nix/store/' + 'c' * 32 + '-home-manager-generation'
        activate = self.runtime / 'root' / self.baseline.lstrip('/') / 'activate'
        activate.parent.mkdir(parents=True)
        activate.touch()
        (self.runtime / 'root/nix/var/nix/gcroots').mkdir(parents=True)

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

    def test_switch_checks_activates_checks_then_publishes(self):
        calls = []
        def runner(command, timeout):
            calls.append(command)
            self.assertFalse((self.runtime / 'current-system').exists())
            if len(calls) == 2:
                self.assertEqual(module.read_state(self.runtime, 'system-pending.json')['phase'], 'activating')
            return 0
        self.assertEqual(module.switch(self.runtime, self.name, self.baseline, runner), 0)
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[1][-1], self.home + '/activate')
        self.assertEqual((self.runtime / 'current-system').read_text(), self.name + '\n')
        self.assertEqual(module.read_state(self.runtime, 'system-state.json')['previousHomeManager'], self.baseline)
        self.assertFalse((self.runtime / 'system-pending.json').exists())
        self.assertEqual((self.runtime / 'root/nix/var/nix/gcroots/termuxpp-system-baseline').readlink(), Path(self.baseline))

    def test_switch_requires_available_baseline(self):
        for baseline in [None, '/tmp/not-store', '/nix/store/' + 'd' * 32 + '-missing']:
            runner = Mock()
            with self.subTest(baseline=baseline), self.assertRaises(ValueError):
                module.switch(self.runtime, self.name, baseline, runner)
            runner.assert_not_called()

    def test_failed_preflight_never_activates_or_selects(self):
        runner = Mock(return_value=7)
        self.assertEqual(module.switch(self.runtime, self.name, self.baseline, runner), 7)
        self.assertEqual(runner.call_count, 1)
        self.assertFalse((self.runtime / 'current-system').exists())
        self.assertFalse((self.runtime / 'system-pending.json').exists())

    def test_failed_activation_retains_recovery_journal(self):
        runner = Mock(side_effect=[0, 7])
        with self.assertRaisesRegex(ValueError, 'recovery is required'):
            module.switch(self.runtime, self.name, self.baseline, runner)
        self.assertIsNotNone(module.read_state(self.runtime, 'system-pending.json'))
        self.assertFalse((self.runtime / 'current-system').exists())
        with self.assertRaisesRegex(ValueError, 'recover first'):
            module.switch(self.runtime, self.name, self.baseline, Mock())

    def test_recover_reactivates_baseline_without_candidate_selection(self):
        with self.assertRaises(ValueError):
            module.switch(self.runtime, self.name, self.baseline, Mock(side_effect=[0, 7]))
        runner = Mock(return_value=0)
        self.assertEqual(module.recover(self.runtime, runner), 0)
        runner.assert_called_once_with(module.session_command(self.runtime, None, [self.baseline + '/activate']), timeout=1800)
        self.assertEqual((self.runtime / 'current-system').read_text(), '\n')
        self.assertFalse((self.runtime / 'system-pending.json').exists())

    def test_failed_recovery_retains_journal(self):
        with self.assertRaises(ValueError):
            module.switch(self.runtime, self.name, self.baseline, Mock(side_effect=[0, 7]))
        with self.assertRaises(ValueError):
            module.recover(self.runtime, Mock(return_value=7))
        self.assertTrue((self.runtime / 'system-pending.json').exists())

    def test_postcheck_failure_requires_recovery(self):
        with self.assertRaisesRegex(ValueError, 'post-activation'):
            module.switch(self.runtime, self.name, self.baseline, Mock(side_effect=[0, 0, 7]))
        self.assertTrue((self.runtime / 'system-pending.json').exists())
        self.assertFalse((self.runtime / 'current-system').exists())

    def test_interrupt_leaves_recovery_journal(self):
        with self.assertRaises(KeyboardInterrupt):
            module.switch(self.runtime, self.name, self.baseline, Mock(side_effect=[0, KeyboardInterrupt()]))
        self.assertTrue((self.runtime / 'system-pending.json').exists())

    def test_rollback_to_baseline_then_forward_to_retained_system(self):
        module.switch(self.runtime, self.name, self.baseline, Mock(return_value=0))
        runner = Mock(return_value=0)
        self.assertEqual(module.rollback(self.runtime, runner), 0)
        self.assertIn('--legacy', runner.call_args.args[0])
        self.assertEqual((self.runtime / 'current-system').read_text(), '\n')
        self.assertEqual(module.rollback(self.runtime, Mock(return_value=0)), 0)
        self.assertEqual((self.runtime / 'current-system').read_text(), self.name + '\n')

    def test_generation_listing_marks_selected_system(self):
        module.switch(self.runtime, self.name, self.baseline, Mock(return_value=0))
        self.assertEqual(module.generations(self.runtime), [
            {'generation': self.name, 'homeManager': self.home, 'current': True, 'previous': False}])

    def test_concurrent_controller_is_rejected(self):
        with module.controller_lock(self.runtime), self.assertRaises(BlockingIOError):
            module.switch(self.runtime, self.name, self.baseline, Mock(return_value=0))

    def test_selection_publication_failure_can_recover(self):
        def partial_publication(runtime, state):
            module.atomic_write(runtime / 'current-system', state['current'] + '\n')
            raise OSError('fixture write failure after selection')
        with patch.object(module, 'publish_selection', side_effect=partial_publication):
            with self.assertRaises(OSError):
                module.switch(self.runtime, self.name, self.baseline, Mock(return_value=0))
        self.assertEqual(module.read_state(self.runtime, 'system-pending.json')['phase'], 'publishing')
        self.assertEqual(module.recover(self.runtime, Mock(return_value=0)), 0)
        self.assertEqual((self.runtime / 'current-system').read_text(), '\n')

    def test_assembly_only_candidate_cannot_activate(self):
        self.data['activationSupported'] = False
        self.save()
        runner = Mock()
        with self.assertRaisesRegex(ValueError, 'does not support'):
            module.switch(self.runtime, self.name, self.baseline, runner)
        runner.assert_not_called()

    def test_special_generation_record_is_rejected_without_reading(self):
        record = self.candidate / 'generation'
        record.unlink()
        module.os.mkfifo(record)
        with self.assertRaisesRegex(ValueError, 'generation record'):
            module.candidate_manifest(self.runtime, self.name)

    def test_special_state_record_is_rejected_without_reading(self):
        module.os.mkfifo(self.runtime / 'system-state.json')
        with self.assertRaisesRegex(ValueError, 'not regular'):
            module.read_state(self.runtime, 'system-state.json')

    def test_stale_rollback_state_is_rejected(self):
        runner = Mock()
        with self.assertRaisesRegex(ValueError, 'state changed'):
            module.switch(self.runtime, self.name, runner=runner, expected_state={'stale': True})
        runner.assert_not_called()

    def test_management_allowlist_rejects_arbitrary_commands(self):
        for request in [{'command': 'exec', 'candidate': self.name},
                        {'command': 'list-generations', 'argv': ['sh']},
                        {'command': 'switch', 'candidate': '../escape'},
                        {'command': 'rollback', 'candidate': self.name}, None]:
            with self.subTest(request=request), self.assertRaises(ValueError):
                module.dispatch(self.runtime, request)

    def test_management_switch_dispatches_only_selected_candidate(self):
        with patch.object(module, 'switch', return_value=7) as switch:
            self.assertEqual(module.dispatch(self.runtime, {'command': 'switch', 'candidate': self.name}), {'status': 7})
            switch.assert_called_once_with(self.runtime, self.name)

    def test_real_management_socket_and_duplicate_server(self):
        path = self.runtime / 'nixpp.sock'
        command = [sys.executable, '-I', str(Path(module.__file__)), '--runtime', str(self.runtime),
                   'serve', '--socket', str(path)]
        process = subprocess.Popen(command)
        try:
            deadline = time.monotonic() + 5
            while True:
                try:
                    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    client.connect(str(path))
                    break
                except OSError:
                    client.close()
                    if process.poll() is not None or time.monotonic() >= deadline:
                        self.fail('management server did not become ready')
                    time.sleep(0.01)
            with client:
                client.settimeout(5)
                client.sendall(b'{"command":"list-generations"}\n')
                result = json.loads(client.recv(65536))
                self.assertEqual(result['status'], 0)
                self.assertEqual(result['generations'][0]['generation'], self.name)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            duplicate = subprocess.run(command, timeout=5)
            self.assertEqual(duplicate.returncode, 0)
            self.assertTrue(path.exists())
        finally:
            process.terminate()
            process.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
