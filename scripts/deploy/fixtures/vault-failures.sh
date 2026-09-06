#!/usr/bin/env bash
# Real isolated Vault failure/recovery cases. Never point this fixture at a deployment Vault.
set -euo pipefail

admin_vault() {
  env -u VAULT_CONFIG_PATH VAULT_TOKEN=entrypoint-fixture-root vault "$@"
}

expect_startup_failure() {
  local scenario="$1" expected="$2" status
  local started=$(date +%s)
  set +e
  timeout 75s bash /usr/local/bin/beanflow-entrypoint > /tmp/vault-failure.log 2>&1
  status="$?"
  set -e
  [[ "$status" == 1 ]] || { echo "Unexpected startup exit for $scenario: $status" >&2; exit 1; }
  grep -Fq "$expected" /tmp/vault-failure.log || {
    echo "Expected startup diagnostic missing for $scenario" >&2
    grep -E 'Vault Proxy did not|Check upstream|^Required ' /tmp/vault-failure.log >&2 || true
    exit 1
  }
  ! grep -q 'Picked up JAVA_TOOL_OPTIONS\|Spring Boot' /tmp/vault-failure.log
  # Timeout is measured from the real clock, not a shortened test-only production setting.
  (($(date +%s) - started <= 70))
  echo "Vault failure $scenario: startup rejected within deadline; JVM not started."
}

cp /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_ROLE_ID /tmp/valid-role-id
cp /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_SECRET_ID /tmp/valid-secret-id
# Repeated invalid logins can lock an AppRole. Isolate that expected lockout from the healthy identity.
admin_vault write auth/approle/role/invalid-identity-fixture token_policies=entrypoint-test >/dev/null
admin_vault read -field=role_id auth/approle/role/invalid-identity-fixture/role-id \
  > /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_ROLE_ID
printf 'invalid-fixture-secret-id' > /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_SECRET_ID
expect_startup_failure invalid-approle 'Vault Proxy did not become ready before the startup deadline'
install --mode=0600 /tmp/valid-role-id /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_ROLE_ID
install --mode=0600 /tmp/valid-secret-id /run/beanflow-vault-bootstrap/BEANFLOW_VAULT_SECRET_ID
rm /tmp/valid-role-id /tmp/valid-secret-id

# Key metadata access must fail even when health and auto-auth are healthy.
sed '/path "transit\/keys\/beanflow-blind-index" {/,/}/d' /etc/beanflow/fixture-policy.hcl > /tmp/denied-policy.hcl
admin_vault policy write entrypoint-test /tmp/denied-policy.hcl >/dev/null
expect_startup_failure denied-transit-metadata 'health=200 encryption=200 blind_index=403'
admin_vault policy write entrypoint-test /etc/beanflow/fixture-policy.hcl >/dev/null

admin_vault operator seal >/dev/null
expect_startup_failure sealed-vault 'Vault Proxy did not become ready before the startup deadline'
# The unseal key belongs only to the ephemeral dev fixture. Do not log it or retain it in artifacts.
fixture_unseal_key="$(sed -n 's/^Unseal Key: //p' /tmp/upstream.log | head -n 1)"
[[ -n "$fixture_unseal_key" ]]
admin_vault operator unseal "$fixture_unseal_key" >/dev/null
unset fixture_unseal_key
for attempt in {1..50}; do
  if admin_vault read transit/keys/beanflow-personal-data >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done
admin_vault read transit/keys/beanflow-personal-data >/dev/null

echo 'Restored AppRole, Transit policy and unseal state for full application startup.'
