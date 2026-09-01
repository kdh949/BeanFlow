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
  case "$name" in
    TOSS_CLIENT_KEY) value="test_ck_contract" ;;
    TOSS_SECRET_KEY) value="test_sk_contract" ;;
  esac
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

grep -q '"code.challenge.method": "S256"' "$root/deploy/keycloak/beanflow-realm.json"
grep -q '"claim.name": "roles"' "$root/deploy/keycloak/beanflow-realm.json"
grep -q '"protocolMapper": "oidc-audience-mapper"' "$root/deploy/keycloak/beanflow-realm.json"
grep -q '"included.client.audience": "beanflow-operations"' "$root/deploy/keycloak/beanflow-realm.json"
bash -n "$root/deploy/keycloak/start.sh"
bash -n "$root/deploy/postgres/init-keycloak-database.sh"
bash -n "$root/deploy/vault/bootstrap-transit.sh"

echo "Deployment contract tests passed."
