"""Run inside the isolated Linux/glibc session, never the normal Termux prefix."""
import ctypes
import errno
import json
import os
from pathlib import Path
import pty
import select
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request

results = []


def check(name, function):
    try:
        detail = function()
        results.append({'name': name, 'passed': True, 'detail': detail})
    except Exception as error:
        results.append({'name': name, 'passed': False, 'error': str(error)})
    print(json.dumps(results[-1]), flush=True)


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=45, **kwargs).stdout.strip()


def glibc():
    version = os.confstr('CS_GNU_LIBC_VERSION')
    assert version.startswith('glibc '), version
    assert run('bash', '-c', 'printf bash-ok') == 'bash-ok'
    assert 'zsh' in run('zsh', '--version')
    return version


def filesystem():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        (path / 'file').write_text('hello')
        os.link(path / 'file', path / 'hard')
        (path / 'symbolic').symlink_to('file')
        assert (path / 'hard').read_text() == 'hello'
        assert (path / 'symbolic').resolve() == path / 'file'
        run('git', 'init', '-q', tmp)
        run('git', '-C', tmp, 'add', 'file')
        assert 'file' in run('git', '-C', tmp, 'diff', '--cached', '--name-only')
    return 'files, links and Git index'


def threads_and_signals():
    hits = []
    old = signal.signal(signal.SIGUSR1, lambda *_: hits.append(1))
    stop = threading.Event()
    def sender():
        while not stop.wait(0.002):
            os.kill(os.getpid(), signal.SIGUSR1)
    thread = threading.Thread(target=sender)
    thread.start()
    try:
        for _ in range(2000):
            os.stat('/etc/passwd')
        time.sleep(0.05)
    finally:
        stop.set()
        thread.join()
        signal.signal(signal.SIGUSR1, old)
    assert hits
    for _ in range(30):
        assert run('bash', '-c', 'printf child') == 'child'
    process = subprocess.Popen(['sleep', '30'])
    try:
        time.sleep(0.1)
        process.send_signal(signal.SIGINT)
        assert process.wait(timeout=5) == -signal.SIGINT
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
    return {'signals_received': len(hits), 'children': 30, 'sigint': 'passed'}


def alternate_stack():
    libc = ctypes.CDLL(None, use_errno=True)
    class Stack(ctypes.Structure):
        _fields_ = [('sp', ctypes.c_void_p), ('flags', ctypes.c_int), ('size', ctypes.c_size_t)]
    libc.mmap.restype = ctypes.c_void_p
    libc.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_long]
    libc.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    old = Stack()
    assert libc.sigaltstack(None, ctypes.byref(old)) == 0
    try:
        for _ in range(256):
            address = libc.mmap(None, 65536, 3, 0x22, -1, 0)
            assert address != ctypes.c_void_p(-1).value
            assert libc.sigaltstack(ctypes.byref(Stack(address, 0, 65536)), None) == 0
            assert libc.munmap(address, 65536) == -1 and ctypes.get_errno() == errno.EBUSY
            os.stat('/etc/passwd')
            assert libc.sigaltstack(ctypes.byref(Stack(None, 2, 0)), None) == 0
            assert libc.munmap(address, 65536) == 0
    finally:
        assert libc.sigaltstack(ctypes.byref(old), None) == 0
    return '256 register/protect/disable/unmap cycles; no retained test mappings'


def terminal():
    master, slave = pty.openpty()
    process = subprocess.Popen(['bash', '--noprofile', '--norc', '-i'], stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
    os.close(slave)
    try:
        os.write(master, b'printf "PTY_%s\\n" PASS\nexit\n')
        output = b''
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if select.select([master], [], [], 0.2)[0]:
                try:
                    data = os.read(master, 65536)
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not data:
                    break
                output += data
            if process.poll() is not None:
                break
        assert process.wait(timeout=5) == 0
        assert b'PTY_PASS' in output, output.decode(errors='replace')
        return 'PTY and interactive Bash; controlling-terminal/job-control tested separately'
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(master)


def network():
    assert socket.getaddrinfo('cache.nixos.org', 443)
    with urllib.request.urlopen('https://cache.nixos.org/nix-cache-info', timeout=20) as response:
        body = response.read(4096).decode()
    assert 'StoreDir: /nix/store' in body
    return 'DNS and verified HTTPS to official cache'


check('glibc-shells', glibc)
check('filesystem-and-git', filesystem)
check('threads-signals-processes', threads_and_signals)
check('alternate-stack-lifetime', alternate_stack)
check('pty-interactive-shell', terminal)
check('dns-https', network)
print('USABLE_SESSION_RESULTS=' + json.dumps(results), flush=True)
raise SystemExit(0 if all(row['passed'] for row in results) else 1)
