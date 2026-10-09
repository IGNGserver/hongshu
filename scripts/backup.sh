#!/usr/bin/env bash
set -euo pipefail
umask 077
mkdir -p /backups
while true; do
  file="/backups/hongshu-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  if mysqldump --host="${MYSQL_HOST:-db}" --user=hongshu --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --hex-blob hongshu | gzip > "$file.partial"; then
    gzip -t "$file.partial"
    mv "$file.partial" "$file"
    echo 'consistent database backup completed'
  else
    echo 'database backup failed; partial file retained for inspection' >&2
  fi
  sleep "${BACKUP_INTERVAL:-86400}"
done
