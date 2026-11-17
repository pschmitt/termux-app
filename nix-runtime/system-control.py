#!/usr/bin/env python3
"""Native controller for checks in a fresh, explicitly selected guest system."""
import argparse
import json
from pathlib import Path
import re
import signal
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
    if manifest.is_symlink() or manifest.stat().st_size > 65536:
        raise ValueError('invalid candidate manifest file')
    data = json.loads(manifest.read_text())
    if not isinstance(data, dict) or data.get('formatVersion') != 1 or data.get('stateVersion') != '1' or data.get('runtime') != 'droid-nix':
        raise ValueError('unsupported candidate manifest')
    generation = (candidate / 'generation').read_text().strip()
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, default=Path(__file__).resolve().parent)
    commands = parser.add_subparsers(dest='command', required=True)
    candidate = commands.add_parser('check', help='check a candidate without activating or selecting it')
    candidate.add_argument('candidate')
    candidate.add_argument('--thorough', action='store_true')
    args = parser.parse_args()
    try:
        status = check(args.runtime, args.candidate, args.thorough)
    except (OSError, ValueError, TypeError) as error:
        parser.exit(1, f'Cannot check system candidate: {error}\n')
    except subprocess.TimeoutExpired:
        parser.exit(124, 'System candidate check exceeded its execution budget.\n')
    except KeyboardInterrupt:
        parser.exit(130, 'System candidate check interrupted.\n')
    except ControllerTerminated:
        parser.exit(143, 'System candidate check terminated.\n')
    parser.exit(status if status >= 0 else 128 - status)


if __name__ == '__main__':
    main()
