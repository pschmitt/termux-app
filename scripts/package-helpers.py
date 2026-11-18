#!/usr/bin/env python3
"""Package prebuilt TermuxPP helpers for dpkg or the bootstrap builder."""

import argparse
import gzip
import hashlib
import io
from pathlib import Path
import tarfile

PREFIX = "data/data/dev.pschmitt.termux/files/usr"
BOOTSTRAP_PREFIX = "data/data/com.termux/files/usr"


def tarball(files):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, (data, mode, target) in sorted(files.items()):
            info = tarfile.TarInfo(name)
            info.mode = mode
            if target is not None:
                info.type = tarfile.SYMTYPE
                info.linkname = target
            else:
                info.size = len(data)
            archive.addfile(info, None if target is not None else io.BytesIO(data))
    return gzip.compress(stream.getvalue(), mtime=0)


def package(out, name, version, architecture, depends, files, bootstrap):
    prefix = BOOTSTRAP_PREFIX if bootstrap else PREFIX
    # dpkg writes a .dpkg-new sibling before replacing an installed file. On
    # Android this runs as the app UID, so helper payload files must be writable
    # by their owner even when the source binary was packaged read-only.
    payload = {
        prefix + "/" + path: (data, mode | 0o200 if target is None else mode, target)
        for path, (data, mode, target) in files.items()
    }
    control = (f"Package: {name}\nVersion: {version}\nArchitecture: {architecture}\n"
               "Maintainer: TermuxPP contributors\n"
               f"Depends: {depends}\nDescription: Fork-aware TermuxPP {name} helper\n").encode()
    md5 = "".join(hashlib.md5(data).hexdigest() + "  " + path + "\n"
                  for path, (data, _, target) in sorted(payload.items()) if target is None).encode()
    members = [("debian-binary", b"2.0\n"),
               ("control.tar.gz", tarball({"control": (control, 0o644, None),
                                         "md5sums": (md5, 0o644, None)})),
               ("data.tar.gz", tarball(payload))]
    output = bytearray(b"!<arch>\n")
    for filename, data in members:
        output.extend(f"{filename + '/':<16}{0:<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n".encode())
        output.extend(data)
        if len(data) % 2:
            output.extend(b"\n")
    destination = out / f"{name}_{version}_{architecture}.deb"
    destination.write_bytes(output)
    print(f"{destination}: sha256 {hashlib.sha256(output).hexdigest()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--am-source", type=Path, required=True)
    parser.add_argument("--am-apk", type=Path, required=True)
    parser.add_argument("--api-prefix", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--bootstrap", action="store_true",
                        help="use upstream archive paths, rewritten by TermuxInstaller")
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    launcher = (args.am_source / "am-libexec-packaged").read_text()
    launcher = launcher.replace("/data/data/com.termux/files/usr", "/" + PREFIX)
    launcher = launcher.replace("@TERMUX_PREFIX@", "/" + PREFIX).encode()
    package(args.out_dir, "termux-am", "0.8.1+termuxpp1", "all", "dash, coreutils",
            {"bin/am": (launcher, 0o755, None),
             "libexec/termux-am/am.apk": (args.am_apk.read_bytes(), 0o600, None)}, args.bootstrap)
    files = {}
    for directory in ("bin", "libexec", "lib"):
        for path in sorted((args.api_prefix / directory).rglob("*")):
            if path.is_dir() or path.suffix == ".a":
                continue
            relative = path.relative_to(args.api_prefix).as_posix()
            if path.is_symlink():
                files[relative] = (b"", 0o777, str(path.readlink()))
            else:
                files[relative] = (path.read_bytes(), path.stat().st_mode & 0o777, None)
    if "libexec/termux-api-broadcast" not in files or "libexec/termux-callback" not in files:
        parser.error("--api-prefix must contain the staged native API client and callback")
    package(args.out_dir, "termux-api", "0.60.0+termuxpp1", "aarch64",
            "bash, util-linux, termux-am (>= 0.8.1+termuxpp1)", files, args.bootstrap)


if __name__ == "__main__":
    main()
