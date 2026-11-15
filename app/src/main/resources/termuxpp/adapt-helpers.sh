#!/usr/bin/env bash
#
# Re-applies the TermuxPP identity to the files of the termux-tools package.
#
# APT overwrites $PREFIX/bin/termux-*, $PREFIX/bin/login and friends with the stock versions, which
# address the stock package (com.termux): they carry stock shebangs and paths, and lack the native
# pkg-redirect preload of the fork. This script runs as a DPkg::Post-Invoke hook and re-applies the
# rows of helper-rewrites.tsv (rendered by the app next to this script), the same table the app applies
# while it extracts the bootstrap. Row kinds are documented in that table.
#
# Safety properties:
# - The prefix comes from the app-rendered table and must match this script's own location; the
#   environment is never trusted.
# - Only regular files (no symlinks) that are plain text are touched: the files named by the table
#   rows (helpers and login must start with a shebang) and, for the identity rows, the text files that
#   dpkg lists for termux-tools. Anything else is left byte-identical.
# - The rewrite goes to a temporary file in the same directory, keeps the original mode and is moved
#   into place atomically, so an interrupted run never leaves a half-written file.
# - It never calls apt or dpkg and never uses the network.
# - It does not hide problems: an unusable table, a file it cannot write, or a result that still lacks
#   the required text (for example because an upstream update changed the shape of a script) is
#   reported as an ERROR on stderr and gives exit status 1, which makes apt report an error.

export LC_ALL=C

ADAPTED_FILE_RE='^(bin/termux-[a-z-]+|bin/login|etc/termux-login\.sh|etc/motd)$'
MAX_LIST_FILE_SIZE=1048576

R_KIND=()
R_FILE=()
R_A=()
R_B=()
IDENTITY_STOCKS=()
PREFIX_RAW=
PREFIX_REAL=
FAILED=

usage() {
  cat <<EOF
Usage: $(basename "$0") [-n|--dry-run] [-h|--help]

Re-apply the fork identity to the termux-tools files.
  -n, --dry-run  only report the files that would be rewritten
EOF
}

warn() {
  printf 'termuxpp-adapt-helpers: %s\n' "$*" >&2
}

err() {
  printf 'termuxpp-adapt-helpers: ERROR: %s\n' "$*" >&2
  FAILED=1
}

# A row is usable when it is complete and has no unresolved placeholder. Rows that replace text must
# also change something and must not match their own result again (idempotence).
usable() {
  local stock="$1"
  local fork="$2"
  local replaces="$3"

  [[ -n "${stock}" && -n "${fork}" ]] || return 1
  [[ ! "${stock}" =~ @[A-Z_]+@ && ! "${fork}" =~ @[A-Z_]+@ ]] || return 1
  if [[ -n "${replaces}" ]]
  then
    [[ "${stock}" != "${fork}" && "${fork}" != *"${stock}"* ]] || return 1
  fi
}

add_row() {
  R_KIND+=("$1")
  R_FILE+=("$2")
  R_A+=("$3")
  R_B+=("$4")
}

