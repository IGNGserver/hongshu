#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test ! -e .env || { echo '.env already exists; refusing to replace credentials' >&2; exit 1; }
: "${PUBLIC_URL:?Run PUBLIC_URL=https://your-domain.example bash scripts/init-deployment.sh}"
[[ "$PUBLIC_URL" =~ ^https://[^/]+$ ]] || { echo 'PUBLIC_URL must be a HTTPS origin without trailing slash' >&2; exit 1; }
command -v openssl >/dev/null
docker compose version >/dev/null
umask 077
{
  printf 'PUBLIC_URL=%s\nPORT=8080\n' "$PUBLIC_URL"
  printf 'MYSQL_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  printf 'MYSQL_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  printf 'BOOTSTRAP_SECRET=%s\n' "$(openssl rand -hex 32)"
  printf 'VAPID_SUBJECT=%s\n' "${VAPID_SUBJECT:-mailto:admin@example.invalid}"
} > .env
docker compose build hub
docker compose run --rm --no-deps --entrypoint /vapid hub >> .env
mkdir -p runtime-data/backups
echo 'Configuration generated. Protect .env; run docker compose up -d, then configure your HTTPS reverse proxy.'
