#!/bin/sh
# shellcheck disable=SC2154
/bin/busybox mount -t proc proc /proc
/bin/busybox mount -t sysfs sysfs /sys
/bin/busybox mount -t devtmpfs devtmpfs /dev
/bin/busybox mkdir -p /dev/pts
/bin/busybox mount -t devpts devpts /dev/pts
/bin/busybox ln -sf /dev/pts/ptmx /dev/ptmx
/bin/busybox chmod 666 /dev/pts/ptmx
/bin/busybox ln -sf /bin/busybox /bin/cat
# shellcheck disable=SC1091
. /guest-paths
# shellcheck disable=SC2154
PATH="$coreutils/bin:/bin" EVAL_MARKER=host-view "$python/bin/python3" -I /drop-uid.py /static-go
printf 'EVAL_DIRECT_GO_EXIT=%s\n' "$?"
printf 'EVAL_GUEST_BEGIN\n'
printf 'kernel_pagesize='
/bin/busybox getconf PAGE_SIZE 2>/dev/null || printf 'see launcher auxv check\n'
/bin/busybox chown -R 10000:10000 /guest
/bin/busybox env -i \
  HOME=/guest/home \
  PATH="$nix/bin:$python/bin:$coreutils/bin:$bash/bin:/bin" \
  PYTHONDONTWRITEBYTECODE=1 \
  NIX_CONFIG="sandbox = false
build-users-group =
experimental-features = nix-command flakes
substituters =" \
  "$python/bin/python3" -I /drop-uid.py /launcher \
  -b /guest/nix:/nix \
  -b /guest/etc:/etc! \
  -b /guest/bin:/bin! \
  -b /guest/tmp:/tmp \
  -b /guest/usr:/usr \
  -b /guest/dev-shm:/dev/shm \
  -b /:/android \
  "$python/bin/python3" python3 -I /runtime-tests.py
result=$?
printf 'EVAL_GUEST_EXIT=%s\n' "$result"
printf 'EVAL_GUEST_END\n'
/bin/busybox poweroff -f

# vim: set ft=sh et ts=2 sw=2 :
