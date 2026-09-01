#!/usr/bin/env bash
set -euo pipefail

readonly proxy_config="/etc/beanflow/vault-proxy.hcl"
readonly bootstrap_dir="/run/beanflow-vault-bootstrap"
readonly runtime_dir="/run/beanflow-vault"
readonly role_id_source="$bootstrap_dir/BEANFLOW_VAULT_ROLE_ID"
readonly secret_id_source="$bootstrap_dir/BEANFLOW_VAULT_SECRET_ID"
readonly ca_source="$bootstrap_dir/BEANFLOW_VAULT_CA_PEM"
readonly role_id_file="$runtime_dir/BEANFLOW_VAULT_ROLE_ID"
readonly secret_id_file="$runtime_dir/BEANFLOW_VAULT_SECRET_ID"
readonly ca_file="$runtime_dir/BEANFLOW_VAULT_CA_PEM"

: "${BEANFLOW_VAULT_UPSTREAM_ADDR:?BEANFLOW_VAULT_UPSTREAM_ADDR is required}"
[[ "$BEANFLOW_VAULT_UPSTREAM_ADDR" == https://* ]] || {
  echo "BEANFLOW_VAULT_UPSTREAM_ADDR must use HTTPS" >&2
  exit 1
}

[[ "$(id -u)" == 0 ]] || {
  echo "Entrypoint must start as root before dropping child privileges" >&2
  exit 1
}

for required_file in "$role_id_source" "$secret_id_source" "$ca_source"; do
  [[ -r "$required_file" && -s "$required_file" ]] || {
    echo "Required Vault credential file is missing or empty" >&2
    exit 1
  }
done

chown vault-proxy:vault-proxy "$runtime_dir"
chmod 0700 "$runtime_dir"
install --owner=vault-proxy --group=vault-proxy --mode=0400 "$role_id_source" "$role_id_file"
install --owner=vault-proxy --group=vault-proxy --mode=0400 "$secret_id_source" "$secret_id_file"
install --owner=vault-proxy --group=vault-proxy --mode=0400 "$ca_source" "$ca_file"

proxy_pid=""
app_pid=""

terminate_children() {
  [[ -z "$app_pid" ]] || kill -TERM "$app_pid" 2>/dev/null || true
  [[ -z "$proxy_pid" ]] || kill -TERM "$proxy_pid" 2>/dev/null || true
}

trap terminate_children TERM INT EXIT

VAULT_ADDR="$BEANFLOW_VAULT_UPSTREAM_ADDR" VAULT_CACERT="$ca_file" \
  setpriv --reuid=vault-proxy --regid=vault-proxy --init-groups \
  vault proxy -config="$proxy_config" &
proxy_pid="$!"
unset BEANFLOW_VAULT_UPSTREAM_ADDR

proxy_ready=false
readonly proxy_startup_timeout_seconds=60
readonly proxy_deadline_epoch=$(($(date +%s) + proxy_startup_timeout_seconds))
while (($(date +%s) < proxy_deadline_epoch)); do
  kill -0 "$proxy_pid" 2>/dev/null || {
    wait "$proxy_pid"
    exit "$?"
  }
  remaining_seconds=$((proxy_deadline_epoch - $(date +%s)))
  probe_max_time=2
  ((remaining_seconds < probe_max_time)) && probe_max_time="$remaining_seconds"
  health_status="$(
    curl \
      --connect-timeout 1 \
      --max-time "$probe_max_time" \
      --silent \
      --output /dev/null \
      --write-out '%{http_code}' \
      http://127.0.0.1:8100/v1/sys/health || true
  )"
  case "$health_status" in
    200 | 429 | 472 | 473)
      proxy_ready=true
      break
      ;;
  esac
  (($(date +%s) + 1 < proxy_deadline_epoch)) && sleep 1
done
[[ "$proxy_ready" == true ]] || {
  echo "Vault Proxy did not become ready before the startup deadline" >&2
  exit 1
}

setpriv --reuid=beanflow --regid=beanflow --init-groups java -jar /opt/beanflow/app.jar &
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
