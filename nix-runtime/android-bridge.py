#!/usr/bin/env python3
"""Native Android bridge broker for the experimental full-Nix runtime.

Run this with the NATIVE (Bionic) Python, outside the Linux/glibc guest. The
guest cannot execute Bionic helpers, so it asks this broker over a private Unix
socket. Only an explicit allowlist of commands exists: ping, status,
battery-status and toast. There is no shell, no arbitrary command, clipboard,
contacts, location or credential access.
"""
import argparse
import errno
import json
import os
from pathlib import Path
import re
import selectors
import signal
import socket
import stat
import struct
import subprocess
import sys
import threading
import time

PROTOCOL = 1
VERSION = '1'
MAX_REQUEST = 4096
MAX_RESPONSE = 65536
MAX_HELPER_OUTPUT = 65536
MAX_CONNECTIONS = 8
CLIENT_DEADLINE = 5.0
DEFAULT_TIMEOUT = 10.0
MAX_TIMEOUT = 30.0
MAX_TOAST_TEXT = 256
SOCKET_NAME = 'bridge.sock'
HELPERS = {
    'battery-status': 'termux-battery-status',
    'toast': 'termux-toast',
}
COMMANDS = ('ping', 'status') + tuple(HELPERS)
BATTERY_KEYS = ('health', 'percentage', 'plugged', 'status', 'temperature', 'current')
GRAVITIES = ('top', 'middle', 'bottom')
COLOR = re.compile(r'#?[0-9A-Fa-f]{6}')
NAME = re.compile(r'[A-Za-z0-9._-]{1,64}')


class BridgeError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


def check_directory(path, uid, what):
    """Return path if it is a real (non-symlink) directory owned by uid and private."""
    try:
        info = os.lstat(path)
    except OSError as error:
        raise ValueError(f'{what} is not accessible: {path}: {error.strerror}') from error
    if not stat.S_ISDIR(info.st_mode):
        raise ValueError(f'{what} is not a real directory (symlinks are refused): {path}')
    if info.st_uid != uid:
        raise ValueError(f'{what} is not owned by UID {uid}: {path}')
    if info.st_mode & 0o022:
        raise ValueError(f'{what} must not be group- or world-writable: {path}')
    return path


def validate_prefix(prefix, uid):
    if not isinstance(prefix, str) or '\0' in prefix or not os.path.isabs(prefix):
        raise ValueError('--prefix must be an absolute native path')
    prefix = os.path.normpath(prefix)
    check_directory(prefix, uid, 'prefix')
    check_directory(os.path.join(prefix, 'bin'), uid, 'prefix bin directory')
    return prefix


def discover_helpers(prefix):
    """Map command name to helper path for executable regular files in $PREFIX/bin."""
    found = {}
    for command, name in HELPERS.items():
        path = os.path.join(prefix, 'bin', name)
        try:
            info = os.stat(path)
        except OSError:
            continue
        if stat.S_ISREG(info.st_mode) and os.access(path, os.X_OK):
            found[command] = path
    return found


def build_environment(prefix, mode, base, extra):
    """Environment for helpers: inherited native launch environment, or explicit."""
    if mode == 'inherit':
        env = dict(base)
    elif mode == 'explicit':
        env = {name: base[name] for name in ('HOME', 'TMPDIR', 'LANG') if name in base}
    else:
        raise ValueError('unknown environment mode')
    env.update(extra)
    env['PREFIX'] = prefix
    bindir = os.path.join(prefix, 'bin')
    parts = [part for part in env.get('PATH', '').split(':') if part and part != bindir]
    env['PATH'] = ':'.join([bindir] + parts)
    return env


