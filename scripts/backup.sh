#!/usr/bin/env bash
set -euo pipefail
umask 077

backup_dir=${BACKUP_DIR:-/backups}
[[ "$backup_dir" == /* ]] || { echo 'BACKUP_DIR must be absolute' >&2; exit 1; }
mkdir -p -- "$backup_dir"
backup_dir=$(cd -- "$backup_dir" && pwd -P)
interval=${BACKUP_INTERVAL:-86400}
deadline=${BACKUP_TIMEOUT:-3600}
retention_days=${BACKUP_RETENTION_DAYS:-30}
max_files=${BACKUP_MAX_FILES:-60}
[[ "$interval" =~ ^[1-9][0-9]*$ && "$deadline" =~ ^[1-9][0-9]*$ &&
   "$retention_days" =~ ^[1-9][0-9]*$ && "$max_files" =~ ^[1-9][0-9]*$ ]] || {
  echo 'BACKUP_INTERVAL/TIMEOUT/RETENTION_DAYS/MAX_FILES must be positive integer seconds/counts' >&2
  exit 1
}

atomic_status() {
  local target=$1 value=$2 temporary
  temporary=$(mktemp "$backup_dir/.${target##*/}.XXXXXX")
  printf '%s\n' "$value" >"$temporary"
  mv -f -- "$temporary" "$target"
}

prune_backups() {
  local now=$1 file name count=0
  local -a files=()
  shopt -s nullglob
  for file in "$backup_dir"/hongshu-*.sql.gz; do
    [[ -f "$file" && ! -L "$file" ]] || continue
    files+=("$file")
  done
  # Timestamped lexical order is oldest first. Only our verified naming scope is eligible.
  for ((count=${#files[@]}; count>max_files; count--)); do
    file=${files[${#files[@]}-count]}
    [[ "$(dirname -- "$file")" == "$backup_dir" ]] || return 1
    rm -f -- "$file"
  done
  for file in "${files[@]}"; do
    [[ -f "$file" && ! -L "$file" ]] || continue
    name=${file##*/}
    if [[ "$name" =~ ^hongshu-([0-9]{8}T[0-9]{6}Z)(-.*)?\.sql\.gz$ ]]; then
      local created
      created=$(date -u -d "${BASH_REMATCH[1]}" +%s 2>/dev/null || true)
      if [[ -n "$created" && $((now-created)) -gt $((retention_days*86400)) ]]; then
        rm -f -- "$file"
      fi
    fi
  done
}

run_once() {
  local started stamp partial final temporary
  started=$(date -u +%s)
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  partial=$(mktemp "$backup_dir/hongshu-${stamp}-XXXXXX.sql.gz.partial")
  final=${partial%.partial}
  export MYSQL_HOST=${MYSQL_HOST:-db} MYSQL_PORT=${MYSQL_PORT:-3306}
  export MYSQL_USER=${MYSQL_USER:-hongshu} MYSQL_DATABASE=${MYSQL_DATABASE:-hongshu}
  if timeout --signal=TERM --kill-after=10s "$deadline" bash -c '
    set -euo pipefail
    mysqldump --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" \
      --ssl-mode="${MYSQL_SSL_MODE:-PREFERRED}" \
      --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --hex-blob \
      "$MYSQL_DATABASE" | gzip >"$1"
    gzip -t "$1"
    mv -f -- "$1" "$2"
  ' _ "$partial" "$final"; then
    atomic_status "$backup_dir/last-success" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    rm -f -- "$backup_dir/last-failure"
    prune_backups "$started"
    echo 'consistent database backup completed'
    return 0
  fi
  atomic_status "$backup_dir/last-failure" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo 'database backup failed or timed out; partial file retained for inspection' >&2
  return 1
}

while true; do
  run_once || true
  [[ "${BACKUP_ONCE:-0}" == 1 ]] && break
  sleep "$interval"
done
