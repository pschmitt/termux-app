import concurrent.futures
import ctypes
import errno
import json
import os
import pathlib
import signal
import subprocess
import sys

results = []

def check(name, action):
    try:
        detail = action()
        results.append({"test": name, "pass": True, "detail": detail})
    except Exception as exc:
        results.append({"test": name, "pass": False, "detail": repr(exc)})
    print(json.dumps(results[-1]), flush=True)

def run(argv, **kwargs):
    result = subprocess.run(argv, text=True, capture_output=True, timeout=90, **kwargs)
    if result.returncode:
        raise RuntimeError({"argv": argv, "code": result.returncode, "stderr": result.stderr[-3000:]})
    return result.stdout.strip()

def require(value, message):
    if not value:
        raise AssertionError(message)

def mapping():
    require(pathlib.Path('/etc/evaluation-marker').read_text() == 'guest-view\n', 'incorrect guest /etc')
    require(pathlib.Path('/android/etc/evaluation-marker').read_text() == 'host-view\n', 'incorrect host view')
    return 'distinct host and guest paths resolved'

def status():
    fields = dict(line.split(':', 1) for line in pathlib.Path('/proc/self/status').read_text().splitlines() if ':' in line)
    require(fields['TracerPid'].strip() == '0', 'unexpected ptrace tracer')
    require(fields['Seccomp'].strip() == '2', 'filter not active')
    return {key: fields.get(key, '').strip() for key in ['TracerPid', 'Seccomp', 'Seccomp_filters']}

def links():
    base = pathlib.Path('/tmp/links')
    base.mkdir()
    original = base / 'original'
    alias = base / 'alias'
    original.write_text('payload')
    os.link(original, alias)
    require(original.stat().st_ino == alias.stat().st_ino, 'inode mismatch')
    require(original.stat().st_nlink == 2, 'incorrect link count')
    require(not alias.is_symlink(), 'emulation leaked symlink')
    original.unlink()
    require(alias.read_text() == 'payload', 'unlink lost payload')
    require(alias.stat().st_nlink == 1, 'incorrect remaining count')
    return 'link, inode/count, lstat, unlink and read passed'

def symlinks():
    base = pathlib.Path('/tmp/symlinks')
    base.mkdir()
    (base / 'absolute').symlink_to('/etc/evaluation-marker')
    (base / 'relative').symlink_to('absolute')
    require((base / 'relative').read_text() == 'guest-view\n', 'symlink escaped guest view')
    return 'absolute and relative chains passed'

def raw_syscall():
    libc = ctypes.CDLL(None, use_errno=True)
    fd = libc.syscall(56, -100, b'/etc/evaluation-marker', 0, 0)
    if fd < 0:
        raise OSError(ctypes.get_errno(), 'raw openat')
    try:
        require(os.read(fd, 128) == b'guest-view\n', 'raw syscall bypassed mapping')
    finally:
        os.close(fd)
    return 'AArch64 raw openat translated'

def spawning():
    for _ in range(8):
        pid = os.posix_spawn('/bin/sh', ['sh', '-c', 'exit 7'], os.environ.copy())
        _, status = os.waitpid(pid, 0)
        require(os.waitstatus_to_exitcode(status) == 7, 'spawn exit status lost')
    require(run(['/bin/sh', '-c', 'printf abc | cat']) == 'abc', 'pipeline failed')
    require(run(['env', '-i', 'PATH=' + os.environ['PATH'], '/bin/sh', '-c', 'cat /etc/evaluation-marker']) == 'guest-view', 'cleared environment lost mapping')
    return 'posix_spawn x8, exit status 7, pipeline, env-cleared exec passed'

def threads():
    def worker(index):
        for iteration in range(100):
            require(pathlib.Path('/etc/evaluation-marker').read_text() == 'guest-view\n', 'thread path mismatch')
            name = pathlib.Path('/tmp/thread-' + str(index))
            name.write_text(str(iteration))
            require(name.read_text() == str(iteration), 'thread write mismatch')
            name.unlink()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(worker, range(8)))
    return '8 threads x100 read/write/unlink cycles passed'

def signals():
    seen = []
    signal.signal(signal.SIGUSR1, lambda *_: seen.append(True))
    signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGUSR1})
    os.kill(os.getpid(), signal.SIGUSR1)
    require(not seen, 'signal mask ignored')
    signal.pthread_sigmask(signal.SIG_UNBLOCK, {signal.SIGUSR1})
    require(seen == [True], 'pending signal not delivered')
    return 'block, pending signal, unblock and handler passed'

