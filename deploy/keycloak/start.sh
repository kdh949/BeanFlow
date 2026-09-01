#!/usr/bin/env bash
set -euo pipefail

readonly database_password_file="/run/secrets/BEANFLOW_KEYCLOAK_DB_PASSWORD"
readonly admin_password_file="/run/secrets/BEANFLOW_KEYCLOAK_ADMIN_PASSWORD"

for required_file in "$database_password_file" "$admin_password_file"; do
  [[ -r "$required_file" && -s "$required_file" ]] || {
    echo "Required Keycloak credential file is missing or empty" >&2
    exit 1
  }
done

: "${BEANFLOW_KEYCLOAK_ADMIN_USERNAME:?BEANFLOW_KEYCLOAK_ADMIN_USERNAME is required}"
: "${BEANFLOW_PUBLIC_ORIGIN:?BEANFLOW_PUBLIC_ORIGIN is required}"

export KC_DB_PASSWORD="$(<"$database_password_file")"
export KC_BOOTSTRAP_ADMIN_USERNAME="$BEANFLOW_KEYCLOAK_ADMIN_USERNAME"
export KC_BOOTSTRAP_ADMIN_PASSWORD="$(<"$admin_password_file")"

exec /opt/keycloak/bin/kc.sh start --import-realm
