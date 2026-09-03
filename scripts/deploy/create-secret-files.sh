#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <external-secrets-directory>" >&2
  exit 2
fi

readonly secrets_dir="$1"
readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ "$secrets_dir" == /* && "$secrets_dir" != "/" && "$secrets_dir" != "$root"/* ]] || {
  echo "Secret files must be written to an absolute directory outside the repository" >&2
  exit 1
}
command -v openssl >/dev/null || {
  echo "openssl is required" >&2
  exit 1
}

umask 077
mkdir -p "$secrets_dir"
chmod 0700 "$secrets_dir"

create_hex() {
  local name="$1"
  local path="$secrets_dir/$name"
  [[ -e "$path" ]] && return
  openssl rand -hex 32 >"$path"
  chmod 0600 "$path"
  echo "created $name"
}

create_base64url() {
  local name="$1"
  local path="$secrets_dir/$name"
  [[ -e "$path" ]] && return
  openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n' >"$path"
  printf '\n' >>"$path"
  chmod 0600 "$path"
  echo "created $name"
}

create_hex BEANFLOW_POSTGRES_PASSWORD
create_hex BEANFLOW_KEYCLOAK_DB_PASSWORD
create_hex BEANFLOW_KEYCLOAK_ADMIN_PASSWORD
create_base64url BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL
create_base64url BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL

echo "Generated local database and HMAC secrets without replacing existing files."
echo "Add AIStor, Toss sandbox and Vault AppRole/CA files before running deployment verification."
