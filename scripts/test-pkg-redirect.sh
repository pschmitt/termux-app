#!/usr/bin/env bash
# Native test for libtermux-pkg-redirect: shebang interpreter rewriting for
# /usr/bin/env and /bin|/usr/bin interpreters, exec/spawn coverage, AF_UNIX paths.
# Needs a Linux host with gcc, unshare (user+mount namespaces) and coreutils.
# Heavy: run on a disposable rworker, not the local workstation.
set -euo pipefail

src=$(cd "$(dirname "$0")/.." && pwd)/app/src/main/cpp/termux-pkg-redirect.c
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

data="$work/data/dev.pschmitt.termux"
prefix="$data/files/usr"
mkdir -p "$prefix/bin" "$work/scripts"

gcc -O2 -Wall -Wextra -shared -fPIC -o "$work/libredir.so" "$src" -ldl
cp "$(command -v env)" "$prefix/bin/env"
ln -s "$(command -v bash)" "$prefix/bin/bash"
printf '#!%s\nprintf "TOOL:%%s\\n" "$*"\n' "$prefix/bin/bash" >"$prefix/bin/mytool"
chmod +x "$prefix/bin/mytool"

cat >"$work/spawn.c" <<'EOC'
#define _GNU_SOURCE
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
extern char **environ;
int main(int argc, char **argv) {
    char *a[] = {argv[2], "p1", "p 2", NULL};
    pid_t pid;
    int st;
    int rc = strcmp(argv[1], "spawn") == 0 ? posix_spawn(&pid, argv[2], NULL, NULL, a, environ)
                                           : posix_spawnp(&pid, argv[2], NULL, NULL, a, environ);
    if (rc) { fprintf(stderr, "spawn failed: %d\n", rc); return 1; }
    waitpid(pid, &st, 0);
    return WIFEXITED(st) ? WEXITSTATUS(st) : 1;
}
EOC
gcc -o "$work/spawn" "$work/spawn.c"

cat >"$work/sock.c" <<'EOC'
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
int main(int argc, char **argv) {
    struct sockaddr_un sa = {.sun_family = AF_UNIX};
    strncpy(sa.sun_path, argv[1], sizeof(sa.sun_path) - 1);
    int s = socket(AF_UNIX, SOCK_STREAM, 0), c = socket(AF_UNIX, SOCK_STREAM, 0);
    if (bind(s, (void *) &sa, sizeof(sa)) || listen(s, 1) || connect(c, (void *) &sa, sizeof(sa))) {
        perror("sock"); return 1;
    }
    puts("SOCK_OK");
    return 0;
}
EOC
gcc -o "$work/sock" "$work/sock.c"

cat >"$work/showpre.c" <<'EOC'
#include <stdio.h>
#include <stdlib.h>
int main(void) {
    const char *v = getenv("LD_PRELOAD");
    printf("PRE=%s\n", v ? v : "(unset)");
    return 0;
}
EOC
gcc -o "$prefix/bin/showpre" "$work/showpre.c"

mkdir -p "$prefix/lib"
cat >"$work/marker.c" <<'EOC'
#include <unistd.h>
__attribute__((constructor)) static void marker(void) { (void) !write(1, "MARKER\n", 7); }
EOC
gcc -shared -fPIC -o "$prefix/lib/libmarker.so" "$work/marker.c"

mk() { printf '%s\n' "$2" "printf 'ARGS:%s|%s|%s\n' \"\$0\" \"\$1\" \"\$2\"" >"$work/scripts/$1"; chmod +x "$work/scripts/$1"; }
mk env-bash '#!/usr/bin/env bash'
mk env-opt '#!/usr/bin/env  -S bash -u   '
mk bin-bash '#!/bin/bash -u'
mk usrbin-bash '#!/usr/bin/bash'
mk missing '#!/usr/bin/env no-such-interpreter-xyz'
printf '#!/usr/bin/env mytool\n' >"$work/scripts/env-path"
chmod +x "$work/scripts/env-path"

inner="$work/inner.sh"
cat >"$inner" <<'EOS'
set -u
fail=0
check() { # name expected actual
    if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}
