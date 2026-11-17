import json
import pathlib
import sys

EXPECTED = {
    'path-mapping', 'no-ptrace-seccomp-active', 'hardlink-lifecycle',
    'symlink-canonicalization', 'raw-syscall', 'process-execution',
    'threaded-filesystem', 'signal-mask', 'unix-socket',
    'static-go-threads-and-exec', 'nix-load-registration',
    'nix-offline-evaluation', 'nix-local-build-and-gc-root',
    'nix-store-verification',
}


def check(text):
    lines = text.splitlines()
    results = [json.loads(line) for line in lines if line.startswith('{"test":')]
    names = [result['test'] for result in results]
    if len(names) != len(EXPECTED) or set(names) != EXPECTED:
        raise ValueError('Missing or duplicated guest test results')
    failed = [result for result in results if result['pass'] is not True]
    if failed:
        raise ValueError(json.dumps(failed))
    for marker in ['EVAL_DIRECT_GO_EXIT=0', 'EVAL_UNPRIVILEGED_UID=10000',
                   'EVAL_GUEST_EXIT=0', 'EVAL_GUEST_END']:
        if marker not in lines:
            raise ValueError('Missing completion/control marker: ' + marker)
    return results


if __name__ == '__main__':
    check(pathlib.Path(sys.argv[1]).read_text())
    print('PASS: all 14 guest checks and direct Go control')
