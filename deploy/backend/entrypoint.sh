#!/usr/bin/env bash
set -euo pipefail

readonly proxy_config="/etc/beanflow/vault-proxy.hcl"
readonly role_id_file="/run/secrets/BEANFLOW_VAULT_ROLE_ID"
readonly secret_id_file="/run/secrets/BEANFLOW_VAULT_SECRET_ID"
readonly ca_file="/run/secrets/BEANFLOW_VAULT_CA_PEM"

: "${VAULT_ADDR:?VAULT_ADDR is required}"
[[ "$VAULT_ADDR" == https://* ]] || {
  echo "VAULT_ADDR must use HTTPS" >&2
  exit 1
}

for required_file in "$role_id_file" "$secret_id_file" "$ca_file"; do
  [[ -r "$required_file" && -s "$required_file" ]] || {
    echo "Required Vault credential file is missing or empty" >&2
    exit 1
  }
done

export VAULT_CACERT="$ca_file"

proxy_pid=""
app_pid=""

terminate_children() {
  [[ -z "$app_pid" ]] || kill -TERM "$app_pid" 2>/dev/null || true
  [[ -z "$proxy_pid" ]] || kill -TERM "$proxy_pid" 2>/dev/null || true
}

trap terminate_children TERM INT EXIT

vault proxy -config="$proxy_config" &
proxy_pid="$!"

proxy_ready=false
for _ in $(seq 1 60); do
  kill -0 "$proxy_pid" 2>/dev/null || {
    wait "$proxy_pid"
    exit "$?"
  }
  health_status="$(curl --silent --output /dev/null --write-out '%{http_code}' http://127.0.0.1:8100/v1/sys/health || true)"
  case "$health_status" in
    200 | 429 | 472 | 473)
      proxy_ready=true
      break
      ;;
  esac
  sleep 1
done
[[ "$proxy_ready" == true ]] || {
  echo "Vault Proxy did not become ready before the startup deadline" >&2
  exit 1
}

java -jar /opt/beanflow/app.jar &
app_pid="$!"

set +e
wait -n "$proxy_pid" "$app_pid"
status="$?"
set -e

terminate_children
wait "$proxy_pid" 2>/dev/null || true
wait "$app_pid" 2>/dev/null || true
trap - TERM INT EXIT
exit "$status"
