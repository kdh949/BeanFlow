#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly runtime_dir="$(mktemp -d)"
readonly project_name="beanflow-identity-contract-$RANDOM"
readonly secrets_dir="$runtime_dir/secrets"
readonly env_file="$runtime_dir/deployment.env"

cleanup() {
  docker compose \
    --env-file "$env_file" \
    --file "$root/compose.portfolio.yml" \
    --file "$root/compose.staging.yml" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$runtime_dir"
}
trap cleanup EXIT

mkdir -m 0700 "$secrets_dir"
for name in \
  BEANFLOW_POSTGRES_PASSWORD \
  BEANFLOW_KEYCLOAK_DB_PASSWORD \
  BEANFLOW_KEYCLOAK_ADMIN_PASSWORD \
  BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL \
  BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL \
  BEANFLOW_AISTOR_ACCESS_KEY \
  BEANFLOW_AISTOR_SECRET_KEY \
  BEANFLOW_VAULT_ROLE_ID \
  BEANFLOW_VAULT_SECRET_ID \
  BEANFLOW_VAULT_CA_PEM; do
  printf 'identity-contract-%s\n' "$name" >"$secrets_dir/$name"
  chmod 0600 "$secrets_dir/$name"
done
printf 'test_ck_identity_contract\n' >"$secrets_dir/TOSS_CLIENT_KEY"
printf 'test_sk_identity_contract\n' >"$secrets_dir/TOSS_SECRET_KEY"
chmod 0600 "$secrets_dir/TOSS_CLIENT_KEY" "$secrets_dir/TOSS_SECRET_KEY"

printf '%s\n' \
  "COMPOSE_PROJECT_NAME=$project_name" \
  "BEANFLOW_BIND_ADDRESS=192.0.2.10" \
  "BEANFLOW_PUBLIC_ORIGIN=https://portfolio.example.test" \
  "BEANFLOW_SECRETS_DIR=$secrets_dir" \
  "BEANFLOW_IMAGE_TAG=identity-contract" \
  "BEANFLOW_API_IMAGE_REPOSITORY=beanflow-api" \
  "BEANFLOW_WEB_IMAGE_REPOSITORY=beanflow-web" \
  "BEANFLOW_PULL_POLICY=never" \
  "BEANFLOW_POSTGRES_PLATFORM=linux/amd64" \
  "BEANFLOW_AUTH_TRUSTED_PROXY_CIDRS=172.28.0.10/32,192.0.2.1/32" \
  "BEANFLOW_AISTOR_ENDPOINT=https://aistor.internal.example.test" \
  "BEANFLOW_AISTOR_PUBLIC_ENDPOINT=https://objects.example.test" \
  "BEANFLOW_AISTOR_BUCKET=beanflow-contract" \
  "BEANFLOW_CURSOR_HMAC_ACTIVE_KEY_ID=portfolio-v1" \
  "BEANFLOW_VAULT_ADDR=https://vault.internal.example.test" \
  "BEANFLOW_VAULT_BLIND_INDEX_WRITE_VERSION=1" \
  "BEANFLOW_VAULT_BLIND_INDEX_SEARCH_VERSIONS=1" \
  >"$env_file"

docker compose \
  --env-file "$env_file" \
  --file "$root/compose.portfolio.yml" \
  --file "$root/compose.staging.yml" \
  up --detach --wait --no-build postgres keycloak

docker compose \
  --env-file "$env_file" \
  --file "$root/compose.portfolio.yml" \
  --file "$root/compose.staging.yml" \
  exec --no-TTY keycloak /bin/bash -ec \
  "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /auth/realms/beanflow/.well-known/openid-configuration HTTP/1.0\\r\\nHost: portfolio.example.test\\r\\n\\r\\n' >&3; response=\"\$(cat <&3)\"; grep -q '200 OK' <<<\"\$response\"; grep -q '\"issuer\":\"https://portfolio.example.test/auth/realms/beanflow\"' <<<\"\$response\""

echo "PostgreSQL and Keycloak identity stack smoke test passed."
