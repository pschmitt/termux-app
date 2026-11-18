#!/system/bin/sh

usage() {
  printf 'Usage: %s BOOTSTRAP_APK | --resume\n' "$0"
  printf 'Place the native nixpp binary, launcher and manifest.json beside this installer.\n'
}

main() {
  if [[ ${1:-} == -h || ${1:-} == --help ]]
  then
    usage
    return 0
  fi
  if [[ $# != 1 ]]
  then
    usage >&2
    return 2
  fi
  script=$(/system/bin/readlink -f "$0") || return 1
  directory=${script%/*}
  prefix=${PREFIX:-/data/data/dev.pschmitt.termux/files/usr}
  if [[ ! -x "$directory/nixpp" ]]
  then
    printf 'The native runtime nixpp binary is required.\n' >&2
    return 1
  fi
  if [[ $1 == --resume ]]
  then
    exec "$directory/nixpp" init --runtime "$directory" --resume
  fi
  exec "$directory/nixpp" init --runtime "$directory" --apk "$1" \
    --manifest "$directory/manifest.json" --certificate "$prefix/etc/tls/cert.pem" \
    --user "${TERMUXPP_NIX_USER:-nix}"
}

main "$@"

# vim: set ft=sh et ts=2 sw=2 :
