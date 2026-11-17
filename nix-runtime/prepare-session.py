#!/usr/bin/env python3
"""Prepare a new isolated runtime from a verified bootstrap and pinned manifest."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import zipfile

BOOTSTRAP_SHA256 = 'f74f0de161fb51ccc9a6ffd1a839150c1c88fd88f1b7176e320ec564fc696ea2'


def extract(apk, root):
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != BOOTSTRAP_SHA256:
        raise ValueError('bootstrap APK checksum mismatch')
    root.mkdir(mode=0o700)
    archive = root.parent / 'bootstrap.zip'
    with zipfile.ZipFile(apk) as package:
        with package.open('assets/bootstrap-aarch64.zip') as src, archive.open('xb') as out:
            shutil.copyfileobj(src, out)

    def safe(name):
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or not path.parts:
            raise ValueError('unsafe archive path')
        return root.joinpath(*path.parts)

    with zipfile.ZipFile(archive) as package:
        if sum(member.file_size for member in package.infolist()) > 512 * 1024 * 1024:
            raise ValueError('bootstrap too large')
        for member in package.infolist():
            output = safe(member.filename)
            if (member.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('unexpected ZIP symlink')
            if member.is_dir():
                output.mkdir(parents=True, exist_ok=True, mode=0o700)
            else:
                output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with package.open(member) as src, output.open('xb') as out:
                    shutil.copyfileobj(src, out)
    for name in (root / 'EXECUTABLES.txt').read_text().splitlines():
        safe(name).chmod(0o700)
    for line in (root / 'SYMLINKS.txt').read_text().splitlines():
        target, name = line.split('←', 1)
        output = safe(name)
        if any(parent.is_symlink() for parent in output.parents if parent != root.parent):
            raise ValueError('symlink parent')
        output.symlink_to(target)
    archive.unlink()


def prepare(directory, apk, manifest, certificate, username='nix'):
    if not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', username):
        raise ValueError('invalid guest username')
    home = f'/home/{username}'
    config = json.loads(manifest.read_text())
    if config.get('schema') != 1 or config.get('system') != 'aarch64-linux' or config.get('pageSize') != 4096:
        raise ValueError('unsupported session manifest')
    store_path = re.compile(r'/nix/store/[a-z0-9]{32}-[^\s:]+')
    for value in config['packages'] + [config[key] for key in ('shell', 'nix', 'python', 'zsh', 'certificate', 'terminfo')]:
        if not isinstance(value, str) or not store_path.fullmatch(value) or '..' in PurePosixPath(value).parts:
            raise ValueError('invalid pinned store path')
    if config['path'] != ':'.join(path + '/bin' for path in config['packages']):
        raise ValueError('unexpected session PATH')
    root = directory / 'root'
    extract(apk, root)
    store = root / 'nix/store'
    busybox, = sorted(store.glob('*-busybox-static-*/'))
    nix, = sorted(store.glob('*-nix-static-*/'))
    virtual = lambda path: '/' + str(path.relative_to(root))
    for name in (home.lstrip('/'), 'tmp', 'dev/shm', 'usr/bin', 'etc'):
        (root / name).mkdir(parents=True, exist_ok=True, mode=0o700)
    for name, target in [('bin/sh', virtual(busybox) + '/bin/sh'), ('usr/bin/env', virtual(busybox) + '/bin/env')]:
        destination = root / name
        if not destination.exists() and not destination.is_symlink():
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.symlink_to(target)
    files = {
        'etc/passwd': f'{username}:x:{os.getuid()}:{os.getgid()}:Nix user:{home}:{config["shell"]}\n',
        'etc/group': f'{username}:x:{os.getgid()}:\n',
        'etc/nsswitch.conf': 'passwd: files\ngroup: files\nhosts: files dns\n',
        'etc/resolv.conf': 'nameserver 1.1.1.1\nnameserver 9.9.9.9\n',
        'etc/hosts': '127.0.0.1 localhost\n::1 localhost\n',
        'etc/bashrc': "export PATH=\"$HOME/.nix-profile/bin:$PATH\"\nPS1='(nix) \\u:\\w\\$ '\ncd \"$HOME\"\n",
    }
    for name, content in files.items():
        output = root / name
        if output.is_symlink():
            output.unlink()
        output.write_text(content)
    (directory / 'proc-stat').write_text('cpu  100 0 100 1000 0 0 0 0 0 0\nbtime 1700000000\n')
    (directory / 'proc-uptime').write_text('1000.00 1000.00\n')
    roots = root / 'nix/var/nix/gcroots/termuxpp-session'
    roots.mkdir(parents=True, exist_ok=True)
    for index, package in enumerate(config['packages']):
        (roots / str(index)).symlink_to(package)
    values = {
        'SESSION_USER': username,
        'SESSION_HOME': home,
        'BOOTSTRAP_NIX': virtual(nix),
        'BOOTSTRAP_BUSYBOX': virtual(busybox),
        'ANDROID_CA': str(certificate.resolve(strict=True)),
        'SESSION_PATH': config['path'],
        'SESSION_SHELL': config['shell'],
        'SESSION_CERTIFICATE': config['certificate'],
        'SESSION_TERMINFO': config['terminfo'],
    }
    (directory / 'session.env').write_text(''.join(f'{key}={shlex.quote(value)}\n' for key, value in values.items()))
    (directory / 'packages.txt').write_text('\n'.join(config['packages']) + '\n')
    shutil.copyfile(manifest, directory / 'session.json')
    print('Prepared isolated runtime; package import is still required.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('apk', type=Path)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('certificate', type=Path)
    parser.add_argument('--user', default='nix', help='guest username (default: nix)')
    args = parser.parse_args()
    os.umask(0o077)
    prepare(args.directory, args.apk, args.manifest, args.certificate, args.user)


if __name__ == '__main__':
    main()
