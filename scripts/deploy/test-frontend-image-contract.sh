#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dockerfile="$root/frontend/Dockerfile"
nginx_config="$root/frontend/nginx/default.conf"

fail() {
  echo "frontend image contract failed: $*" >&2
  exit 1
}

for required in "$dockerfile" "$nginx_config"; do
  [[ -f "$required" ]] || fail "missing ${required#"$root/"}"
done

grep -Eq '^FROM node:24\.[0-9]+\.[0-9]+-alpine[0-9.]+ AS build$' "$dockerfile" || fail "pinned Node 24 build image is required"
grep -Eq '^FROM nginxinc/nginx-unprivileged:[0-9]+\.[0-9]+\.[0-9]+-alpine[0-9.]+$' "$dockerfile" || fail "pinned unprivileged Nginx image is required"
grep -q '^USER 101$' "$dockerfile" || fail "runtime must explicitly use the unprivileged Nginx user"
grep -q 'npm ci' "$dockerfile" || fail "reproducible npm install is required"
grep -q 'npm run build' "$dockerfile" || fail "frontend production build is required"
grep -q '/healthz' "$dockerfile" || fail "frontend healthcheck is required"
! grep -Eq 'FROM [^ ]*:latest([ @]|$)' "$dockerfile" || fail "latest base images are forbidden"

grep -Eq 'location[[:space:]]+/api/' "$nginx_config" || fail "/api proxy is required"
grep -Eq 'location[[:space:]]+\^~[[:space:]]+/auth/realms/' "$nginx_config" || fail "Keycloak realm proxy is required"
grep -Eq 'location[[:space:]]+\^~[[:space:]]+/auth/admin/' "$nginx_config" || fail "Keycloak admin route must be explicit"
grep -A2 -E 'location[[:space:]]+\^~[[:space:]]+/auth/admin/' "$nginx_config" | grep -q 'return 404' || fail "Keycloak admin route must be blocked"
grep -q 'try_files \$uri \$uri/ /index.html' "$nginx_config" || fail "SPA fallback is required"
grep -q 'proxy_set_header X-Forwarded-Proto https' "$nginx_config" || fail "Sophos TLS termination must be explicit"
grep -q '\$request_method \$uri \$server_protocol' "$nginx_config" || fail "safe request log format is required"
! grep -q '\$request_uri' "$nginx_config" || fail "query-bearing request_uri must not be logged"
! grep -q '\$http_referer' "$nginx_config" || fail "raw Referer must not be logged"
grep -Eq 'location[[:space:]]+=[[:space:]]+/healthz' "$nginx_config" || fail "health endpoint is required"

echo "Frontend image contract passed."