load_table() {
  local table="$1"
  local kind
  local f1
  local f2
  local f3

  while IFS=$'\t' read -r kind f1 f2 f3 || [[ -n "${kind}" ]]
  do
    case "${kind}" in
      prefix)
        PREFIX_RAW="${f1}"
        ;;
      identity)
        if usable "${f1}" "${f2}" 1 && [[ -z "${f3}" ]]
        then
          add_row identity "" "${f1}" "${f2}"
          IDENTITY_STOCKS+=("${f1}")
        else
          warn "ignoring unusable identity row"
        fi
        ;;
      rule|append|require|forbid)
        if [[ ! "${f1}" =~ ${ADAPTED_FILE_RE} ]]
        then
          err "refusing unexpected helper name: ${f1}"
          continue
        fi
        if [[ "${f3}" == *$'\t'* ]]
        then
          warn "ignoring malformed ${kind} row for ${f1}"
          continue
        fi
        case "${kind}" in
          rule)
            if usable "${f2}" "${f3}" 1
            then
              # \n is a newline in both texts, so that a rule can address (or remove) whole lines.
              add_row rule "${f1}" "${f2//\\n/$'\n'}" "${f3//\\n/$'\n'}"
            else
              warn "ignoring unusable rule for ${f1}"
            fi
            ;;
          append)
            if usable "${f2}" "${f3}" ""
            then
              add_row append "${f1}" "${f2}" "${f3//\\n/$'\n'}"
            else
              warn "ignoring unusable append for ${f1}"
            fi
            ;;
          *)
            # f3 is the guard, it only resolves for the fork identity.
            if usable "${f2}" "${f3}" ""
            then
              add_row "${kind}" "${f1}" "${f2}" ""
            else
              warn "ignoring unusable ${kind} row for ${f1}"
            fi
            ;;
        esac
        ;;
    esac
  done < "${table}"

  if [[ "${PREFIX_RAW}" != /* || "${PREFIX_RAW}" == *..* || "${PREFIX_RAW}" == *$'\n'* ]]
  then
    err "table has no usable prefix"
    return 1
  fi

  PREFIX_REAL="$(readlink -f -- "${PREFIX_RAW}")" || return 1
  [[ -d "${PREFIX_REAL}" ]] || return 1
}

# Print the files the rows name, once each, in table order.
named_files() {
  local i
  local seen=$'\n'

  for i in "${!R_KIND[@]}"
  do
    [[ -n "${R_FILE[i]}" ]] || continue
    if [[ "${seen}" != *$'\n'"${R_FILE[i]}"$'\n'* ]]
    then
      seen+="${R_FILE[i]}"$'\n'
      printf '%s\n' "${R_FILE[i]}"
    fi
  done
}

# Print the files of termux-tools, relative to the prefix, as listed by dpkg. The list records the
# paths of the fork (dpkg runs under the pkg-redirect preload), but stock paths are accepted too.
tools_files() {
  local list="${PREFIX_REAL}/var/lib/dpkg/info/termux-tools.list"
  local entry
  local rel
  local stock
  local stocks=("${PREFIX_RAW}" "${PREFIX_REAL}")

  [[ -f "${list}" && ! -L "${list}" ]] || return 0

  for stock in "${IDENTITY_STOCKS[@]}"
  do
    stocks+=("${stock}/files/usr")
  done

  while IFS= read -r entry
  do
    rel=
    for stock in "${stocks[@]}"
    do
      if [[ "${entry}" == "${stock}/"* ]]
      then
        rel="${entry#"${stock}/"}"
        break
      fi
    done
    if [[ "${rel}" =~ ^[A-Za-z0-9._+/-]+$ && "${rel}" != *..* && "${rel}" != */ ]]
    then
      printf '%s\n' "${rel}"
    fi
  done < "${list}"
}

has_verification() {
  local file="$1"
  local i

  for i in "${!R_KIND[@]}"
  do
    if [[ "${R_FILE[i]}" == "${file}" && ( "${R_KIND[i]}" == require || "${R_KIND[i]}" == forbid ) ]]
    then
      return 0
    fi
  done
  return 1
}

# True when the file contains no NUL byte (bash would silently drop it from a string).
is_text() {
  local target="$1"
  local without_nul
  local total

  without_nul="$(tr -d '\000' < "${target}" | wc -c)" || return 1
  total="$(wc -c < "${target}")" || return 1
  [[ "${without_nul//[[:space:]]/}" == "${total//[[:space:]]/}" ]]
}

# Set REWRITTEN to the text with all matching rows applied: identity, then rules, then appends.
rewrite_text() {
  local file="$1"
  local text="$2"
  local i

  REWRITTEN="${text}"
  for i in "${!R_KIND[@]}"
  do
    if [[ "${R_KIND[i]}" == identity && "${REWRITTEN}" == *"${R_A[i]}"* ]]
    then
      REWRITTEN="${REWRITTEN//"${R_A[i]}"/"${R_B[i]}"}"
    fi
  done
  for i in "${!R_KIND[@]}"
  do
    if [[ "${R_KIND[i]}" == rule && "${R_FILE[i]}" == "${file}" && "${REWRITTEN}" == *"${R_A[i]}"* ]]
    then
      REWRITTEN="${REWRITTEN//"${R_A[i]}"/"${R_B[i]}"}"
    fi
  done
  for i in "${!R_KIND[@]}"
  do
    if [[ "${R_KIND[i]}" == append && "${R_FILE[i]}" == "${file}" && "${REWRITTEN}" != *"${R_A[i]}"* ]]
    then
      REWRITTEN+="${R_B[i]}"
    fi
  done
}

