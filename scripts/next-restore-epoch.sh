#!/usr/bin/env bash
set -euo pipefail
# Pure helper: no database writes. Caller must record the live epoch BEFORE restore.
[[ $# == 2 && $1 =~ ^[0-9]{1,16}$ && $2 =~ ^[0-9]{1,16}$ ]] || { echo 'usage: next-restore-epoch.sh PRE_RESTORE_EPOCH RESTORED_EPOCH' >&2; exit 1; }
previous=$((10#$1))
restored=$((10#$2))
highest=$previous
if (( restored > highest )); then highest=$restored; fi
(( highest < 9007199254740991 )) || { echo 'epoch exceeds exact JSON integer range; restore requires protocol migration' >&2; exit 1; }
printf '%s\n' "$((highest + 1))"
