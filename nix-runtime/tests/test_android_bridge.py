import importlib.util
import json
import os
from pathlib import Path
import socket
import stat
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('android_bridge', ROOT / 'android-bridge.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

CLIENT = os.environ.get('ANDROID_BRIDGE_CLIENT')
BATTERY = {'health': 'GOOD', 'percentage': 87, 'plugged': 'UNPLUGGED', 'status': 'DISCHARGING',
           'temperature': 25.5, 'current': -300, 'secret': 'dropped'}


class Fixture:
    """Temporary native prefix whose helpers are Python scripts."""

    def __init__(self, test):
        self.directory = Path(tempfile.mkdtemp(prefix='bridge-'))
        test.addCleanup(self.cleanup)
        self.prefix = self.directory / 'usr'
        (self.prefix / 'bin').mkdir(parents=True)
        self.prefix.chmod(0o700)
        (self.prefix / 'bin').chmod(0o700)
        self.log = self.directory / 'argv.json'

    def cleanup(self):
        import shutil
        shutil.rmtree(self.directory, ignore_errors=True)

    def helper(self, name, body):
        path = self.prefix / 'bin' / name
        path.write_text(f'#!{sys.executable}\nimport json, os, sys, time\n{body}\n')
        path.chmod(0o700)

    def standard(self):
        self.helper('termux-battery-status', f'print(json.dumps({BATTERY!r}))')
        self.helper('termux-toast', (
            f'open({str(self.log)!r}, "w").write(json.dumps([sys.argv[1:], os.environ.get("PREFIX"),'
            ' os.environ.get("PATH").split(":")[0]]))'))

    def bridge(self, **kwargs):
        return module.Bridge(str(self.prefix), **kwargs)


def call(bridge, request, uid=None):
    raw = request if isinstance(request, bytes) else json.dumps(request).encode()
    return bridge.handle(raw, os.getuid() if uid is None else uid)


def command(name, **args):
    return {'v': 1, 'command': name, 'args': args}


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture(self)
        self.fixture.standard()
        self.bridge = self.fixture.bridge()

    def error(self, response):
        self.assertFalse(response['ok'], response)
        return response['error']['code']

    def test_ping_and_status_have_no_side_effects(self):
        self.assertEqual(call(self.bridge, command('ping')), {'ok': True, 'result': {'pong': True}})
        result = call(self.bridge, command('status'))['result']
        self.assertEqual(result['commands'], ['ping', 'status', 'battery-status', 'toast'])
        self.assertEqual(result['helpers'], {'battery-status': True, 'toast': True})
        self.assertEqual(result['uid'], os.getuid())
        self.assertFalse(self.fixture.log.exists())

    def test_status_reports_missing_helper(self):
        (self.fixture.prefix / 'bin/termux-toast').unlink()
        result = call(self.bridge, command('status'))['result']
        self.assertEqual(result['helpers'], {'battery-status': True, 'toast': False})
        self.assertEqual(self.error(call(self.bridge, command('toast', text='x'))), 'helper_missing')

    def test_battery_allowlists_keys(self):
        result = call(self.bridge, command('battery-status'))['result']
        self.assertEqual(result['percentage'], 87)
        self.assertNotIn('secret', result)

    def test_toast_arguments(self):
        response = call(self.bridge, command(
            'toast', text='hello world', short=True, gravity='top', background='#112233', color='ffffff'))
        self.assertEqual(response, {'ok': True, 'result': {'shown': True}})
        argv, prefix, path0 = json.loads(self.fixture.log.read_text())
        self.assertEqual(argv, ['-s', '-g', 'top', '-b', '#112233', '-c', 'ffffff', 'hello world'])
        self.assertEqual(prefix, str(self.fixture.prefix))
        self.assertEqual(path0, str(self.fixture.prefix / 'bin'))

    def test_toast_validation(self):
        bad = [
            dict(), dict(text=''), dict(text='x' * 257), dict(text=5), dict(text='-s'),
            dict(text='a\nb'), dict(text='a\x00b'), dict(text='x', gravity='left'),
            dict(text='x', background='red'), dict(text='x', color='#12'), dict(text='x', short='yes'),
            dict(text='x', extra=1), dict(text='x', background=['#112233']),
        ]
        for args in bad:
            with self.subTest(args=args):
                self.assertEqual(self.error(call(self.bridge, command('toast', **args))), 'bad_request')
        self.assertFalse(self.fixture.log.exists())

    def test_unknown_and_dangerous_commands(self):
        for name in ('exec', 'sh', 'clipboard', 'termux-clipboard-get', 'contacts', 'location',
                     'termux-toast', '../x', 'ping; id', 'BATTERY-STATUS', ''):
            with self.subTest(name=name):
                code = self.error(call(self.bridge, command(name)))
                self.assertIn(code, ('unknown_command', 'bad_request'))
        for args in ({'argv': ['id']}, {'x': 1}):
            self.assertEqual(self.error(call(self.bridge, command('ping', **args))), 'bad_request')
            self.assertEqual(self.error(call(self.bridge, command('battery-status', **args))), 'bad_request')

    def test_malformed_requests(self):
        for raw in (b'', b'not json', b'[]', b'"ping"', b'\xff\xfe', b'{}',
                    json.dumps({'v': 2, 'command': 'ping'}).encode(),
                    json.dumps({'v': 1, 'command': 5}).encode(),
                    json.dumps({'v': 1, 'command': 'ping', 'cmd': 'id'}).encode(),
                    json.dumps({'v': 1, 'command': 'ping', 'args': []}).encode()):
            with self.subTest(raw=raw):
                self.assertEqual(self.error(call(self.bridge, raw)), 'bad_request')

    def test_oversized_request(self):
        raw = json.dumps(command('toast', text='x' * 8000)).encode()
        self.assertEqual(self.error(call(self.bridge, raw)), 'request_too_large')

    def test_uid_validation(self):
        self.assertEqual(self.error(call(self.bridge, command('ping'), uid=os.getuid() + 1)), 'forbidden')
        self.assertEqual(self.error(call(self.bridge, command('ping'), uid=0 if os.getuid() else 1)), 'forbidden')

    def test_helper_failure_is_reported(self):
        self.fixture.helper('termux-battery-status', 'sys.stderr.write("denied\\x1b[0m"); sys.exit(3)')
        response = call(self.bridge, command('battery-status'))
        self.assertEqual(self.error(response), 'helper_failed')
        self.assertIn('status 3', response['error']['message'])
        self.assertNotIn('\x1b', response['error']['message'])
        self.fixture.helper('termux-battery-status', 'print("not json")')
        self.assertEqual(self.error(call(self.bridge, command('battery-status'))), 'helper_failed')

    def test_helper_timeout_kills_process_group(self):
        marker = self.fixture.directory / 'survivor'
        self.fixture.helper('termux-battery-status', (
            'import subprocess\n'
            f'subprocess.Popen([sys.executable, "-c", "import time; time.sleep(2);'
            f' open({str(marker)!r}, \'w\').close()"])\n'
            'time.sleep(60)'))
        bridge = self.fixture.bridge(timeout=0.5)
        started = time.monotonic()
        self.assertEqual(self.error(call(bridge, command('battery-status'))), 'timeout')
        self.assertLess(time.monotonic() - started, 5)
        time.sleep(2.5)
        self.assertFalse(marker.exists())

    def test_helper_output_bound(self):
        self.fixture.helper('termux-battery-status', 'sys.stdout.write("x" * 200000)')
        self.assertEqual(self.error(call(self.bridge, command('battery-status'))), 'output_too_large')

    def test_stderr_flood_is_bounded(self):
        self.fixture.helper('termux-battery-status', 'sys.stderr.write("e" * 200000)')
        self.assertEqual(self.error(call(self.bridge, command('battery-status'))), 'output_too_large')

    def test_no_shell_interpretation(self):
        call(self.bridge, command('toast', text='$(touch pwned); `id` ; | &'))
        argv = json.loads(self.fixture.log.read_text())[0]
        self.assertEqual(argv, ['$(touch pwned); `id` ; | &'])

    def test_missing_helper(self):
        (self.fixture.prefix / 'bin/termux-battery-status').unlink()
        response = call(self.bridge, command('battery-status'))
        self.assertEqual(self.error(response), 'helper_missing')
        self.assertIn('termux-api', response['error']['message'])

    def test_non_executable_or_non_regular_helper_is_ignored(self):
        path = self.fixture.prefix / 'bin/termux-toast'
        path.chmod(0o600)
        self.assertFalse(self.bridge.helpers().get('toast'))
        path.unlink()
        path.mkdir()
        self.assertNotIn('toast', self.bridge.helpers())


class EnvironmentTests(unittest.TestCase):
    def test_inherit_and_explicit(self):
        fixture = Fixture(self)
        prefix = str(fixture.prefix)
        base = {'PATH': '/a:/b', 'HOME': '/h', 'LD_PRELOAD': '/x.so', 'SECRET': 's'}
        inherited = module.build_environment(prefix, 'inherit', base, {'X': '1'})
        self.assertEqual(inherited['LD_PRELOAD'], '/x.so')
        self.assertEqual(inherited['PATH'], f'{prefix}/bin:/a:/b')
        self.assertEqual(inherited['X'], '1')
        explicit = module.build_environment(prefix, 'explicit', base, {'X': '1'})
        self.assertEqual(explicit, {'HOME': '/h', 'X': '1', 'PREFIX': prefix, 'PATH': f'{prefix}/bin'})
        with self.assertRaises(ValueError):
            module.build_environment(prefix, 'other', base, {})
        self.assertEqual(module.parse_environment(['A=b=c', 'D=']), {'A': 'b=c', 'D': ''})
        for bad in ('1A=x', 'A', '=x', 'A-B=x'):
            with self.assertRaises(ValueError):
                module.parse_environment([bad])

    def test_helpers_see_environment(self):
        fixture = Fixture(self)
        fixture.helper('termux-battery-status', 'print(json.dumps({"status": os.environ.get("MARK", "")}))')
        for mode, expected in (('inherit', 'launch'), ('explicit', 'x')):
            bridge = fixture.bridge(env_mode=mode, environ={'MARK': 'launch', 'PATH': '/bin'},
                                    env_extra={'MARK': 'x'} if mode == 'explicit' else {})
            self.assertEqual(call(bridge, command('battery-status'))['result'], {'status': expected})


class ValidationTests(unittest.TestCase):
    def test_prefix_validation(self):
        fixture = Fixture(self)
        uid = os.getuid()
        self.assertEqual(module.validate_prefix(str(fixture.prefix), uid), str(fixture.prefix))
        with self.assertRaises(ValueError):
            module.validate_prefix('relative/usr', uid)
        with self.assertRaises(ValueError):
            module.validate_prefix(str(fixture.directory / 'missing'), uid)
        link = fixture.directory / 'link'
        link.symlink_to(fixture.prefix)
        with self.assertRaises(ValueError):
            module.validate_prefix(str(link), uid)
        with self.assertRaises(ValueError):
            module.validate_prefix(str(fixture.prefix), uid + 1)
        (fixture.prefix / 'bin').chmod(0o770)
        with self.assertRaises(ValueError):
            module.validate_prefix(str(fixture.prefix), uid)
        (fixture.prefix / 'bin').chmod(0o700)
        (fixture.prefix / 'bin').rename(fixture.prefix / 'real-bin')
        (fixture.prefix / 'bin').symlink_to('real-bin')
        with self.assertRaises(ValueError):
            module.validate_prefix(str(fixture.prefix), uid)

    def test_socket_directory(self):
        fixture = Fixture(self)
        uid = os.getuid()
        created = fixture.directory / 'sockets'
        module.prepare_socket_directory(str(created), uid)
        self.assertEqual(stat.S_IMODE(created.stat().st_mode), 0o700)
        created.chmod(0o750)
        with self.assertRaises(ValueError):
            module.prepare_socket_directory(str(created), uid)
        created.chmod(0o700)
        link = fixture.directory / 'sockets-link'
        link.symlink_to(created)
        with self.assertRaises(ValueError):
            module.prepare_socket_directory(str(link), uid)
        with self.assertRaises(ValueError):
            module.prepare_socket_directory('relative', uid)

    def test_timeout_is_capped(self):
        fixture = Fixture(self)
        self.assertEqual(fixture.bridge(timeout=1000).timeout, module.MAX_TIMEOUT)
        with self.assertRaises(ValueError):
            fixture.bridge(timeout=0)


class SocketTests(unittest.TestCase):
    def start(self, fixture=None, **kwargs):
        fixture = fixture or Fixture(self)
        fixture.standard()
        self.directory = module.prepare_socket_directory(str(fixture.directory / 'sock'), os.getuid())
        bridge = fixture.bridge(**kwargs)
        listener = module.bind_socket(self.directory, 'bridge.sock', os.getuid())
        stop = threading.Event()
        thread = threading.Thread(target=module.serve, args=(bridge, listener, stop))
        thread.start()

        def finish():
            stop.set()
            thread.join()
            listener.close()
        self.addCleanup(finish)
        return fixture, bridge

    def exchange(self, payload, shutdown=True):
        directory = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
        old = os.open('.', os.O_RDONLY | os.O_DIRECTORY)
        client = socket.socket(socket.AF_UNIX)
        try:
            os.fchdir(directory)
            client.settimeout(10)
            client.connect('bridge.sock')
        finally:
            os.fchdir(old)
            os.close(old)
            os.close(directory)
        with client:
            try:
                client.sendall(payload)
            except OSError:
                pass
            if shutdown:
                client.shutdown(socket.SHUT_WR)
            data = b''
            while True:
                try:
                    chunk = client.recv(4096)
                except OSError:
                    break
                if not chunk:
                    break
                data += chunk
        return json.loads(data) if data else None

    def test_socket_mode_and_roundtrip(self):
        self.start()
        self.assertEqual(stat.S_IMODE(os.lstat(os.path.join(self.directory, 'bridge.sock')).st_mode), 0o600)
        self.assertEqual(self.exchange(b'{"v":1,"command":"ping"}\n'), {'ok': True, 'result': {'pong': True}})
        self.assertEqual(self.exchange(b'{"v":1,"command":"ping"}'), {'ok': True, 'result': {'pong': True}})

    def test_peer_credentials(self):
        left, right = socket.socketpair()
        self.addCleanup(left.close)
        self.addCleanup(right.close)
        pid, uid, gid = module.peer_credentials(left)
        self.assertEqual((pid, uid, gid), (os.getpid(), os.getuid(), os.getgid()))

    def test_wrong_peer_uid_rejected_before_reading(self):
        fixture = Fixture(self)
        fixture.standard()
        bridge = fixture.bridge()
        bridge.allowed_uid = os.getuid() + 1
        left, right = socket.socketpair()
        thread = threading.Thread(target=bridge.serve_connection, args=(right,))
        left.settimeout(5)
        thread.start()
        data = left.recv(4096)
        thread.join()
        left.close()
        self.assertEqual(json.loads(data)['error']['code'], 'forbidden')

    def test_oversized_request_over_socket(self):
        self.start()
        response = self.exchange(b'{"v":1,"command":"toast","args":{"text":"' + b'x' * 10000 + b'"}}\n')
        self.assertEqual(response['error']['code'], 'request_too_large')
        response = self.exchange(b'x' * 100000)
        self.assertIn(response['error']['code'], ('request_too_large',))

    def test_slow_client_times_out(self):
        self.start()
        with unittest.mock.patch.object(module, 'CLIENT_DEADLINE', 0.5):
            started = time.monotonic()
            response = self.exchange(b'{"v":1,', shutdown=False)
        self.assertIsNone(response)
        self.assertLess(time.monotonic() - started, 8)

    def test_existing_listener_is_not_replaced_and_stale_socket_is(self):
        fixture, _ = self.start()
        with self.assertRaises(ValueError):
            module.bind_socket(self.directory, 'bridge.sock', os.getuid())
        stale = Path(self.directory) / 'stale.sock'
        old = socket.socket(socket.AF_UNIX)
        directory = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
        cwd = os.open('.', os.O_RDONLY | os.O_DIRECTORY)
        os.fchdir(directory)
        old.bind('stale.sock')
        old.close()
        os.fchdir(cwd)
        os.close(cwd)
        os.close(directory)
        self.assertTrue(stale.exists())
        replacement = module.bind_socket(self.directory, 'stale.sock', os.getuid())
        replacement.close()
        (Path(self.directory) / 'regular').write_text('x')
        with self.assertRaises(ValueError):
            module.bind_socket(self.directory, 'regular', os.getuid())

    def test_long_socket_directory(self):
        fixture = Fixture(self)
        nested = fixture.directory
        while len(str(nested)) < 130:
            nested = nested / ('d' * 20)
            nested.mkdir(mode=0o700)
        listener = module.bind_socket(module.prepare_socket_directory(str(nested), os.getuid()), 'bridge.sock', os.getuid())
        listener.close()
        self.assertTrue((nested / 'bridge.sock').exists())

    @unittest.skipUnless(CLIENT, 'set ANDROID_BRIDGE_CLIENT to the guest client script')
    def test_guest_client(self):
        import subprocess
        fixture, _ = self.start()
        sock = os.path.join(self.directory, 'bridge.sock')
        run = lambda *args, path=sock: subprocess.run(
            [sys.executable, '-I', CLIENT, '--socket', path, *args],
            capture_output=True, text=True, timeout=30)
        result = run('ping')
        self.assertEqual((result.returncode, json.loads(result.stdout)), (0, {'pong': True}))
        result = run('battery-status')
        self.assertEqual(json.loads(result.stdout)['percentage'], 87)
        self.assertEqual(run('toast', '-s', 'hi', 'there').returncode, 0)
        self.assertEqual(json.loads(fixture.log.read_text())[0], ['-s', 'hi there'])
        result = run('toast', '-g', 'left', 'x')
        self.assertEqual(result.returncode, 2)
        absent = run('ping', path=os.path.join(self.directory, 'missing.sock'))
        self.assertEqual(absent.returncode, 69)
        self.assertIn('not running', absent.stderr)
        self.assertIn('/system/bin/sh "$R/start-android-bridge.sh"', absent.stderr)


if __name__ == '__main__':
    unittest.main()
