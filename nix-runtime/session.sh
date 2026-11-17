#!/system/bin/sh

usage() {
  printf 'Usage: %s [--bootstrap] [-- COMMAND ARG...]\n' "$0"
}

main() {
  script=$(/system/bin/readlink -f "$0") || return 1
  directory=${script%/*}
  if [[ ! -r "$directory/session.env" ]]
  then
    printf 'Runtime is not prepared: %s\n' "$directory" >&2
    return 1
  fi
  # shellcheck source=/dev/null
  . "$directory/session.env"
  if [[ $(/system/bin/uname -m) != aarch64 || $(/system/bin/getconf PAGE_SIZE) != 4096 ]]
  then
    printf 'This experimental runtime requires AArch64 with 4096-byte pages.\n' >&2
    return 1
  fi
  bootstrap=
  case ${1:-} in
    -h|--help)
      usage
      return 0
      ;;
    --bootstrap)
      bootstrap=1
      shift
      ;;
  esac
  if [[ ${1:-} == -- ]]
  then
    shift
  fi
  if [[ -n $bootstrap ]]
  then
    command_path="$BOOTSTRAP_NIX/bin:$BOOTSTRAP_BUSYBOX/bin:/bin"
    login_shell=/bin/sh
    certificate="/android$ANDROID_CA"
  else
    command_path="/home/nix/.nix-profile/bin:$SESSION_PATH"
    login_shell=$SESSION_SHELL
    certificate=$SESSION_CERTIFICATE
    if [[ ! -x "$directory/root$login_shell" ]]
    then
      printf 'Session packages are not installed; finish bootstrap first.\n' >&2
      return 1
    fi
  fi
  if [[ $# -gt 0 ]]
  then
    target=/bin/sh
    set -- -c 'exec "$@"' sh "$@"
  elif [[ -n $bootstrap ]]
  then
    target=/bin/sh
    set -- -i
  else
    target=$login_shell
    set -- --noprofile --rcfile /etc/bashrc -i
  fi
  cd "$directory/root/home/nix" || return 1
  exec /system/bin/env -i \
    HOME=/home/nix USER=nix LOGNAME=nix \
    TERM="${TERM:-xterm-256color}" COLORTERM="${COLORTERM:-truecolor}" \
    LANG=C.UTF-8 TMPDIR=/tmp SHELL="$login_shell" \
    PATH="$command_path" TERMINFO="$SESSION_TERMINFO" \
    NIX_SSL_CERT_FILE="$certificate" SSL_CERT_FILE="$certificate" \
    NIX_CONFIG='sandbox = false
build-users-group =
experimental-features = nix-command flakes
substituters = https://cache.nixos.org
require-sigs = true
auto-optimise-store = false' \
    "$directory/launcher" \
    -b "$directory/root/nix:/nix" \
    -b "$directory/root/etc:/etc!" \
    -b "$directory/root/bin:/bin!" \
    -b "$directory/root/home:/home" \
    -b "$directory/root/tmp:/tmp" \
    -b "$directory/root/usr:/usr" \
    -b "$directory/root/dev/shm:/dev/shm" \
    -b "$directory/proc-stat:/proc/stat" \
    -b "$directory/proc-uptime:/proc/uptime" \
    -b /:/android \
    "$target" "$target" "$@"
}

main "$@"

# vim: set ft=sh et ts=2 sw=2 :
