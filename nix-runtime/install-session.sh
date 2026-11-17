#!/system/bin/sh

usage() {
  printf 'Usage: %s BOOTSTRAP_APK | --resume\n' "$0"
  printf 'Place launcher, manifest.json, prepare-session.py and session.sh beside this installer.\n'
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
  if [[ $(/system/bin/uname -m) != aarch64 || $(/system/bin/getconf PAGE_SIZE) != 4096 ]]
  then
    printf 'Only AArch64 with 4096-byte pages is qualified for this prototype.\n' >&2
    return 1
  fi
  if [[ $1 != --resume ]]
  then
    "$prefix/bin/python3" -I "$directory/prepare-session.py" \
      "$directory" "$1" "$directory/manifest.json" "$prefix/etc/tls/cert.pem" || return 1
  fi
  if [[ ! -r "$directory/session.env" || ! -r "$directory/packages.txt" ]]
  then
    printf 'No prepared runtime to resume.\n' >&2
    return 1
  fi
  index=0
  while IFS= read -r package
  do
    "$directory/session.sh" --bootstrap -- nix copy --from https://cache.nixos.org "$package" || return 1
    "$directory/session.sh" --bootstrap -- nix-store --add-root \
      "/nix/var/nix/gcroots/termuxpp-session/$index" --realise "$package" || return 1
    index=$((index + 1))
  done < "$directory/packages.txt"
  "$directory/session.sh" -- nix --version || return 1
  "$directory/session.sh" -- python3 -I -c \
    'import os; print(os.confstr("CS_GNU_LIBC_VERSION"))' || return 1
  printf 'ready\n' > "$directory/ready" || return 1
  printf 'Runtime ready: %s/session.sh\n' "$directory"
  printf 'The normal TermuxPP shell and prefix have not been changed.\n'
}

main "$@"

# vim: set ft=sh et ts=2 sw=2 :
