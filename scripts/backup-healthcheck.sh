#!/usr/bin/env bash
set -euo pipefail
backup_dir=${BACKUP_DIR:-/backups}
success="$backup_dir/last-success"
[[ -f "$success" && ! -L "$success" ]] || { echo 'no successful backup recorded' >&2; exit 1; }
interval=${BACKUP_INTERVAL:-86400}
deadline=${BACKUP_TIMEOUT:-3600}
grace=${BACKUP_HEALTH_GRACE:-900}
[[ "$interval" =~ ^[1-9][0-9]*$ && "$deadline" =~ ^[1-9][0-9]*$ && "$grace" =~ ^[0-9]+$ ]] || exit 1
modified=$(date -u -r "$success" +%s)
now=$(date -u +%s)
max_age=$((interval+deadline+grace))
((now >= modified && now-modified <= max_age)) || {
  echo "last successful backup is stale (${now-modified}s > ${max_age}s)" >&2
  exit 1
}
echo 'backup freshness healthy'
