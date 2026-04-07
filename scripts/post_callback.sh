#!/usr/bin/env bash
set -euo pipefail

callback_url="${1:-}"
build_id="${2:-}"
status="${3:-}"
download_url="${4:-}"
logs="${5:-}"
secret="${6:-}"

if [ -z "$callback_url" ] || [ -z "$build_id" ] || [ -z "$status" ]; then
  echo "Usage: post_callback.sh <callback_url> <build_id> <status> [download_url] [logs] [secret]" >&2
  exit 1
fi

payload_file="$(mktemp)"
response_file="$(mktemp)"

python3 - "$build_id" "$status" "$download_url" "$logs" "$secret" <<'PY' > "$payload_file"
import json
import sys

build_id, status, download_url, logs, secret = sys.argv[1:6]
body = {
    "build_id": build_id,
    "status": status,
}
if download_url:
    body["download_url"] = download_url
if logs:
    body["logs"] = logs
if secret:
    body["secret"] = secret
json.dump(body, sys.stdout)
PY

http_status="$(curl -sS --retry 3 --retry-delay 2 --retry-all-errors --max-time 30 \
  -o "$response_file" \
  -w "%{http_code}" \
  -X POST "$callback_url" \
  -H "Content-Type: application/json" \
  --data-binary @"$payload_file")"

cat "$response_file"
echo

rm -f "$payload_file" "$response_file"

if [ "$http_status" -lt 200 ] || [ "$http_status" -ge 300 ]; then
  echo "Callback request failed with HTTP ${http_status}" >&2
  exit 1
fi