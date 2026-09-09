#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runtime_dir="$(mktemp -d)"
trap 'rm -rf "$runtime_dir"' EXIT
secrets_dir="$runtime_dir/secrets"
mkdir -m 0700 "$secrets_dir"

secret_names=(
  BEANFLOW_POSTGRES_PASSWORD
  BEANFLOW_KEYCLOAK_DB_PASSWORD
  BEANFLOW_KEYCLOAK_ADMIN_PASSWORD
  BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL
  BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL
  BEANFLOW_AISTOR_ACCESS_KEY
  BEANFLOW_AISTOR_SECRET_KEY
  TOSS_CLIENT_KEY
  TOSS_SECRET_KEY
  BEANFLOW_VAULT_ROLE_ID
  BEANFLOW_VAULT_SECRET_ID
  BEANFLOW_VAULT_CA_PEM
)

for name in "${secret_names[@]}"; do
  value="contract-test-$name"
  printf '%s\n' "$value" >"$secrets_dir/$name"
  chmod 0600 "$secrets_dir/$name"
done

for environment in staging prod; do
  env_file="$runtime_dir/$environment.env"
  backend_octet=28
  [[ "$environment" == prod ]] && backend_octet=29
  printf '%s\n' \
    "COMPOSE_PROJECT_NAME=beanflow-$environment-contract" \
    "BEANFLOW_BIND_ADDRESS=192.0.2.10" \
    "BEANFLOW_HTTP_PORT=8080" \
    "BEANFLOW_PUBLIC_ORIGIN=https://portfolio.example.test" \
    "BEANFLOW_SECRETS_DIR=$secrets_dir" \
    "BEANFLOW_IMAGE_TAG=contract-test-sha" \
    "BEANFLOW_API_IMAGE_REPOSITORY=beanflow-api" \
    "BEANFLOW_WEB_IMAGE_REPOSITORY=beanflow-web" \
    "BEANFLOW_PULL_POLICY=never" \
    "BEANFLOW_POSTGRES_PLATFORM=linux/amd64" \
    "BEANFLOW_AUTH_TRUSTED_PROXY_CIDRS=172.$backend_octet.0.10/32,192.0.2.1/32" \
    "BEANFLOW_AISTOR_ENDPOINT=https://aistor.internal.example.test" \
    "BEANFLOW_AISTOR_PUBLIC_ENDPOINT=https://objects.example.test" \
    "BEANFLOW_AISTOR_BUCKET=beanflow-$environment" \
    "BEANFLOW_AISTOR_REGION=us-east-1" \
    "BEANFLOW_CURSOR_HMAC_ACTIVE_KEY_ID=portfolio-v1" \
    "BEANFLOW_VAULT_ADDR=https://vault.internal.example.test" \
    "BEANFLOW_VAULT_TRANSIT_MOUNT=transit" \
    "BEANFLOW_VAULT_PERSONAL_DATA_ENCRYPTION_KEY=beanflow-personal-data" \
    "BEANFLOW_VAULT_PERSONAL_DATA_BLIND_INDEX_KEY=beanflow-blind-index" \
    "BEANFLOW_VAULT_BLIND_INDEX_WRITE_VERSION=1" \
    "BEANFLOW_VAULT_BLIND_INDEX_SEARCH_VERSIONS=1" \
    >"$env_file"

  "$root/scripts/deploy/verify-deployment.sh" "$environment" --env-file "$env_file"
done

# External mode must work without either bundled Keycloak credential file.
rm "$secrets_dir/BEANFLOW_KEYCLOAK_DB_PASSWORD" "$secrets_dir/BEANFLOW_KEYCLOAK_ADMIN_PASSWORD"
for environment in staging prod; do
  external_env_file="$runtime_dir/$environment-external.env"
  cat "$runtime_dir/$environment.env" > "$external_env_file"
  cat >> "$external_env_file" <<'ENV'
BEANFLOW_KEYCLOAK_MODE=external
BEANFLOW_OPERATIONS_OIDC_AUTHORIZATION_SERVER_URL=https://sso.example.test:5443
BEANFLOW_OPERATIONS_OIDC_ISSUER_URI=https://sso.example.test:5443/realms/beanflow
BEANFLOW_OPERATIONS_OIDC_REALM=beanflow
BEANFLOW_OPERATIONS_OIDC_CLIENT_ID=beanflow
BEANFLOW_JWK_SET_URI=https://sso.example.test:5443/realms/beanflow/protocol/openid-connect/certs
ENV
  python3 "$root/scripts/deploy/render_external_nginx.py" --env-file "$external_env_file" > "$runtime_dir/external-keycloak.conf"
  "$root/scripts/deploy/verify-deployment.sh" "$environment" --env-file "$external_env_file"