def sockets():
    import socket
    path = '/tmp/evaluation.sock'
    with socket.socket(socket.AF_UNIX) as server:
        server.bind(path)
        server.listen(1)
        with socket.socket(socket.AF_UNIX) as client:
            client.connect(path)
            connection, _ = server.accept()
            with connection:
                client.sendall(b'hello')
                require(connection.recv(5) == b'hello', 'socket data mismatch')
    os.unlink(path)
    return 'AF_UNIX bind/connect/accept passed'

def build_derivation():
    paths = dict(line.split('=', 1) for line in pathlib.Path('/guest-paths').read_text().splitlines())
    builder = paths['busybox'] + '/bin/busybox'
    expression = 'derivation { name = "launcher-smoke"; system = "aarch64-linux"; builder = "' + builder + '"; args = [ "sh" "-c" "echo built-through-launcher > $out" ]; }'
    output = run(['nix', 'build', '--offline', '--no-link', '--print-out-paths', '--expr', expression])
    require(pathlib.Path(output).read_text().strip() == 'built-through-launcher', 'derivation output mismatch')
    run(['nix-store', '--realise', output, '--add-root', '/guest/home/smoke-root'])
    roots = run(['nix-store', '--query', '--roots', output])
    require('/guest/home/smoke-root' in roots, 'GC root missing')
    return {'output': output, 'gc_root': '/guest/home/smoke-root'}

def alternate_stack_lifetime():
    return run([sys.executable, '-I', '-c', '''
import ctypes, errno, os
libc = ctypes.CDLL(None, use_errno=True)
class Stack(ctypes.Structure):
    _fields_ = [('sp', ctypes.c_void_p), ('flags', ctypes.c_int), ('size', ctypes.c_size_t)]
libc.mmap.restype = ctypes.c_void_p
libc.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_long]
libc.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
p = libc.mmap(None, 65536, 3, 0x22, -1, 0)
assert p != ctypes.c_void_p(-1).value
assert libc.sigaltstack(ctypes.byref(Stack(p, 0, 65536)), None) == 0
assert libc.munmap(p, 65536) == -1
assert ctypes.get_errno() == errno.EBUSY
assert os.stat('/etc/evaluation-marker').st_size > 0
assert libc.sigaltstack(ctypes.byref(Stack(None, 2, 0)), None) == 0
assert libc.munmap(p, 65536) == 0
print('ALTSTACK_LIFETIME_GUARD_PASS')
'''])


def inherited_syscall_denial():
    return run([sys.executable, '-I', '-c', '''
import ctypes, errno
libc = ctypes.CDLL(None, use_errno=True)
class Filter(ctypes.Structure):
    _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte), ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
class Program(ctypes.Structure):
    _fields_ = [('length', ctypes.c_ushort), ('filters', ctypes.POINTER(Filter))]
filters = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 452), Filter(0x06, 0, 0, 0x30000), Filter(0x06, 0, 0, 0x7fff0000))
assert libc.prctl(38, 1, 0, 0, 0) == 0
assert libc.prctl(22, 2, ctypes.byref(Program(4, filters)), 0, 0) == 0
assert libc.syscall(452, -100, b'/etc/evaluation-marker', 0o600, 0) == -1
assert ctypes.get_errno() == errno.ENOSYS
print('INHERITED_DENIAL_PASS')
'''])


check('alternate-stack-lifetime', alternate_stack_lifetime)
check('inherited-syscall-denial', inherited_syscall_denial)
check('path-mapping', mapping)
check('no-ptrace-seccomp-active', status)
check('hardlink-lifecycle', links)
check('symlink-canonicalization', symlinks)
check('raw-syscall', raw_syscall)
check('process-execution', spawning)
check('threaded-filesystem', threads)
check('signal-mask', signals)
check('unix-socket', sockets)
check('static-go-threads-and-exec', lambda: require(run(['/static-go']) == 'STATIC_GO_PASS', 'static Go probe failed'))
check('nix-load-registration', lambda: run(['nix-store', '--load-db'], input=pathlib.Path('/registration').read_text()))
check('nix-offline-evaluation', lambda: require(run(['nix', '--extra-experimental-features', 'nix-command', 'eval', '--expr', '20 + 22']) == '42', 'incorrect Nix evaluation'))
check('nix-local-build-and-gc-root', build_derivation)
check('nix-store-verification', lambda: run(['nix-store', '--verify', '--check-contents']))
print('EVALUATION_SUMMARY=' + json.dumps({'passed': sum(item['pass'] for item in results), 'total': len(results)}), flush=True)
sys.exit(0 if all(item['pass'] for item in results) else 1)
