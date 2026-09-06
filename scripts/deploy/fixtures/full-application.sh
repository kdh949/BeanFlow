#!/usr/bin/env bash
# Test-only configuration in an isolated network; the packaged application and entrypoint are unmodified.
set -euo pipefail

printf '%s' "$BEANFLOW_RUNTIME_TEST_DB_PASSWORD" > /run/secrets/BEANFLOW_DB_PASSWORD
unset BEANFLOW_RUNTIME_TEST_DB_PASSWORD
for name in BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL; do
  # Random test-only 32-byte keys, encoded without padding.
  head -c 32 /dev/urandom | base64 -w0 | tr '+/' '-_' | tr -d '=' > "/run/secrets/$name"
done
printf 'fixture-access' > /run/secrets/BEANFLOW_AISTOR_ACCESS_KEY
printf 'fixture-secret' > /run/secrets/BEANFLOW_AISTOR_SECRET_KEY
printf 'test_gck_runtime_fixture' > /run/secrets/TOSS_CLIENT_KEY
printf 'test_gsk_runtime_fixture' > /run/secrets/TOSS_SECRET_KEY
chmod 0600 /run/secrets/*
export SPRING_PROFILES_ACTIVE=portfolio
export LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_AVAILABILITY=DEBUG
export JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25'
export BEANFLOW_DB_URL=jdbc:postgresql://postgres:5432/beanflow
export BEANFLOW_DB_USERNAME=beanflow
export BEANFLOW_FRONTEND_BASE_URL=https://beanflow.example.test
export BEANFLOW_CURSOR_HMAC_ACTIVE_KEY_ID=fixture-v1
export BEANFLOW_VAULT_PROXY_BASE_URI=http://127.0.0.1:8100
export BEANFLOW_VAULT_BLIND_INDEX_WRITE_VERSION=1
export BEANFLOW_VAULT_BLIND_INDEX_SEARCH_VERSIONS=1
export BEANFLOW_JWK_SET_URI=https://auth.example.test/realms/beanflow/protocol/openid-connect/certs
export BEANFLOW_OPERATIONS_OIDC_ISSUER_URI=https://auth.example.test/realms/beanflow
export BEANFLOW_OPERATIONS_OIDC_AUTHORIZATION_SERVER_URL=https://auth.example.test
export BEANFLOW_OPERATIONS_OIDC_REALM=beanflow
export BEANFLOW_OPERATIONS_OIDC_CLIENT_ID=beanflow
export BEANFLOW_OPERATIONS_OIDC_REDIRECT_URI=https://beanflow.example.test/ops/auth/callback
export BEANFLOW_OPERATIONS_OIDC_POST_LOGOUT_REDIRECT_URI=https://beanflow.example.test/ops
export BEANFLOW_OPERATIONS_OIDC_SCOPES=openid,profile,email
export BEANFLOW_AUTH_TRUSTED_PROXY_CIDRS=127.0.0.1/32
# ADR-120 permits a transient media outage while ordinary API startup remains available.
# This deliberately exercises that policy; it is not evidence of a working AIStor service.
export BEANFLOW_AISTOR_ENDPOINT=http://127.0.0.1:1
export BEANFLOW_AISTOR_PUBLIC_ENDPOINT=https://media.example.test
export BEANFLOW_AISTOR_BUCKET=beanflow-fixture
export BEANFLOW_AISTOR_REGION=us-east-1

entrypoint_pid=''
cleanup() {
  [[ -z "$entrypoint_pid" ]] || kill -TERM "$entrypoint_pid" 2>/dev/null || true
  [[ -z "$entrypoint_pid" ]] || wait "$entrypoint_pid" 2>/dev/null || true
}
trap cleanup EXIT

# Exercise the real offline bootstrap, including signature verification and its Flyway/Audit transaction.
# These policy values are confined to the disposable fixture and are not deployment defaults.
install -d --owner=beanflow --group=beanflow --mode=0700 /tmp/bootstrap-identity
setpriv --reuid=beanflow --regid=beanflow --init-groups \
  java -cp '/entrypoint-test/classes:/entrypoint-test/lib/*' BootstrapIdentityFixture /tmp/bootstrap-identity
# The earlier entrypoint probe already prepared the private config tree; refresh its changed DB secret.
install --owner=beanflow --group=beanflow --mode=0400 /run/secrets/BEANFLOW_DB_PASSWORD /run/beanflow-secrets/BEANFLOW_DB_PASSWORD
setpriv --reuid=beanflow --regid=beanflow --init-groups \
  env -u SPRING_PROFILES_ACTIVE java \
    -Dloader.main=io.github.kdh949.beanflow.operations.internal.OrdinaryPointAccrualPolicyBootstrapCli \
    -cp /opt/beanflow/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
    --rate-bps=100 --rounding-mode=FLOOR --issuer-type=PLATFORM --issuer-reference=runtime-fixture \
    --expiry-rule=EXACT_DURATION_FROM_COMPLETION --validity-days=365 \
    --reason=isolated-startup-verification --evidence-reference=runtime-fixture --correlation-id=runtime-fixture \
    --token-file=/tmp/bootstrap-identity/token --jwk-set-file=/tmp/bootstrap-identity/jwks.json \
    --issuer=https://release.example.test --audience=beanflow-bootstrap --allowed-subjects=runtime-fixture \
    --deployment-run-claim=run_id > /tmp/bootstrap-result.log 2>&1 || {
      grep 'operation=INITIALIZE' /tmp/bootstrap-result.log >&2 || true
      echo 'Packaged policy bootstrap failed' >&2
      exit 1
    }
grep -Fxq 'operation=INITIALIZE principal=verified-release-principal result=APPLIED' /tmp/bootstrap-result.log
rm /tmp/bootstrap-identity/token /tmp/bootstrap-identity/jwks.json

start_application() {
  bash /usr/local/bin/beanflow-entrypoint > /tmp/full-application.log 2>&1 &
  entrypoint_pid="$!"
  local deadline=$(($(date +%s) + 240))
  while (($(date +%s) < deadline)); do
    kill -0 "$entrypoint_pid" 2>/dev/null || break
    # The HTTP listener alone can be up before ApplicationRunner prechecks finish.
    if grep -q 'ReadinessState.*ACCEPTING_TRAFFIC' /tmp/full-application.log && \
      curl --noproxy '*' --fail --silent --max-time 2 http://127.0.0.1:8080/actuator/health \
        > /tmp/application-health.json; then
      grep -q '"status":"UP"' /tmp/application-health.json
      return
    fi
    sleep 1
  done
  echo 'Full packaged application failed startup' >&2
  # All values belong to this isolated fixture; keep diagnostics focused on startup failures.
  grep -E 'ERROR|Exception|Caused by:|Reason:|^Required |Vault Proxy did not|Description:|^[[:space:]]+at .*beanflow' \
    /tmp/full-application.log >&2 || true
  return 1
}

# A real HA step-down leaves a transient leader gap immediately before Proxy/AppRole startup.
env -u VAULT_CONFIG_PATH VAULT_TOKEN=entrypoint-fixture-root vault operator step-down >/dev/null
start_application
curl --noproxy '*' --fail --silent --max-time 5 http://127.0.0.1:8080/actuator/health > /dev/null
curl --noproxy '*' --fail --silent --max-time 5 \
  http://127.0.0.1:8080/api/v1/auth/operations/config > /tmp/oidc-config.json
grep -q 'https://auth.example.test/realms/beanflow' /tmp/oidc-config.json
# Private session route must remain unauthorized without a session.
[[ "$(curl --noproxy '*' --silent --output /dev/null --max-time 5 --write-out '%{http_code}' \
  http://127.0.0.1:8080/api/v1/me)" == 401 ]]
grep -q 'AIStor startup verification is unavailable; media operations remain isolated' /tmp/full-application.log

# Graceful exit must stop both the JVM and Proxy, and the same credentials/database must boot again.
kill -TERM "$entrypoint_pid"
wait "$entrypoint_pid" || true
entrypoint_pid=''
! pgrep -u beanflow java >/dev/null
! pgrep -u vault-proxy vault >/dev/null
start_application
echo 'Full portfolio application, PostgreSQL/PostGIS migrations, health, OIDC config, unauthenticated 401 and restart passed.'
echo 'External Keycloak login, AIStor access and Toss payment are not exercised by this isolated startup test.'