mount -t tmpfs tmpfs /usr/bin 2>/dev/null || { echo "cannot hide /usr/bin"; exit 2; }
[ -e /usr/bin/env ] && { echo "/usr/bin/env still visible"; exit 2; }
S=$WORK/scripts
export TERMUX_APP__DATA_DIR=$DATA PATH=$PREFIX/bin:$PATH
unset LD_PRELOAD
# Without the module the kernel cannot find /usr/bin/env.
check baseline-fails "fail" "$("$S/env-bash" 2>/dev/null && echo ok || echo fail)"
export LD_PRELOAD=$WORK/libredir.so
B=$PREFIX/bin/bash
check execve-env "ARGS:$S/env-bash|a|b c" "$("$B" -c "\"$S/env-bash\" a 'b c'")"
check execve-env-opt "ARGS:$S/env-opt|a|b c" "$("$B" -c "\"$S/env-opt\" a 'b c'")"
check execve-bin-bash "ARGS:$S/bin-bash|a|b c" "$("$B" -c "\"$S/bin-bash\" a 'b c'")"
check execve-usrbin-bash "ARGS:$S/usrbin-bash|a|b c" "$("$B" -c "\"$S/usrbin-bash\" a 'b c'")"
check env-resolves-path "TOOL:$S/env-path x y" "$("$B" -c "\"$S/env-path\" x y")"
check missing-interp "fail" "$("$B" -c "\"$S/missing\"" >/dev/null 2>&1 && echo ok || echo fail)"
check posix_spawn "ARGS:$S/env-bash|p1|p 2" "$("$WORK/spawn" spawn "$S/env-bash")"
check posix_spawnp "TOOL:p1 p 2" "$("$WORK/spawn" spawnp mytool)"
# System paths that exist are not shadowed: /bin/sh is present on the host.
printf '#!/bin/sh\necho SYSSH\n' >"$S/sys-sh"; chmod +x "$S/sys-sh"
check existing-interp-untouched "SYSSH" "$("$B" -c "\"$S/sys-sh\"")"
# Old stock-prefix interpreters still rewrite.
mkdir -p "$WORK/stock"
printf '#!/data/data/com.termux/files/usr/bin/bash\necho STOCK\n' >"$S/stock"; chmod +x "$S/stock"
check stock-prefix "STOCK" "$("$B" -c "\"$S/stock\"")"
# LD_PRELOAD normalization: the linker reads it before redirection, so stock-prefix
# entries must be rewritten in the environment of the new image.
SP=$PREFIX/bin/showpre
STOCK_LIB=/data/data/com.termux/files/usr/lib
cat >"$S/launcher" <<EOL
#!$B
LD_PRELOAD="\$LD_PRELOAD:$STOCK_LIB/libmarker.so" exec "$STOCK_LIB/../bin/showpre" "\$@"
EOL
chmod +x "$S/launcher"
check preload-launcher-loads-lib "$(printf 'MARKER\nPRE=%s:%s/files/usr/lib/libmarker.so' "$WORK/libredir.so" "$DATA")" \
    "$("$B" -c "\"$S/launcher\"" 2>/dev/null)"
MIXED="$WORK/libredir.so:$STOCK_LIB/libx.so /other/keep.so /data/data/com.termux2/x.so:$STOCK_LIB/liby.so"
MIXED_EXPECT="$WORK/libredir.so:$DATA/files/usr/lib/libx.so /other/keep.so /data/data/com.termux2/x.so:$DATA/files/usr/lib/liby.so"
check preload-preserves-others "PRE=$MIXED_EXPECT" "$(LD_PRELOAD=$MIXED "$B" -c "exec $SP" 2>/dev/null)"
check preload-posix_spawn "PRE=$MIXED_EXPECT" "$(LD_PRELOAD=$MIXED "$WORK/spawn" spawn "$SP" 2>/dev/null)"
check preload-posix_spawnp "PRE=$MIXED_EXPECT" "$(LD_PRELOAD=$MIXED "$WORK/spawn" spawnp showpre 2>/dev/null)"
check preload-user-prefix "PRE=$WORK/libredir.so:$DATA/files/usr/lib/libz.so" \
    "$(LD_PRELOAD=$WORK/libredir.so:/data/user/0/com.termux/files/usr/lib/libz.so "$B" -c "exec $SP" 2>/dev/null)"
check preload-fork-only-untouched "PRE=$WORK/libredir.so" "$("$B" -c "exec $SP")"
# Stock Termux: no fork data dir (or the stock one) means no rewriting at all.
check stock-unset-noop "PRE=$MIXED" \
    "$(unset TERMUX_APP__DATA_DIR; LD_PRELOAD=$MIXED "$B" -c "exec $SP" 2>/dev/null)"
check stock-dir-noop "PRE=$MIXED" \
    "$(TERMUX_APP__DATA_DIR=/data/data/com.termux LD_PRELOAD=$MIXED "$B" -c "exec $SP" 2>/dev/null)"
# AF_UNIX path rewriting (stock com.termux path lands in the fork data dir).
mkdir -p "$DATA/files/home"
check af-unix "SOCK_OK" "$("$WORK/sock" /data/data/com.termux/files/home/test.sock)"
[ -S "$DATA/files/home/test.sock" ] && echo "PASS af-unix-target" || { echo "FAIL af-unix-target"; fail=1; }
exit $fail
EOS

export WORK="$work" DATA="$data" PREFIX="$prefix"
exec unshare -rm bash "$inner"