done

# The effective proxy must match the API's signing configuration, including same-origin mode.
same_origin_env="$runtime_dir/same-origin.env"
sed 's|BEANFLOW_AISTOR_PUBLIC_ENDPOINT=https://objects.example.test|BEANFLOW_AISTOR_PUBLIC_ENDPOINT=https://portfolio.example.test|' \
  "$runtime_dir/staging-external.env" > "$same_origin_env"
if "$root/scripts/deploy/verify-deployment.sh" staging --env-file "$same_origin_env" > "$runtime_dir/stale.log" 2>&1; then
  echo "Stale Nginx configuration was unexpectedly accepted" >&2
  exit 1
fi
grep -q 'external-keycloak.conf differs' "$runtime_dir/stale.log"
python3 "$root/scripts/deploy/render_external_nginx.py" --env-file "$same_origin_env" > "$runtime_dir/external-keycloak.conf"
"$root/scripts/deploy/verify-deployment.sh" staging --env-file "$same_origin_env"

expect_external_rejection() {
  local key="$1" value="$2" expected_message="$3"
  local invalid_env_file="$runtime_dir/invalid-external.env"
  awk -v key="$key" 'index($0, key "=") != 1' "$runtime_dir/staging-external.env" > "$invalid_env_file"
  printf '%s=%s\n' "$key" "$value" >> "$invalid_env_file"
  if "$root/scripts/deploy/verify-deployment.sh" staging --env-file "$invalid_env_file" > "$runtime_dir/rejection.log" 2>&1; then
    echo "external Keycloak contract unexpectedly accepted: $key" >&2
    exit 1
  fi
  grep -q "$expected_message" "$runtime_dir/rejection.log" || {
    echo "external Keycloak rejection did not match expected reason: $key" >&2
    cat "$runtime_dir/rejection.log" >&2
    exit 1
  }
}
expect_external_rejection BEANFLOW_KEYCLOAK_MODE unknown 'must be bundled or external'
expect_external_rejection BEANFLOW_OPERATIONS_OIDC_CLIENT_ID '' 'required for external Keycloak'
expect_external_rejection BEANFLOW_OPERATIONS_OIDC_ISSUER_URI http://sso.example.test/realms/beanflow 'must be an absolute HTTPS URL'
expect_external_rejection BEANFLOW_OPERATIONS_OIDC_REALM different 'issuer must match'
expect_external_rejection BEANFLOW_JWK_SET_URI https://other.example.test/certs 'JWKS must belong'
expect_external_rejection BEANFLOW_OPERATIONS_OIDC_AUTHORIZATION_SERVER_URL 'https://user:password@sso.example.test:5443' 'without credentials'

validator="$root/scripts/deploy/validate-trusted-proxies.py"

expect_trusted_proxy_rejection() {
  local frontend_cidr="$1"
  local trusted_cidrs="$2"
  if python3 "$validator" "$frontend_cidr" "$trusted_cidrs" >/dev/null 2>&1; then
    echo "trusted proxy contract unexpectedly accepted: $trusted_cidrs" >&2
    exit 1
  fi
}

python3 "$validator" "172.28.0.10/32" "172.28.0.10/32,192.0.2.1/32"
python3 "$validator" "172.28.0.10/32" "192.0.2.1/32,172.28.0.10/32,2001:db8::1/128"
expect_trusted_proxy_rejection "172.28.0.10/32" "172.28.0.10/32"
expect_trusted_proxy_rejection "172.28.0.10/32" "172.28.0.10/32,0.0.0.0/0"
expect_trusted_proxy_rejection "172.28.0.10/32" "172.28.0.10/32,192.0.2.0/24"
expect_trusted_proxy_rejection "172.28.0.10/32" "192.0.2.1/32"
expect_trusted_proxy_rejection "172.28.0.10/32" "172.28.0.10/32,172.28.0.10/32,192.0.2.1/32"
expect_trusted_proxy_rejection "172.28.0.10/32" "172.28.0.10/32,not-a-cidr"

grep -q '"code.challenge.method": "S256"' "$root/deploy/keycloak/beanflow-realm.json"
grep -q '"claim.name": "roles"' "$root/deploy/keycloak/beanflow-realm.json"
grep -q '"protocolMapper": "oidc-audience-mapper"' "$root/deploy/keycloak/beanflow-realm.json"
grep -q '"included.client.audience": "beanflow-operations"' "$root/deploy/keycloak/beanflow-realm.json"
bash -n "$root/deploy/keycloak/start.sh"
bash -n "$root/deploy/postgres/init-keycloak-database.sh"
bash -n "$root/deploy/vault/bootstrap-transit.sh"

echo "Deployment contract tests passed."
