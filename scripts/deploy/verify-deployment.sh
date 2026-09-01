#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 || "$2" != "--env-file" ]]; then
  echo "usage: $0 <staging|prod> --env-file <path>" >&2
  exit 2
fi

readonly environment="$1"
readonly env_file="$3"
readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
case "$environment" in
  staging) readonly overlay="$root/compose.staging.yml"; readonly frontend_ip="172.28.0.10/32" ;;
  prod) readonly overlay="$root/compose.prod.yml"; readonly frontend_ip="172.29.0.10/32" ;;
  *) echo "environment must be staging or prod" >&2; exit 2 ;;
esac

[[ -f "$env_file" ]] || {
  echo "env file does not exist: $env_file" >&2
  exit 1
}

env_value() {
  local key="$1"
  awk -v key="$key" 'index($0, key "=") == 1 { print substr($0, length(key) + 2); found=1; exit } END { if (!found) exit 1 }' "$env_file"
}

readonly secrets_dir="$(env_value BEANFLOW_SECRETS_DIR)"
readonly public_origin="$(env_value BEANFLOW_PUBLIC_ORIGIN)"
readonly bind_address="$(env_value BEANFLOW_BIND_ADDRESS)"
readonly image_tag="$(env_value BEANFLOW_IMAGE_TAG)"
readonly trusted_proxies="$(env_value BEANFLOW_AUTH_TRUSTED_PROXY_CIDRS)"

[[ "$secrets_dir" == /* && "$secrets_dir" != "/" && "$secrets_dir" != "$root"/* ]] || {
  echo "BEANFLOW_SECRETS_DIR must be an absolute directory outside the repository" >&2
  exit 1
}
[[ "$public_origin" == https://* && "$public_origin" != */ ]] || {
  echo "BEANFLOW_PUBLIC_ORIGIN must be an HTTPS origin without a trailing slash" >&2
  exit 1
}
[[ -n "$bind_address" && "$bind_address" != "0.0.0.0" && "$bind_address" != "::" ]] || {
  echo "BEANFLOW_BIND_ADDRESS must be an explicit non-wildcard server address" >&2
  exit 1
}
[[ -n "$image_tag" && "$image_tag" != "latest" && "$image_tag" != *replace* ]] || {
  echo "BEANFLOW_IMAGE_TAG must be an explicit immutable candidate tag" >&2
  exit 1
}
python3 "$root/scripts/deploy/validate-trusted-proxies.py" "$frontend_ip" "$trusted_proxies"

required_secrets=(
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

for name in "${required_secrets[@]}"; do
  path="$secrets_dir/$name"
  [[ -f "$path" && -s "$path" ]] || {
    echo "required secret file is missing or empty: $name" >&2
    exit 1
  }
  if stat -f '%Lp' "$path" >/dev/null 2>&1; then
    mode="$(stat -f '%Lp' "$path")"
  else
    mode="$(stat -c '%a' "$path")"
  fi
  (( (8#$mode & 8#077) == 0 )) || {
    echo "secret file must not grant group/other permissions: $name" >&2
    exit 1
  }
done

[[ "$(<"$secrets_dir/TOSS_CLIENT_KEY")" == test_ck_* ]] || {
  echo "TOSS_CLIENT_KEY must be a Toss sandbox test client key" >&2
  exit 1
}
[[ "$(<"$secrets_dir/TOSS_SECRET_KEY")" == test_sk_* ]] || {
  echo "TOSS_SECRET_KEY must be a Toss sandbox test secret key" >&2
  exit 1
}

docker compose \
  --env-file "$env_file" \
  --file "$root/compose.portfolio.yml" \
  --file "$overlay" \
  config --format json | "$root/scripts/deploy/inspect-compose.py"

echo "$environment deployment preflight passed."
