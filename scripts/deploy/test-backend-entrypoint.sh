#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly image="${1:?usage: test-backend-entrypoint.sh <backend-runtime-image>}"
readonly fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

# Only the JVM is replaced: the real entrypoint, Vault binary, TLS and AppRole run below.
cat > "$fixture_dir/java" <<'JAVA'
#!/usr/bin/env bash
set -euo pipefail
[[ "$(id -u)" == 10001 ]]
[[ "${BEANFLOW_ENTRYPOINT_TEST_SETTING:-}" == preserved ]]
[[ "${JAVA_TOOL_OPTIONS:-}" == -Xms64m ]]
[[ "$*" == '-jar /opt/beanflow/app.jar' ]]
[[ ! -r /run/beanflow-vault/BEANFLOW_VAULT_ROLE_ID ]]
[[ ! -r /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_ROLE_ID ]]
for attempt in {1..20}; do
  if curl --fail --silent --connect-timeout 1 --max-time 2 \
    --output /dev/null http://127.0.0.1:8100/v1/auth/token/lookup-self; then
    touch /tmp/beanflow-entrypoint-test-passed
    exit 0
  fi
  sleep 0.2
done
echo 'Vault Proxy did not authenticate the JVM request with AppRole' >&2
exit 1
JAVA
chmod 0755 "$fixture_dir/java"

docker run --rm --interactive --network none --read-only --user root \
  --security-opt no-new-privileges:true \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --tmpfs /run/beanflow-vault:rw,noexec,nosuid,size=1m,mode=0700 \
  --tmpfs /run/beanflow-vault-bootstrap:rw,noexec,nosuid,size=1m,mode=0700 \
  --mount "type=bind,src=$root/deploy/backend/entrypoint.sh,dst=/usr/local/bin/beanflow-entrypoint,readonly" \
  --mount "type=bind,src=$root/deploy/vault/proxy.hcl,dst=/etc/beanflow/vault-proxy.hcl,readonly" \
  --mount "type=bind,src=$fixture_dir/java,dst=/opt/java/openjdk/bin/java,readonly" \
  --entrypoint /bin/bash "$image" -se <<'CONTAINER'
set -euo pipefail
upstream_pid=""
cleanup() {
  [[ -z "$upstream_pid" ]] || kill "$upstream_pid" 2>/dev/null || true
}
trap cleanup EXIT

mkdir -m 0700 /tmp/entrypoint-vault-tls
vault server -dev -dev-tls -dev-no-store-token -dev-root-token-id=entrypoint-fixture-root \
  -dev-tls-cert-dir=/tmp/entrypoint-vault-tls -log-level=error > /tmp/upstream.log 2>&1 &
upstream_pid="$!"
export VAULT_ADDR=https://127.0.0.1:8200
export VAULT_CACERT=/tmp/entrypoint-vault-tls/vault-ca.pem
export VAULT_TOKEN=entrypoint-fixture-root

ready=false
for attempt in {1..50}; do
  if vault status >/dev/null 2>&1; then
    ready=true
    break
  fi
  kill -0 "$upstream_pid" 2>/dev/null || {
    grep -iE 'error|failed|mlock|read-only' /tmp/upstream.log >&2 || true
    exit 1
  }
  sleep 0.2
done
[[ "$ready" == true ]] || { echo 'Ephemeral test Vault did not start' >&2; exit 1; }

vault auth enable approle >/dev/null
vault write auth/approle/role/entrypoint-test token_policies=default token_ttl=5m >/dev/null
vault read -field=role_id auth/approle/role/entrypoint-test/role-id \
  > /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_ROLE_ID
vault write -field=secret_id -force auth/approle/role/entrypoint-test/secret-id \
  > /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_SECRET_ID
cp "$VAULT_CACERT" /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_CA_PEM
chmod 0600 /run/beanflow-vault-bootstrap/*

# An inherited root CLI configuration must not reach the unprivileged Proxy.
unset VAULT_TOKEN
export VAULT_CONFIG_PATH=/root/.vault
export BEANFLOW_VAULT_UPSTREAM_ADDR="$VAULT_ADDR"
export BEANFLOW_ENTRYPOINT_TEST_SETTING=preserved
export JAVA_TOOL_OPTIONS=-Xms64m
set +e
timeout 75s /bin/bash /usr/local/bin/beanflow-entrypoint > /tmp/entrypoint.log 2>&1
status="$?"
set -e
if [[ "$status" != 0 || ! -f /tmp/beanflow-entrypoint-test-passed ]]; then
  if grep -q '/root/.vault: permission denied' /tmp/entrypoint.log; then
    echo 'Vault Proxy inherited the root CLI configuration' >&2
  else
    echo "Entrypoint runtime smoke failed with exit $status" >&2
  fi
  exit 1
fi
echo 'Entrypoint Vault TLS/AppRole, root environment isolation and JVM UID/environment handoff passed.'
CONTAINER
