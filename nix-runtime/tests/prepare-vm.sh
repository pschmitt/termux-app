#!/usr/bin/env bash

main() {
  if [[ $# != 3 ]]
  then
    printf 'Usage: %s INPUTS OUTPUT_DIR STATIC_GO\n' "$(basename "$0")" >&2
    return 2
  fi
  local inputs root output fixtures name path
  inputs=$(realpath "$1") || return 1
  output=$(realpath -m "$2") || return 1
  fixtures=$(dirname "$(realpath "${BASH_SOURCE[0]}")") || return 1
  mkdir -p "$output" || return 1
  root=$(mktemp -d "$output/root.XXXXXXXX") || return 1
  mkdir -p "$root"/{nix/store,proc,sys,dev,bin,etc,guest/{nix/store,nix/var/nix,home,etc,tmp,bin,usr,dev-shm}} || return 1
  : > "$output/closures"
  : > "$root/guest-paths"
  for name in busybox bash coreutils python nix
  do
    path=$(readlink -f "$inputs/$name") || return 1
    printf '%s=%q\n' "$name" "$path" >> "$root/guest-paths"
    nix-store -qR "$path" >> "$output/closures" || return 1
  done
  sort -u "$output/closures" -o "$output/closures" || return 1
  while IFS= read -r path
  do
    cp -a "$path" "$root/nix/store/" || return 1
  done < "$output/closures"
  chmod -R u+w "$root/nix" || return 1
  cp -al "$root/nix/store/." "$root/guest/nix/store/" || return 1
  local -a closure
  mapfile -t closure < "$output/closures"
  nix-store --dump-db "${closure[@]}" > "$root/registration" || return 1
  path=$(readlink -f "$inputs/busybox") || return 1
  ln -sf "$path/bin/busybox" "$root/bin/busybox" || return 1
  ln -sf "$path/bin/busybox" "$root/bin/sh" || return 1
  ln -sf "$path/bin/busybox" "$root/guest/bin/sh" || return 1
  cp "$inputs/launcher/bin/launcher" "$root/launcher" || return 1
  cp "$fixtures/guest-init.sh" "$root/init" || return 1
  cp "$fixtures/runtime-tests.py" "$fixtures/drop-uid.py" "$root/" || return 1
  cp "$3" "$root/static-go" || return 1
  printf 'host-view\n' > "$root/etc/evaluation-marker"
  printf 'guest-view\n' > "$root/guest/etc/evaluation-marker"
  printf 'root:x:0:0:root:/root:/bin/sh\ntester:x:10000:10000:tester:/guest/home:/bin/sh\n' > "$root/guest/etc/passwd"
  printf 'root:x:0:\ntester:x:10000:\n' > "$root/guest/etc/group"
  chmod -R a+rX "$root" || return 1
  chmod 755 "$root" "$root/init" "$root/launcher" "$root/static-go" || return 1
  (
    set -o pipefail
    cd "$root" || return 1
    timeout 5m find . -print0 | "$inputs/cpio/bin/cpio" --null -o -H newc | gzip -1 > "$output/initrd.gz"
  ) || return 1
  du -sh "$root" "$output/initrd.gz"
  timeout 180 "$inputs/qemu/bin/qemu-system-aarch64" \
    -machine virt -cpu max -smp 2 -m 2048 -nographic -no-reboot \
    -kernel "$inputs/kernel/Image" -initrd "$output/initrd.gz" \
    -append 'console=ttyAMA0 rdinit=/init panic=-1' > "$output/console.log" 2>&1 || return 1
  "$inputs/python/bin/python3" -I "$fixtures/check-results.py" "$output/console.log"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ft=sh et ts=2 sw=2 :
