#!/usr/bin/env bash
#
# Re-applies the TermuxPP package identity to the termux-tools helper scripts.
#
# APT overwrites $PREFIX/bin/termux-* with the stock versions, which address the stock package
# (com.termux). This script runs as a DPkg::Post-Invoke hook and rewrites exactly the known stock
# strings listed in helper-rewrites.tsv (rendered by the app next to this script) back to this app.
#
# Safety properties:
# - The prefix comes from the app-rendered table and must match this script's own location; the
#   environment is never trusted.
# - Only whitelisted bin/termux-* regular files (no symlinks) that start with a shebang are touched,
#   and only when a rule matches. Anything else is left byte-identical.
# - The rewrite goes to a temporary file in the same directory, keeps the original mode and is moved
#   into place atomically, so an interrupted run never leaves a half-written helper.
# - It never calls apt or dpkg, never uses the network and never fails the caller: problems are
#   reported on stderr and the exit status is always 0.

export LC_ALL=C

RULE_FILES=()
RULE_STOCKS=()
RULE_FORKS=()
PREFIX_REAL=

usage() {
  cat <<EOF
Usage: $(basename "$0") [-n|--dry-run] [-h|--help]

Re-apply the fork identity to the termux-tools helper scripts.
  -n, --dry-run  only report the helpers that would be rewritten
EOF
}

warn() {
  printf 'termuxpp-adapt-helpers: %s\n' "$*" >&2
}

load_table() {
  local table="$1"
  local kind
  local file
  local stock
  local fork
  local prefix=

  while IFS=$'\t' read -r kind file stock fork
  do
    case "${kind}" in
      prefix)
        prefix="${file}"
        ;;
      rule)
        if [[ -z "${file}" || -z "${stock}" || -z "${fork}" ]]
        then
          warn "ignoring incomplete rule"
          continue
        fi
        if [[ "${fork}" =~ @[A-Z_]+@ ]]
        then
          warn "ignoring rule with unresolved placeholder"
          continue
        fi
        RULE_FILES+=("${file}")
        RULE_STOCKS+=("${stock}")
        RULE_FORKS+=("${fork}")
        ;;
    esac
  done < "${table}"

  if [[ "${prefix}" != /* || "${prefix}" == *..* || "${prefix}" == *$'\n'* ]]
  then
    warn "table has no usable prefix"
    return 1
  fi

  PREFIX_REAL="$(readlink -f -- "${prefix}")" || return 1
  [[ -d "${PREFIX_REAL}" ]] || return 1
}

# Print the helpers named by the rules, once each, in table order.
unique_files() {
  local file
  local seen=$'\n'

  for file in "${RULE_FILES[@]}"
  do
    if [[ "${seen}" != *$'\n'"${file}"$'\n'* ]]
    then
      seen+="${file}"$'\n'
      printf '%s\n' "${file}"
    fi
  done
}

# Set REWRITTEN to the helper text with all matching rules applied.
rewrite_text() {
  local file="$1"
  local text="$2"
  local i

  REWRITTEN="${text}"
  for i in "${!RULE_FILES[@]}"
  do
    if [[ "${RULE_FILES[i]}" == "${file}" && "${REWRITTEN}" == *"${RULE_STOCKS[i]}"* ]]
    then
      REWRITTEN="${REWRITTEN//"${RULE_STOCKS[i]}"/"${RULE_FORKS[i]}"}"
    fi
  done
}

adapt_file() {
  local file="$1"
  local dry_run="$2"
  local target="${PREFIX_REAL}/${file}"
  local text
  local size
  local mode
  local tmp

  if [[ ! "${file}" =~ ^bin/termux-[a-z-]+$ ]]
  then
    warn "refusing unexpected helper name: ${file}"
    return 1
  fi

  # Absent helpers (not installed) and symlinks are skipped on purpose.
  if [[ -L "${target}" || ! -f "${target}" ]]
  then
    return 0
  fi

  # A trailing sentinel keeps trailing newlines intact through the command substitution.
  text="$(cat -- "${target}" && printf x)" || return 1
  text="${text%x}"
  size="$(wc -c < "${target}")" || return 1
  size="${size//[[:space:]]/}"

  # A NUL byte would be dropped by bash: such a file is not a shell script, leave it alone.
  if [[ "${#text}" -ne "${size}" || "${text}" != '#!'* ]]
  then
    return 0
  fi

  rewrite_text "${file}" "${text}"
  if [[ "${REWRITTEN}" == "${text}" ]]
  then
    return 0
  fi

  if [[ -n "${dry_run}" ]]
  then
    printf 'would adapt %s\n' "${file}"
    return 0
  fi

  mode="$(stat -c %a -- "${target}")" || return 1
  tmp="$(mktemp "${target}.termuxpp.XXXXXX")" || return 1
  if ! printf '%s' "${REWRITTEN}" > "${tmp}"
  then
    rm -f -- "${tmp}"
    return 1
  fi

  size="$(wc -c < "${tmp}")"
  size="${size//[[:space:]]/}"
  if [[ "${size}" -ne "${#REWRITTEN}" ]] || ! chmod "${mode}" "${tmp}" || ! mv -f -- "${tmp}" "${target}"
  then
    rm -f -- "${tmp}"
    return 1
  fi

  printf 'adapted %s\n' "${file}" >&2
}

main() {
  local dry_run=
  local self
  local dir
  local file
  local failed=

  while [[ -n "${1:-}" ]]
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      -n|--dry-run)
        dry_run=1
        shift
        ;;
      *)
        usage >&2
        return 0
        ;;
    esac
  done

  self="$(readlink -f -- "${BASH_SOURCE[0]}")" || return 0
  dir="${self%/*}"

  if [[ ! -f "${dir}/helper-rewrites.tsv" || -L "${dir}/helper-rewrites.tsv" ]]
  then
    return 0
  fi

  if ! load_table "${dir}/helper-rewrites.tsv"
  then
    warn "not adapting helpers: invalid rewrite table"
    return 0
  fi

  if [[ "${dir}" != "${PREFIX_REAL}/libexec/termuxpp" ]]
  then
    warn "not adapting helpers: script is outside the table's prefix"
    return 0
  fi

  if [[ "${#RULE_FILES[@]}" -eq 0 ]]
  then
    return 0
  fi

  while IFS= read -r file
  do
    if ! adapt_file "${file}" "${dry_run}"
    then
      warn "could not adapt ${file}"
      failed=1
    fi
  done < <(unique_files)

  if [[ -n "${failed}" ]]
  then
    warn "some helpers were left unchanged"
  fi

  return 0
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
  exit 0
fi

# vim: set ft=sh et ts=2 sw=2 :
