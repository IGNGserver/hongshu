#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test ! -e .env || { echo '.env already exists; refusing to replace credentials' >&2; exit 1; }
: "${PUBLIC_URL:?Set PUBLIC_URL to the exact external origin, e.g. https://sms.example.com or http://sms.example.com:18473}"
[[ "$PUBLIC_URL" =~ ^https?://[^/]+$ && "$PUBLIC_URL" != *'?'* && "$PUBLIC_URL" != *'#'* && "$PUBLIC_URL" != *@* ]] || { echo 'PUBLIC_URL must be an HTTP(S) origin without credentials, path, query or fragment' >&2; exit 1; }
if [[ -z "${HONGSHU_PASSWORD+x}" ]]; then
  [[ -r /dev/tty ]] || { echo 'Set HONGSHU_PASSWORD or run this script interactively to enter it' >&2; exit 1; }
  read -r -s -p 'Choose the single-user hub password (weak passwords are allowed): ' HONGSHU_PASSWORD </dev/tty
  printf '\n' >/dev/tty
  read -r -s -p 'Confirm hub password: ' HONGSHU_PASSWORD_CONFIRM </dev/tty
  printf '\n' >/dev/tty
  [[ "$HONGSHU_PASSWORD" == "$HONGSHU_PASSWORD_CONFIRM" ]] || { echo 'Passwords do not match' >&2; exit 1; }
fi
password_bytes=$(LC_ALL=C printf '%s' "$HONGSHU_PASSWORD" | wc -c)
[[ -n "$HONGSHU_PASSWORD" && "$password_bytes" -le 1024 && "$HONGSHU_PASSWORD" != *$'\n'* ]] || { echo 'HONGSHU_PASSWORD must contain 1 to 1024 bytes on one line' >&2; exit 1; }
command -v openssl >/dev/null
docker compose version >/dev/null
umask 077
quote_compose_single() {
  local value=$1
  value=${value//\'/\\\'}
  printf '%s' "$value"
}
{
  printf 'PUBLIC_URL=%s\nPORT=%s\nBIND_ADDRESS=%s\n' "$PUBLIC_URL" "${PORT:-18473}" "${BIND_ADDRESS:-127.0.0.1}"
  printf 'HONGSHU_VERSION=%s\n' "${HONGSHU_VERSION:-latest}"
  printf "HONGSHU_PASSWORD='%s'\n" "$(quote_compose_single "$HONGSHU_PASSWORD")"
  printf 'MYSQL_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  printf 'MYSQL_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 32)"
  printf 'VAPID_SUBJECT=%s\n' "${VAPID_SUBJECT:-mailto:admin@example.invalid}"
} > .env
if [[ -f Dockerfile ]]; then
  docker compose build hub || true
fi
docker compose run --rm --no-deps --entrypoint /vapid hub >> .env
mkdir -p runtime-data/backups
if [[ "$PUBLIC_URL" == http://* ]]; then
  echo 'Configuration generated for plaintext HTTP. Protect .env; expose the port only if you accept the network risk.'
else
  echo 'Configuration generated. Protect .env; run docker compose up -d, then configure your HTTPS reverse proxy.'
fi
