#!/usr/bin/env bash
set -euo pipefail
# Exit 0 only when the first stable SemVer is strictly newer than the second.
first=${1:-}
second=${2:-}
pattern='^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
[[ "$first" =~ $pattern && "$second" =~ $pattern ]] || {
  echo 'compare-semver expects two stable SemVer values' >&2
  exit 2
}
[[ "${first#v}" != "${second#v}" ]] || exit 1
newest=$(printf '%s\n' "${first#v}" "${second#v}" | sort -V | tail -n 1)
[[ "$newest" == "${first#v}" ]]
