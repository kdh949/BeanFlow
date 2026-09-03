#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <external-secrets-directory>" >&2
  exit 2
fi

readonly output_dir="$1"
readonly script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly repository_root="$(cd "$script_dir/../.." && pwd)"

[[ "$output_dir" == /* && "$output_dir" != "/" && "$output_dir" != "$repository_root"/* ]] || {
  echo "Vault credentials must be written to an absolute directory outside the repository" >&2
  exit 1
}
: "${VAULT_ADDR:?VAULT_ADDR is required}"
[[ "$VAULT_ADDR" == https://* ]] || {
  echo "VAULT_ADDR must use HTTPS" >&2
  exit 1
}
command -v vault >/dev/null || {
  echo "vault CLI is required" >&2
  exit 1
}

umask 077
mkdir -p "$output_dir"
chmod 0700 "$output_dir"

vault secrets list -format=json | grep -q '"transit/"' || vault secrets enable -path=transit transit
vault auth list -format=json | grep -q '"approle/"' || vault auth enable approle

vault write transit/keys/beanflow-personal-data \
  type=aes256-gcm96 derived=false exportable=false allow_plaintext_backup=false deletion_allowed=false >/dev/null
vault write transit/keys/beanflow-blind-index \
  type=hmac derived=false exportable=false allow_plaintext_backup=false deletion_allowed=false >/dev/null
vault policy write beanflow-portfolio "$script_dir/beanflow-policy.hcl" >/dev/null
vault write auth/approle/role/beanflow-portfolio \
  token_policies=beanflow-portfolio \
  token_ttl=1h \
  token_max_ttl=4h \
  token_num_uses=0 \
  secret_id_ttl=0 >/dev/null

vault read -field=role_id auth/approle/role/beanflow-portfolio/role-id \
  >"$output_dir/BEANFLOW_VAULT_ROLE_ID"
vault write -field=secret_id -f auth/approle/role/beanflow-portfolio/secret-id \
  >"$output_dir/BEANFLOW_VAULT_SECRET_ID"
chmod 0600 "$output_dir/BEANFLOW_VAULT_ROLE_ID" "$output_dir/BEANFLOW_VAULT_SECRET_ID"

echo "Vault Transit keys, least-privilege policy and AppRole credentials are ready."
echo "Copy the Vault CA certificate to $output_dir/BEANFLOW_VAULT_CA_PEM before deployment."
