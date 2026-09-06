#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

: "${DOPPLER_PROJECT:?Run this script with doppler run --no-fallback --}"
: "${DOPPLER_CONFIG:?Run this script with doppler run --no-fallback --}"
: "${BEANFLOW_IMAGE_TAG:?BEANFLOW_IMAGE_TAG is required}"
: "${BEANFLOW_SECRETS_DIR:?BEANFLOW_SECRETS_DIR is required}"
[[ "$BEANFLOW_IMAGE_TAG" =~ ^[0-9a-f]{40}$ && "$(git rev-parse HEAD)" == "$BEANFLOW_IMAGE_TAG" ]] || {
  echo "Checkout HEAD and BEANFLOW_IMAGE_TAG must be the same 40-character Git SHA" >&2
  exit 1
}
[[ -z "$(git status --porcelain)" ]] || {
  echo "Deployment requires a clean checkout; preserve local changes before retrying" >&2
  exit 1
}
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || {
  echo "This deployment requires an x86-64 Linux host" >&2
  exit 1
}

export COMPOSE_DISABLE_ENV_FILE=1
unset COMPOSE_ENV_FILES COMPOSE_FILE
umask 077
python3 scripts/deploy/prepare-doppler-deployment.py staging
readonly env_file="$(dirname "$BEANFLOW_SECRETS_DIR")/deployment.env"
bash scripts/deploy/verify-deployment.sh staging --env-file "$env_file"

compose=(docker compose --env-file "$env_file" -f compose.portfolio.yml -f compose.staging.yml)
if [[ "${BEANFLOW_KEYCLOAK_MODE:-bundled}" == external ]]; then
  compose+=(-f compose.external-keycloak.yml)
  curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
    "$BEANFLOW_OPERATIONS_OIDC_ISSUER_URI/.well-known/openid-configuration" |
    python3 scripts/deploy/validate-oidc-discovery.py
fi
"${compose[@]}" pull
for repository in "$BEANFLOW_API_IMAGE_REPOSITORY" "$BEANFLOW_WEB_IMAGE_REPOSITORY"; do
  revision="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$repository:$BEANFLOW_IMAGE_TAG")"
  [[ "$revision" == "$BEANFLOW_IMAGE_TAG" ]] || {
    echo "Application image revision does not match BEANFLOW_IMAGE_TAG" >&2
    exit 1
  }
done
"${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 300
"${compose[@]}" ps
"${compose[@]}" exec -T api curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8080/actuator/health
printf '\nStaging containers are healthy; verify external OIDC login separately.\n'
