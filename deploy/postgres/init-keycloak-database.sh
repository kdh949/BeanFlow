#!/usr/bin/env bash
set -euo pipefail

readonly password_file="/run/secrets/BEANFLOW_KEYCLOAK_DB_PASSWORD"
[[ -r "$password_file" && -s "$password_file" ]] || {
  echo "Keycloak database password file is missing or empty" >&2
  exit 1
}

keycloak_password="$(<"$password_file")"

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname postgres \
  --set=keycloak_role=keycloak \
  --set=keycloak_password="$keycloak_password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'keycloak_role', :'keycloak_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'keycloak_role')
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', 'keycloak', :'keycloak_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak')
\gexec
SQL