def run_helper(argv, env, timeout, max_output=MAX_HELPER_OUTPUT):
    """Run argv without a shell; bound time and output; kill the process group."""
    process = subprocess.Popen(
        argv, env=env, shell=False, stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True, close_fds=True)
    selector = selectors.DefaultSelector()
    buffers = {process.stdout: bytearray(), process.stderr: bytearray()}
    for stream in buffers:
        os.set_blocking(stream.fileno(), False)
        selector.register(stream, selectors.EVENT_READ)
    deadline = time.monotonic() + timeout
    failure = None

    def kill():
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except PermissionError:
            process.kill()

    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                failure = 'timeout'
                break
            for key, _ in selector.select(min(remaining, 0.5)):
                chunk = os.read(key.fd, 4096)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                buffer = buffers[key.fileobj]
                buffer.extend(chunk)
                if len(buffer) > max_output:
                    failure = 'output'
                    break
            if failure:
                break
        if failure is None:
            try:
                process.wait(max(deadline - time.monotonic(), 0.01))
            except subprocess.TimeoutExpired:
                failure = 'timeout'
    finally:
        if failure is not None or process.poll() is None:
            kill()
        process.wait()
        selector.close()
        process.stdout.close()
        process.stderr.close()
    if failure == 'timeout':
        raise BridgeError('timeout', f'helper exceeded {timeout:g}s and was killed')
    if failure == 'output':
        raise BridgeError('output_too_large', f'helper output exceeded {max_output} bytes')
    return process.returncode, bytes(buffers[process.stdout]), bytes(buffers[process.stderr])


def expect_keys(args, allowed):
    if not isinstance(args, dict):
        raise BridgeError('bad_request', 'args must be an object')
    unknown = sorted(set(args) - set(allowed))
    if unknown:
        raise BridgeError('bad_request', 'unknown argument: ' + ', '.join(map(str, unknown))[:80])


def toast_argv(args):
    expect_keys(args, ('text', 'short', 'gravity', 'background', 'color'))
    text = args.get('text')
    if not isinstance(text, str) or not 1 <= len(text) <= MAX_TOAST_TEXT:
        raise BridgeError('bad_request', f'text must be a string of 1-{MAX_TOAST_TEXT} characters')
    if text.startswith('-') or any(ord(char) < 32 or ord(char) == 127 for char in text):
        raise BridgeError('bad_request', 'text must not start with "-" or contain control characters')
    argv = []
    if args.get('short', False) is True:
        argv.append('-s')
    elif args.get('short', False) is not False:
        raise BridgeError('bad_request', 'short must be a boolean')
    gravity = args.get('gravity')
    if gravity is not None:
        if gravity not in GRAVITIES:
            raise BridgeError('bad_request', 'gravity must be one of: ' + ', '.join(GRAVITIES))
        argv += ['-g', gravity]
    for key, flag in (('background', '-b'), ('color', '-c')):
        value = args.get(key)
        if value is not None:
            if not isinstance(value, str) or not COLOR.fullmatch(value):
                raise BridgeError('bad_request', f'{key} must be a hex colour such as #rrggbb')
            argv += [flag, value]
    return argv + [text]


def parse_battery(output):
    try:
        data = json.loads(output.decode('utf-8'))
    except (UnicodeDecodeError, ValueError) as error:
        raise BridgeError('helper_failed', 'battery helper did not return JSON') from error
    if not isinstance(data, dict):
        raise BridgeError('helper_failed', 'battery helper returned unexpected JSON')
    result = {}
    for key in BATTERY_KEYS:
        value = data.get(key)
        if isinstance(value, (str, int, float, bool)) or value is None:
            if key in data:
                result[key] = value
    return result


def stderr_hint(data):
    text = data[:200].decode('utf-8', 'replace')
    return ''.join(char if char.isprintable() else ' ' for char in text).strip()


