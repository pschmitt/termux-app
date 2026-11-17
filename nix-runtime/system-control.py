#!/usr/bin/env python3
"""Native controller for checks in a fresh, explicitly selected guest system."""
import argparse
from contextlib import contextmanager
import fcntl
import json
from pathlib import Path
import re
import signal
import socket
import stat
import struct
import subprocess
import os

STORE = re.compile(r'/nix/store/[a-z0-9]{32}-[a-zA-Z0-9+._-]+')
CANDIDATE = re.compile(r'[a-z0-9]{32}-[a-zA-Z0-9+._-]+')


def candidate_manifest(runtime, name):
    if not CANDIDATE.fullmatch(name):
        raise ValueError('invalid candidate name')
    candidates = runtime / 'system-candidates'
    candidate = candidates / name
    if candidates.is_symlink() or candidate.is_symlink():
        raise ValueError('candidate directory must not be a symlink')
    manifest = candidate / 'manifest.json'
    if manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > 65536:
        raise ValueError('invalid candidate manifest file')
    data = json.loads(manifest.read_text())
    if not isinstance(data, dict) or data.get('formatVersion') != 1 or data.get('stateVersion') != '1' or data.get('runtime') != 'droid-nix':
        raise ValueError('unsupported candidate manifest')
    record = candidate / 'generation'
    if record.is_symlink() or not record.is_file() or record.stat().st_size > 4096:
        raise ValueError('invalid candidate generation record')
    generation = record.read_text().strip()
    if not STORE.fullmatch(generation) or Path(generation).name != name:
        raise ValueError('candidate generation does not match its name')
    home = data.get('homeManager')
    if not isinstance(home, str) or not STORE.fullmatch(home):
        raise ValueError('candidate needs a store-backed Home Manager generation')
    return data


class ControllerTerminated(Exception):
    pass


def terminate(_signum, _frame):
    raise ControllerTerminated()


