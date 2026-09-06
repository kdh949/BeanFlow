#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly image="${1:?usage: test-backend-entrypoint.sh <backend-runtime-image> [boot-jar]}"
readonly fixture_dir="$(mktemp -d)"
image_container=""
cleanup_fixture() {
  [[ -z "$image_container" ]] || docker rm "$image_container" >/dev/null
  rm -rf "$fixture_dir"
}
trap cleanup_fixture EXIT

# Compile a probe against the selected boot jar's classes/libraries, then use the real Java 21 runtime.
# Host prerequisites: Python 3 and JDK 21+; no real deployment credentials are used.
if [[ $# -eq 2 ]]; then
  cp "$2" "$fixture_dir/app.jar"
  echo 'Validating the supplied boot jar in the selected runtime image.'
else
  image_container="$(docker create "$image")"
  docker cp "$image_container:/opt/beanflow/app.jar" "$fixture_dir/app.jar"
  docker rm "$image_container" >/dev/null
  image_container=""
  echo 'Validating the application packaged in the selected runtime image.'
fi
python3 - "$fixture_dir" <<'PY'
from pathlib import Path
import sys
import zipfile
root = Path(sys.argv[1])
(root / "lib").mkdir()
with zipfile.ZipFile(root / "app.jar") as archive:
    for name in archive.namelist():
        if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
            (root / "lib" / Path(name).name).write_bytes(archive.read(name))
        elif name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
            target = root / "application" / name.removeprefix("BOOT-INF/classes/")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
(root / "app.jar").unlink()
PY
mkdir "$fixture_dir/classes" "$fixture_dir/bin"
javac --release 21 -proc:none -cp "$fixture_dir/application:$fixture_dir/lib/*" -d "$fixture_dir/classes" \
  "$root/scripts/deploy/fixtures/EntrypointProbe.java"
chmod 0755 "$fixture_dir"

# Keep the production Java invocation contract, replacing only the application with the focused probe.
cat > "$fixture_dir/bin/java" <<'JAVA'
#!/usr/bin/env bash
set -euo pipefail
[[ "$(id -u)" == 10001 ]]
[[ "$*" == '-jar /opt/beanflow/app.jar' ]]
exec /opt/java/openjdk/bin/java -cp '/entrypoint-test/classes:/entrypoint-test/application:/entrypoint-test/lib/*' EntrypointProbe
JAVA
chmod 0755 "$fixture_dir/bin/java"

config_import="$(sed -n 's/^ *SPRING_CONFIG_IMPORT: //p' "$root/compose.portfolio.yml")"

docker run --rm --interactive --network none --read-only --user root \
  --security-opt no-new-privileges:true \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --tmpfs /run/beanflow-vault:rw,noexec,nosuid,size=1m,mode=0700 \
  --tmpfs /run/beanflow-vault-bootstrap:rw,noexec,nosuid,size=1m,mode=0700 \
  --tmpfs /run/beanflow-secrets:rw,noexec,nosuid,size=1m,mode=0700 \
  --tmpfs /run/secrets:rw,noexec,nosuid,size=1m,mode=0755 \
  --env "SPRING_CONFIG_IMPORT=$config_import" \
  --mount "type=bind,src=$root/deploy/backend/entrypoint.sh,dst=/usr/local/bin/beanflow-entrypoint,readonly" \
  --mount "type=bind,src=$root/deploy/vault/proxy.hcl,dst=/etc/beanflow/vault-proxy.hcl,readonly" \
  --mount "type=bind,src=$root/deploy/vault/beanflow-policy.hcl,dst=/etc/beanflow/fixture-policy.hcl,readonly" \
  --mount "type=bind,src=$fixture_dir,dst=/entrypoint-test,readonly" \
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
vault secrets enable transit >/dev/null
vault write transit/keys/beanflow-personal-data type=aes256-gcm96 derived=false exportable=false >/dev/null
vault write transit/keys/beanflow-blind-index type=hmac key_size=32 derived=false exportable=false >/dev/null
vault policy write entrypoint-test /etc/beanflow/fixture-policy.hcl >/dev/null
vault write auth/approle/role/entrypoint-test token_policies=entrypoint-test token_ttl=5m >/dev/null
vault read -field=role_id auth/approle/role/entrypoint-test/role-id \
  > /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_ROLE_ID
vault write -field=secret_id -force auth/approle/role/entrypoint-test/secret-id \
  > /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_SECRET_ID
cp "$VAULT_CACERT" /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_CA_PEM
chmod 0600 /run/beanflow-vault-bootstrap/*

secret_names=(BEANFLOW_DB_PASSWORD BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL
  BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL BEANFLOW_AISTOR_ACCESS_KEY BEANFLOW_AISTOR_SECRET_KEY
  TOSS_CLIENT_KEY TOSS_SECRET_KEY)
for name in "${secret_names[@]}"; do
  printf 'fixture-%s-#$=\\ value\nnext' "$name" > "/run/secrets/$name"
  chmod 0600 "/run/secrets/$name"
done

# An inherited root CLI configuration must not reach the unprivileged Proxy.
unset VAULT_TOKEN
export VAULT_CONFIG_PATH=/root/.vault
export BEANFLOW_VAULT_UPSTREAM_ADDR="$VAULT_ADDR"
export BEANFLOW_ENTRYPOINT_TEST_SETTING=preserved
export JAVA_TOOL_OPTIONS=-Xms64m
export PATH="/entrypoint-test/bin:$PATH"
set +e
timeout 75s /bin/bash /usr/local/bin/beanflow-entrypoint > /tmp/entrypoint.log 2>&1
status="$?"
set -e
if [[ "$status" != 0 || ! -f /tmp/beanflow-entrypoint-test-passed ]]; then
  if grep -q '/root/.vault: permission denied' /tmp/entrypoint.log; then
    echo 'Vault Proxy inherited the root CLI configuration' >&2
  else
    echo "Entrypoint runtime smoke failed with exit $status" >&2
    # Fixture values only: retain exception types and file paths without logging property values.
    grep -E 'Exception|Caused by:|^Required |invalid Upgrade request header|at .*VaultTransitPersonalDataAdapter|at EntrypointProbe' \
      /tmp/entrypoint.log >&2 || true
  fi
  exit 1
fi
for name in "${secret_names[@]}"; do
  [[ "$(stat -c '%u:%g:%a' "/run/secrets/$name")" == 0:0:600 ]]
  cmp "/run/secrets/$name" "/run/beanflow-secrets/$name"
  setpriv --reuid=vault-proxy --regid=vault-proxy --init-groups \
    /bin/bash -c '[[ ! -r "$1" ]]' -- "/run/beanflow-secrets/$name"
done

# Each required secret must fail before either child starts when missing or empty.
for name in "${secret_names[@]}"; do
  cp "/run/secrets/$name" /tmp/saved-secret
  for state in missing empty; do
    rm -f "/run/secrets/$name"
    [[ "$state" == missing ]] || install --mode=0600 /dev/null "/run/secrets/$name"
    set +e
    timeout 5s /bin/bash /usr/local/bin/beanflow-entrypoint > /tmp/negative.log 2>&1
    status="$?"
    set -e
    [[ "$status" == 1 ]]
    grep -Fxq "Required application secret file is missing or empty: $name" /tmp/negative.log
    ! grep -q 'Vault Proxy started\|Picked up JAVA_TOOL_OPTIONS' /tmp/negative.log
  done
  install --mode=0600 /tmp/saved-secret "/run/secrets/$name"
done
echo 'Entrypoint real JVM/Spring secrets, UID isolation, 14 missing/empty cases and production adapter Transit metadata/encrypt/decrypt/HMAC over Vault TLS/AppRole passed.'
echo 'Known Vault 2.0.4 limitation: AAD rewrap fails; explicit DEPENDENCY_UNAVAILABLE verified, successful rewrap is not supported.'
CONTAINER