class Bridge:
    def __init__(self, prefix, uid=None, env_mode='inherit', env_extra=None, environ=None,
                 timeout=DEFAULT_TIMEOUT):
        self.uid = os.getuid() if uid is None else uid
        self.prefix = validate_prefix(prefix, self.uid)
        self.allowed_uid = self.uid
        self.timeout = min(float(timeout), MAX_TIMEOUT)
        if self.timeout <= 0:
            raise ValueError('timeout must be positive')
        self.env = build_environment(
            self.prefix, env_mode, os.environ if environ is None else environ, env_extra or {})
        self.helper_lock = threading.Lock()

    def helpers(self):
        return discover_helpers(self.prefix)

    def run(self, command, argv):
        path = self.helpers().get(command)
        if path is None:
            raise BridgeError(
                'helper_missing',
                f'{HELPERS[command]} is not installed in the native prefix; '
                'install the termux-api package')
        with self.helper_lock:
            code, out, err = run_helper([path] + argv, self.env, self.timeout)
        if code != 0:
            raise BridgeError(
                'helper_failed', f'{HELPERS[command]} exited with status {code}: {stderr_hint(err)}')
        return out

    def dispatch(self, command, args):
        if command == 'ping':
            expect_keys(args, ())
            return {'pong': True}
        if command == 'status':
            expect_keys(args, ())
            helpers = self.helpers()
            return {
                'protocol': PROTOCOL, 'version': VERSION, 'uid': self.uid, 'pid': os.getpid(),
                'commands': list(COMMANDS),
                'helpers': {name: name in helpers for name in HELPERS},
                'limits': {
                    'request_bytes': MAX_REQUEST, 'response_bytes': MAX_RESPONSE,
                    'helper_output_bytes': MAX_HELPER_OUTPUT, 'timeout_seconds': self.timeout,
                    'toast_text': MAX_TOAST_TEXT,
                },
            }
        if command == 'battery-status':
            expect_keys(args, ())
            return parse_battery(self.run(command, []))
        if command == 'toast':
            self.run(command, toast_argv(args))
            return {'shown': True}
        raise BridgeError('unknown_command', 'unknown command; allowed: ' + ', '.join(COMMANDS))

    def handle(self, raw, peer_uid):
        """Handle one request line and return the response object."""
        try:
            if peer_uid != self.allowed_uid:
                raise BridgeError('forbidden', 'peer UID is not allowed')
            if len(raw) > MAX_REQUEST:
                raise BridgeError('request_too_large', f'request exceeds {MAX_REQUEST} bytes')
            try:
                request = json.loads(raw.decode('utf-8'))
            except (UnicodeDecodeError, ValueError) as error:
                raise BridgeError('bad_request', 'request is not valid UTF-8 JSON') from error
            if not isinstance(request, dict):
                raise BridgeError('bad_request', 'request must be an object')
            if request.get('v') != PROTOCOL:
                raise BridgeError('bad_request', f'unsupported protocol version; expected {PROTOCOL}')
            unknown = sorted(set(request) - {'v', 'command', 'args'})
            if unknown:
                raise BridgeError('bad_request', 'unknown request field')
            command = request.get('command')
            if not isinstance(command, str) or not NAME.fullmatch(command):
                raise BridgeError('bad_request', 'command must be a short string')
            return {'ok': True, 'result': self.dispatch(command, request.get('args', {}))}
        except BridgeError as error:
            return {'ok': False, 'error': {'code': error.code, 'message': error.message}}
        except Exception:  # never leak internals to the guest
            return {'ok': False, 'error': {'code': 'internal', 'message': 'internal broker error'}}

    def serve_connection(self, connection):
        try:
            connection.settimeout(CLIENT_DEADLINE)
            peer_uid = peer_credentials(connection)[1]
            raw = read_request(connection) if peer_uid == self.allowed_uid else b''
            response = self.handle(raw, peer_uid)
            data = json.dumps(response, separators=(',', ':')).encode() + b'\n'
            if len(data) > MAX_RESPONSE:
                data = b'{"ok":false,"error":{"code":"output_too_large","message":"response too large"}}\n'
            connection.sendall(data)
        except (OSError, BridgeError):
            pass
        finally:
            connection.close()