def stop_session(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def run_session(command, timeout):
    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        process = subprocess.Popen(command, start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except BaseException:
            stop_session(process)
            raise
    finally:
        signal.signal(signal.SIGTERM, previous)


def check(runtime, name, thorough=False, runner=run_session):
    runtime = runtime.resolve(strict=True)
    data = candidate_manifest(runtime, name)
    home = data['homeManager']
    command = ['/system/bin/sh', str(runtime / 'session.sh'), '--system', name, '--',
               home + '/home-path/bin/full-nix-check-runtime', '--isolated', '--generation', home]
    if thorough:
        command.append('--thorough')
    return runner(command, timeout=1200)


def atomic_write(path, text):
    temporary = path.with_name(path.name + '.new')
    if temporary.is_symlink() or path.is_symlink():
        raise ValueError('state files must not be symlinks')
    with open(temporary, 'w', opener=lambda name, flags: os.open(name, flags | os.O_NOFOLLOW, 0o600)) as stream:
        stream.write(text)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


@contextmanager
def controller_lock(runtime):
    path = runtime / 'system-control.lock'
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)


def read_state(runtime, filename):
    path = runtime / filename
    if path.is_symlink():
        raise ValueError('state files must not be symlinks')
    if not path.exists():
        return None
    if not path.is_file() or path.stat().st_size > 65536:
        raise ValueError('state file is not regular or exceeds size limit')
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError('invalid system state')
    return value


def write_state(runtime, filename, value):
    atomic_write(runtime / filename, json.dumps(value, sort_keys=True) + '\n')


def session_command(runtime, name, arguments):
    selection = ['--legacy'] if name is None else ['--system', name]
    return ['/system/bin/sh', str(runtime / 'session.sh'), *selection, '--', *arguments]


def activate_home(runtime, name, home, runner):
    if not isinstance(home, str) or not STORE.fullmatch(home):
        raise ValueError('invalid Home Manager generation')
    status = runner(session_command(runtime, name, [home + '/activate']), timeout=1800)
    if status:
        raise ValueError(f'Home Manager activation failed with status {status}; recovery is required')


def publish_selection(runtime, state):
    name = state['current']
    if name is not None and not CANDIDATE.fullmatch(name):
        raise ValueError('invalid current system')
    atomic_write(runtime / 'current-system', (name or '') + '\n')
    write_state(runtime, 'system-state.json', state)


def switch(runtime, name, previous_home=None, runner=run_session, expected_state=None):
    runtime = runtime.resolve(strict=True)
    with controller_lock(runtime):
        if read_state(runtime, 'system-pending.json') is not None:
            raise ValueError('an unfinished activation exists; run recover first')
        data = candidate_manifest(runtime, name)
        if data.get('activationSupported') is not True:
            raise ValueError('candidate does not support coordinated activation')
        old = read_state(runtime, 'system-state.json')
        if expected_state is not None and old != expected_state:
            raise ValueError('system state changed; retry rollback')
        if old is None:
            if not isinstance(previous_home, str) or not STORE.fullmatch(previous_home):
                raise ValueError('first migration requires the verified previous Home Manager generation')
            old = {'current': None, 'homeManager': previous_home, 'previous': None,
                   'previousHomeManager': None}
            if not (runtime / 'root' / previous_home.lstrip('/') / 'activate').is_file():
                raise ValueError('previous Home Manager generation is unavailable')
            root = runtime / 'root/nix/var/nix/gcroots/termuxpp-system-baseline'
            if root.is_symlink():
                if os.readlink(root) != previous_home:
                    raise ValueError('baseline GC root does not match the previous generation')
            else:
                root.symlink_to(previous_home)
        elif previous_home is not None:
            raise ValueError('previous Home Manager may only be supplied for the first migration')
        if old['current'] == name:
            return 0
        status = check(runtime, name, thorough=True, runner=runner)
        if status:
            return status
        new = {'current': name, 'homeManager': data['homeManager'],
               'previous': old['current'], 'previousHomeManager': old['homeManager']}
        journal = {'before': old, 'after': new, 'phase': 'activating'}
        write_state(runtime, 'system-pending.json', journal)
        activate_home(runtime, name, data['homeManager'], runner)
        status = check(runtime, name, thorough=True, runner=runner)
        if status:
            raise ValueError(f'post-activation check failed with status {status}; recovery is required')
        journal['phase'] = 'publishing'
        write_state(runtime, 'system-pending.json', journal)
        publish_selection(runtime, new)
        (runtime / 'system-pending.json').unlink()
        return 0


def recover(runtime, runner=run_session):
    runtime = runtime.resolve(strict=True)
    with controller_lock(runtime):
        journal = read_state(runtime, 'system-pending.json')
        if journal is None:
            raise ValueError('no unfinished activation to recover')
        old = journal['before']
        if old['current'] is not None:
            candidate_manifest(runtime, old['current'])
        activate_home(runtime, old['current'], old['homeManager'], runner)
        publish_selection(runtime, old)
        (runtime / 'system-pending.json').unlink()
        return 0


def rollback(runtime, runner=run_session):
    runtime = runtime.resolve(strict=True)
    state = read_state(runtime, 'system-state.json')
    if state is None or state.get('previousHomeManager') is None:
        raise ValueError('no previous generation is retained')
    if state['previous'] is not None:
        return switch(runtime, state['previous'], runner=runner, expected_state=state)
    with controller_lock(runtime):
        if read_state(runtime, 'system-pending.json') is not None:
            raise ValueError('an unfinished activation exists; run recover first')
        if read_state(runtime, 'system-state.json') != state:
            raise ValueError('system state changed; retry rollback')
        old = {'current': None, 'homeManager': state['previousHomeManager'],
               'previous': state['current'], 'previousHomeManager': state['homeManager']}
        write_state(runtime, 'system-pending.json', {'before': state, 'after': old, 'phase': 'activating'})
        activate_home(runtime, None, old['homeManager'], runner)
        publish_selection(runtime, old)
        (runtime / 'system-pending.json').unlink()
        return 0


def generations(runtime):
    state = read_state(runtime, 'system-state.json') or {}
    result = []
    candidates = runtime / 'system-candidates'
    if candidates.is_symlink():
        raise ValueError('candidate directory must not be a symlink')
    if candidates.exists():
        for entry in sorted(candidates.iterdir()):
            if entry.name.startswith('.'):
                continue
            data = candidate_manifest(runtime, entry.name)
            result.append({'generation': entry.name, 'homeManager': data['homeManager'],
                           'current': entry.name == state.get('current'),
                           'previous': entry.name == state.get('previous')})
    return result


def dispatch(runtime, request):
    if not isinstance(request, dict) or set(request) - {'command', 'candidate'}:
        raise ValueError('invalid management request')
    command = request.get('command')
    if command in ('switch', 'check'):
        name = request.get('candidate')
        if not isinstance(name, str) or not CANDIDATE.fullmatch(name):
            raise ValueError('invalid candidate name')
        status = switch(runtime, name) if command == 'switch' else check(runtime, name, thorough=True)
        return {'status': status}
    if 'candidate' in request:
        raise ValueError('unexpected candidate argument')
    if command == 'list-generations':
        return {'status': 0, 'generations': generations(runtime)}
    if command == 'rollback':
        return {'status': rollback(runtime)}
    if command == 'recover':
        return {'status': recover(runtime)}
    raise ValueError('unsupported management command')


def serve(runtime, path):
    runtime = runtime.resolve(strict=True)
    info = path.parent.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o022:
        raise ValueError('management socket needs an owned, non-writable-by-others directory')
    descriptor = os.open(runtime / 'system-server.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    bound = False
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return 0
        if path.exists() or path.is_symlink():
            info = path.lstat()
            if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
                raise ValueError('refusing to replace a non-socket or foreign socket')
            path.unlink()
        server.bind(str(path))
        bound = True
        path.chmod(0o600)
        server.listen(4)
        while True:
            connection, _ = server.accept()
            with connection:
                uid = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[1]
                if uid != os.getuid():
                    continue
                connection.settimeout(5)
                try:
                    payload = bytearray()
                    while not payload.endswith(b'\n'):
                        chunk = connection.recv(4097 - len(payload))
                        if not chunk or len(payload) + len(chunk) > 4096:
                            raise ValueError('invalid management request size')
                        payload.extend(chunk)
                    response = dispatch(runtime, json.loads(payload))
                except (OSError, ValueError, TypeError, KeyError, subprocess.TimeoutExpired) as error:
                    response = {'status': 1, 'error': str(error)}
                try:
                    connection.sendall(json.dumps(response).encode() + b'\n')
                except OSError:
                    pass
    finally:
        server.close()
        if bound:
            path.unlink(missing_ok=True)
        os.close(descriptor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, default=Path(__file__).resolve().parent)
    commands = parser.add_subparsers(dest='command', required=True)
    candidate = commands.add_parser('check', help='check a candidate without activating or selecting it')
    candidate.add_argument('candidate')
    candidate.add_argument('--thorough', action='store_true')
    activation = commands.add_parser('switch', help='activate matching Home Manager, then select the system')
    activation.add_argument('candidate')
    activation.add_argument('--previous-home-manager', help='verified active HM store path; first migration only')
    commands.add_parser('recover', help='restore the generation recorded before an interrupted activation')
    commands.add_parser('rollback', help='activate and select the retained previous generation')
    commands.add_parser('list-generations', help='list prepared system generations as JSON')
    server = commands.add_parser('serve', help='serve only system-management operations to same-UID guest clients')
    server.add_argument('--socket', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'check':
            status = check(args.runtime, args.candidate, args.thorough)
        elif args.command == 'switch':
            status = switch(args.runtime, args.candidate, args.previous_home_manager)
        elif args.command == 'recover':
            status = recover(args.runtime)
        elif args.command == 'rollback':
            status = rollback(args.runtime)
        elif args.command == 'serve':
            status = serve(args.runtime, args.socket)
        else:
            print(json.dumps(generations(args.runtime), sort_keys=True))
            status = 0
    except (OSError, ValueError, TypeError, KeyError) as error:
        parser.exit(1, f'Cannot manage system candidate: {error}\n')
    except subprocess.TimeoutExpired:
        parser.exit(124, 'System candidate check exceeded its execution budget.\n')
    except KeyboardInterrupt:
        parser.exit(130, 'System candidate check interrupted.\n')
    except ControllerTerminated:
        parser.exit(143, 'System candidate check terminated.\n')
    parser.exit(status if status >= 0 else 128 - status)


if __name__ == '__main__':
    main()
