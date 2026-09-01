#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dockerfile="$root/Dockerfile"
entrypoint="$root/deploy/backend/entrypoint.sh"
proxy_config="$root/deploy/vault/proxy.hcl"

fail() {
  echo "backend image contract failed: $*" >&2
  exit 1
}

for required in "$dockerfile" "$entrypoint" "$proxy_config"; do
  [[ -f "$required" ]] || fail "missing ${required#"$root/"}"
done

grep -Eq '^FROM eclipse-temurin:21[^ ]* AS build$' "$dockerfile" || fail "Java 21 build stage is required"
grep -Eq '^FROM hashicorp/vault:[0-9]+\.[0-9]+\.[0-9]+ AS vault$' "$dockerfile" || fail "pinned Vault stage is required"
grep -Eq '^FROM eclipse-temurin:21[^ ]*$' "$dockerfile" || fail "Java 21 runtime stage is required"
grep -q '^USER beanflow$' "$dockerfile" || fail "runtime must use the beanflow user"
grep -q '/actuator/health' "$dockerfile" || fail "actuator healthcheck is required"
grep -q 'COPY --from=vault /bin/vault /usr/local/bin/vault' "$dockerfile" || fail "Vault binary must come from the pinned image"
grep -q '/usr/bin/tini' "$dockerfile" || fail "tini must own signal forwarding"
! grep -Eq 'FROM [^ ]*:latest([ @]|$)' "$dockerfile" || fail "latest base images are forbidden"

grep -Eq 'type[[:space:]]*=[[:space:]]*"approle"' "$proxy_config" || fail "Vault Proxy must use AppRole"
grep -q 'use_auto_auth_token = "force"' "$proxy_config" || fail "Vault Proxy must force its auto-auth token"
grep -Eq 'address[[:space:]]*=[[:space:]]*"127\.0\.0\.1:8100"' "$proxy_config" || fail "Vault Proxy must listen on loopback"
grep -Eq 'role_id_file_path[[:space:]]*=[[:space:]]*"/run/secrets/BEANFLOW_VAULT_ROLE_ID"' "$proxy_config" || fail "role ID must be file-backed"
grep -Eq 'secret_id_file_path[[:space:]]*=[[:space:]]*"/run/secrets/BEANFLOW_VAULT_SECRET_ID"' "$proxy_config" || fail "secret ID must be file-backed"

bash -n "$entrypoint"
grep -q 'wait -n' "$entrypoint" || fail "entrypoint must terminate when either child exits"
! grep -Eq 'echo.*(ROLE_ID|SECRET_ID|TOKEN|PASSWORD|SECRET)' "$entrypoint" || fail "entrypoint must not print secret values"

echo "Backend image contract passed."
