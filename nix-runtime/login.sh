#!/system/bin/sh

script=$(/system/bin/readlink -f "$0") || exit 1
directory=${script%/*}
if [[ $# -eq 0 ]]
then
  set -- -l
fi
# HOME is assigned by session.sh inside the guest, not by the native shell.
# shellcheck disable=SC2016
exec "$directory/session.sh" -- /bin/sh -c '
  export SHELL="$HOME/.nix-profile/bin/zsh"
  export POWERLEVEL9K_DISABLE_CONFIGURATION_WIZARD=true
  exec "$SHELL" "$@"
' sh "$@"

# vim: set ft=sh et ts=2 sw=2 :