# Report every require or forbid row that the text violates. Returns 1 when there is one.
verify_text() {
  local file="$1"
  local text="$2"
  local i
  local bad=0

  for i in "${!R_KIND[@]}"
  do
    [[ "${R_FILE[i]}" == "${file}" ]] || continue
    if [[ "${R_KIND[i]}" == require && "${text}" != *"${R_A[i]}"* ]]
    then
      err "${file}: missing expected text: ${R_A[i]}"
      bad=1
    elif [[ "${R_KIND[i]}" == forbid && "${text}" == *"${R_A[i]}"* ]]
    then
      err "${file}: still contains stock text: ${R_A[i]}"
      bad=1
    fi
  done
  return "${bad}"
}

adapt_file() {
  local file="$1"
  local dry_run="$2"
  local target="${PREFIX_REAL}/${file}"
  local text
  local size
  local mode
  local expected
  local tmp

  # Absent files (package part not installed), directories and symlinks are skipped on purpose.
  if [[ -L "${target}" || ! -f "${target}" ]]
  then
    return 0
  fi

  size="$(wc -c < "${target}")" || return 1
  size="${size//[[:space:]]/}"

  if ! is_text "${target}"
  then
    if has_verification "${file}"
    then
      err "${file}: not a text file, cannot verify the fork identity"
      return 1
    fi
    return 0
  fi
  if [[ "${size}" -gt "${MAX_LIST_FILE_SIZE}" ]]
  then
    return 0
  fi

  # A trailing sentinel keeps trailing newlines intact through the command substitution.
  text="$(cat -- "${target}" && printf x)" || return 1
  text="${text%x}"

  # Scripts under bin/ must be scripts; anything else is not ours to rewrite, only to verify.
  if [[ "${file}" == bin/* && "${text}" != '#!'* ]]
  then
    verify_text "${file}" "${text}"
    return
  fi

  rewrite_text "${file}" "${text}"

  if [[ "${REWRITTEN}" != "${text}" ]]
  then
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

    # Compare byte counts: ${#REWRITTEN} counts characters on some platforms (Android's bash is UTF-8
    # aware even with LC_ALL=C), so it is not a byte length for text with multibyte characters.
    size="$(wc -c < "${tmp}")"
    size="${size//[[:space:]]/}"
    expected="$(printf '%s' "${REWRITTEN}" | wc -c)"
    expected="${expected//[[:space:]]/}"
    if [[ "${size}" != "${expected}" ]] || ! chmod "${mode}" "${tmp}" || ! mv -f -- "${tmp}" "${target}"
    then
      rm -f -- "${tmp}"
      return 1
    fi

    printf 'adapted %s\n' "${file}" >&2
  fi

  verify_text "${file}" "${REWRITTEN}"
}

main() {
  local dry_run=
  local self
  local dir
  local file
  local seen=$'\n'
  local files=()

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
        return 2
        ;;
    esac
  done

  self="$(readlink -f -- "${BASH_SOURCE[0]}")" || return 1
  dir="${self%/*}"

  if [[ ! -f "${dir}/helper-rewrites.tsv" || -L "${dir}/helper-rewrites.tsv" ]]
  then
    err "missing rewrite table ${dir}/helper-rewrites.tsv"
    return 1
  fi

  if ! load_table "${dir}/helper-rewrites.tsv"
  then
    err "not adapting termux-tools: invalid rewrite table"
    return 1
  fi

  if [[ "${dir}" != "${PREFIX_REAL}/libexec/termuxpp" ]]
  then
    err "not adapting termux-tools: script is outside the table's prefix"
    return 1
  fi

  if [[ "${#R_KIND[@]}" -eq 0 ]]
  then
    [[ -z "${FAILED}" ]]
    return
  fi

  while IFS= read -r file
  do
    files+=("${file}")
    seen+="${file}"$'\n'
  done < <(named_files)

  if [[ "${#IDENTITY_STOCKS[@]}" -gt 0 ]]
  then
    while IFS= read -r file
    do
      if [[ "${seen}" != *$'\n'"${file}"$'\n'* ]]
      then
        files+=("${file}")
        seen+="${file}"$'\n'
      fi
    done < <(tools_files)
  fi

  for file in "${files[@]}"
  do
    if ! adapt_file "${file}" "${dry_run}"
    then
      FAILED=1
      warn "could not adapt ${file}"
    fi
  done

  if [[ -n "${FAILED}" ]]
  then
    warn "ERROR: the fork identity of termux-tools is incomplete, fix the reported files or reinstall the app"
    return 1
  fi
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
  exit $?
fi

# vim: set ft=sh et ts=2 sw=2 :
