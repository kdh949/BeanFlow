#!/usr/bin/env bash
set -euo pipefail

readonly proxy_config="/etc/beanflow/vault-proxy.hcl"
readonly bootstrap_dir="/run/beanflow-vault-bootstrap"
readonly runtime_dir="/run/beanflow-vault"
readonly app_secret_source_dir="/run/secrets"
readonly app_secret_runtime_dir="/run/beanflow-secrets"
readonly app_secret_names=(
  BEANFLOW_DB_PASSWORD
  BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL
  BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL
  BEANFLOW_AISTOR_ACCESS_KEY
  BEANFLOW_AISTOR_SECRET_KEY
  TOSS_CLIENT_KEY
  TOSS_SECRET_KEY
)
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

# Use the same safe path contract as the JVM's Vault properties before building probe URLs.
: "${BEANFLOW_VAULT_TRANSIT_MOUNT:?BEANFLOW_VAULT_TRANSIT_MOUNT is required}"
: "${BEANFLOW_VAULT_PERSONAL_DATA_ENCRYPTION_KEY:?BEANFLOW_VAULT_PERSONAL_DATA_ENCRYPTION_KEY is required}"
: "${BEANFLOW_VAULT_PERSONAL_DATA_BLIND_INDEX_KEY:?BEANFLOW_VAULT_PERSONAL_DATA_BLIND_INDEX_KEY is required}"
[[ ${#BEANFLOW_VAULT_TRANSIT_MOUNT} -le 128 &&
  "$BEANFLOW_VAULT_TRANSIT_MOUNT" =~ ^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$ &&
  "$BEANFLOW_VAULT_PERSONAL_DATA_ENCRYPTION_KEY" =~ ^[A-Za-z0-9_-]{1,128}$ &&
  "$BEANFLOW_VAULT_PERSONAL_DATA_BLIND_INDEX_KEY" =~ ^[A-Za-z0-9_-]{1,128}$ ]] || {
  echo "Vault Transit readiness paths are invalid" >&2
  exit 1
}

for required_file in "$role_id_source" "$secret_id_source" "$ca_source"; do
  [[ -r "$required_file" && -s "$required_file" ]] || {
    echo "Required Vault credential file is missing or empty" >&2
    exit 1
  }
done

for secret_name in "${app_secret_names[@]}"; do
  required_file="$app_secret_source_dir/$secret_name"
  [[ -f "$required_file" && -r "$required_file" && -s "$required_file" ]] || {
    echo "Required application secret file is missing or empty: $secret_name" >&2
    exit 1
  }
done

# File-backed Compose secrets retain host ownership. Keep those sources unchanged and
# hand the JVM only its allowlisted secrets in a private tmpfs, separate from AppRole.
chown beanflow:beanflow "$app_secret_runtime_dir"
chmod 0700 "$app_secret_runtime_dir"
for secret_name in "${app_secret_names[@]}"; do
  install --owner=beanflow --group=beanflow --mode=0400 \
    "$app_secret_source_dir/$secret_name" "$app_secret_runtime_dir/$secret_name"
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

setpriv --reuid=vault-proxy --regid=vault-proxy --init-groups --reset-env \
  env VAULT_ADDR="$BEANFLOW_VAULT_UPSTREAM_ADDR" VAULT_CACERT="$ca_file" \
  vault proxy -config="$proxy_config" &
proxy_pid="$!"
unset BEANFLOW_VAULT_UPSTREAM_ADDR

proxy_ready=false
readonly proxy_startup_timeout_seconds=60
readonly proxy_deadline_epoch=$(($(date +%s) + proxy_startup_timeout_seconds))
probe_status() {
  local remaining_seconds=$((proxy_deadline_epoch - $(date +%s)))
  local probe_max_time=2
  ((remaining_seconds > 0)) || { printf '000'; return; }
  ((remaining_seconds < probe_max_time)) && probe_max_time="$remaining_seconds"
  curl --http1.1 --noproxy '*' --connect-timeout 1 --max-time "$probe_max_time" \
    --silent --output /dev/null --write-out '%{http_code}' \
    "http://127.0.0.1:8100/v1/$1" || true
}
health_status=000
encryption_status=not_checked
blind_index_status=not_checked
while (($(date +%s) < proxy_deadline_epoch)); do
  kill -0 "$proxy_pid" 2>/dev/null || {
    wait "$proxy_pid"
    exit "$?"
  }
  sampled_health="$(probe_status sys/health)"
  # Preserve the last HTTP response if the final probe runs out of time (curl 000).
  [[ "$sampled_health" == 000 ]] || health_status="$sampled_health"
  case "$sampled_health" in
    200 | 429 | 473)
      # Health alone does not prove authenticated Transit access or a usable active node.
      # Discard metadata; the JVM retains the full key type/version/policy validation.
      sampled_encryption="$(probe_status "$BEANFLOW_VAULT_TRANSIT_MOUNT/keys/$BEANFLOW_VAULT_PERSONAL_DATA_ENCRYPTION_KEY")"
      sampled_blind_index="$(probe_status "$BEANFLOW_VAULT_TRANSIT_MOUNT/keys/$BEANFLOW_VAULT_PERSONAL_DATA_BLIND_INDEX_KEY")"
      [[ "$sampled_encryption" == 000 ]] || encryption_status="$sampled_encryption"
      [[ "$sampled_blind_index" == 000 ]] || blind_index_status="$sampled_blind_index"
      if [[ "$sampled_encryption" == 200 && "$sampled_blind_index" == 200 ]]; then
        proxy_ready=true
        break
      fi
      ;;
  esac
  (($(date +%s) + 1 < proxy_deadline_epoch)) && sleep 1
done
[[ "$proxy_ready" == true ]] || {
  echo "Vault Proxy did not become ready before the startup deadline (last HTTP: health=$health_status encryption=$encryption_status blind_index=$blind_index_status)" >&2
  echo "Check upstream Vault active/leader and seal state, then AppRole policy and Transit keys; JVM was not started" >&2
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