def peer_credentials(connection):
    pid, uid, gid = struct.unpack(
        '3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize('3i')))
    return pid, uid, gid


def read_request(connection):
    """Read one newline-terminated request, bounded by size and a deadline."""
    deadline = time.monotonic() + CLIENT_DEADLINE
    data = bytearray()
    while b'\n' not in data:
        if len(data) > MAX_REQUEST:
            return bytes(data)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BridgeError('timeout', 'client too slow')
        connection.settimeout(remaining)
        chunk = connection.recv(1024)
        if not chunk:
            break
        data.extend(chunk)
    return bytes(data.split(b'\n', 1)[0])


def prepare_socket_directory(directory, uid):
    """Create (mode 0700) or validate the private socket directory."""
    if not os.path.isabs(directory) or '\0' in directory:
        raise ValueError('--socket-dir must be an absolute native path')
    directory = os.path.normpath(directory)
    try:
        os.mkdir(directory, 0o700)
    except FileExistsError:
        pass
    except OSError as error:
        raise ValueError(f'cannot create socket directory: {error.strerror}') from error
    check_directory(directory, uid, 'socket directory')
    if os.lstat(directory).st_mode & 0o777 != 0o700:
        raise ValueError(f'socket directory must have mode 0700: {directory}')
    return directory


def bind_socket(directory, name, uid):
    """Bind inside directory via a relative name: native app paths exceed sun_path limits."""
    if not NAME.fullmatch(name):
        raise ValueError('invalid socket name')
    if os.path.lexists(os.path.join(directory, name)):
        info = os.lstat(os.path.join(directory, name))
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != uid:
            raise ValueError('refusing to replace a non-socket or foreign file')
        probe = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        dirfd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        old = os.open('.', os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fchdir(dirfd)
            probe.settimeout(1)
            try:
                probe.connect(name)
            except OSError as error:
                if error.errno not in (errno.ECONNREFUSED, errno.ENOENT):
                    raise ValueError(f'cannot probe existing socket: {error.strerror}') from error
                os.unlink(name)
            else:
                raise ValueError('a broker is already listening on this socket')
        finally:
            os.fchdir(old)
            os.close(old)
            os.close(dirfd)
            probe.close()
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    dirfd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    old = os.open('.', os.O_RDONLY | os.O_DIRECTORY)
    previous = os.umask(0o177)
    try:
        os.fchdir(dirfd)
        listener.bind(name)
        os.chmod(name, 0o600)
    except BaseException:
        listener.close()
        raise
    finally:
        os.umask(previous)
        os.fchdir(old)
        os.close(old)
        os.close(dirfd)
    listener.listen(MAX_CONNECTIONS)
    return listener


def serve(bridge, listener, stop=None):
    """Accept loop; one thread per connection, at most MAX_CONNECTIONS in flight."""
    slots = threading.BoundedSemaphore(MAX_CONNECTIONS)
    stop = stop or threading.Event()
    listener.settimeout(0.5)

    def worker(connection):
        try:
            bridge.serve_connection(connection)
        finally:
            slots.release()

    while not stop.is_set():
        try:
            connection, _ = listener.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        if not slots.acquire(blocking=False):
            try:
                connection.sendall(b'{"ok":false,"error":{"code":"busy","message":"too many connections"}}\n')
            except OSError:
                pass
            connection.close()
            continue
        threading.Thread(target=worker, args=(connection,), daemon=True).start()


def parse_environment(values):
    result = {}
    for value in values:
        name, separator, content = value.partition('=')
        if not separator or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name) or '\0' in content:
            raise ValueError(f'invalid --env entry: {name or value!r}')
        result[name] = content
    return result


def command_serve(args):
    uid = os.getuid()
    directory = prepare_socket_directory(args.socket_dir, uid)
    bridge = Bridge(args.prefix, uid=uid, env_mode=args.env_mode,
                    env_extra=parse_environment(args.env), timeout=args.timeout)
    listener = bind_socket(directory, args.socket_name, uid)
    stop = threading.Event()

    def terminate(_signal, _frame):
        stop.set()

    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    signal.signal(signal.SIGPIPE, signal.SIG_IGN)
    helpers = bridge.helpers()
    print(f'android-bridge: listening in {directory} as UID {uid}; '
          f'helpers: {", ".join(sorted(helpers)) or "none"}', file=sys.stderr, flush=True)
    try:
        serve(bridge, listener, stop)
    finally:
        listener.close()
        try:
            os.unlink(os.path.join(directory, args.socket_name))
        except OSError:
            pass
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    run = commands.add_parser('serve', help='listen on a private Unix socket (foreground)')
    run.add_argument('--prefix', required=True, help='native TermuxPP $PREFIX (real directory)')
    run.add_argument('--socket-dir', required=True,
                     help='native private socket directory (created 0700); in the guest this is the /tmp path')
    run.add_argument('--socket-name', default=SOCKET_NAME)
    run.add_argument('--timeout', type=float, default=DEFAULT_TIMEOUT,
                     help=f'per-helper timeout in seconds (default {DEFAULT_TIMEOUT:g}, max {MAX_TIMEOUT:g})')
    run.add_argument('--env-mode', choices=('inherit', 'explicit'), default='inherit',
                     help='helper environment: the broker launch environment (default) or only HOME/TMPDIR/LANG plus --env')
    run.add_argument('--env', action='append', default=[], metavar='NAME=VALUE',
                     help='additional helper environment variable (repeatable)')
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        return command_serve(args)
    except ValueError as error:
        print(f'android-bridge: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
