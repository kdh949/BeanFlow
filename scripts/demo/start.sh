#!/usr/bin/env bash
#
# Brings up the local-demo environment:
#   PostGIS -> ephemeral JWKS endpoint -> explicit policy bootstrap -> application (local,local-demo)
#
# Every wait is bounded. Nothing falls back to a default policy or an unauthenticated mode.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

require_cmd docker curl

cd "$DEMO_ROOT"
mkdir -p "$DEMO_RUNTIME_DIR"
chmod 700 "$DEMO_RUNTIME_DIR"

log "1/5 starting PostgreSQL 17 / PostGIS 3.5"
compose up -d
wait_until 180 "PostgreSQL to accept connections" postgres_ready
ok "database ready on port ${DEMO_DB_PORT} (database ${DEMO_DB_NAME})"

log "2/5 starting the ephemeral identity server"
if [ -f "$DEMO_IDENTITY_PID_FILE" ] && kill -0 "$(cat "$DEMO_IDENTITY_PID_FILE")" 2>/dev/null; then
  ok "identity server already running (pid $(cat "$DEMO_IDENTITY_PID_FILE"))"
else
  ./gradlew --quiet local-demo-identity-server \
    --args="${DEMO_IDENTITY_PORT} ${DEMO_RUNTIME_DIR}" \
    >"$DEMO_IDENTITY_LOG" 2>&1 &
  echo $! >"$DEMO_IDENTITY_PID_FILE"
  wait_until 240 "the JWK set endpoint" jwks_ready
  ok "identity server listening on http://127.0.0.1:${DEMO_IDENTITY_PORT}/jwks.json"
fi

load_identity_env
export_app_env

log "3/5 bootstrapping the required GLOBAL ordinary point accrual policy"
# The demo never invents this policy implicitly. It is created by the same audited CLI production
# would use, verified through the same OIDC workload identity path.
# Arguments go through the CLI's environment-variable contract rather than --args: Gradle splits
# --args on whitespace, which would break any value containing a space.
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_RATE_BPS="100"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ROUNDING_MODE="FLOOR"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER_TYPE="PLATFORM"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER_REFERENCE="local-demo:platform"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_EXPIRY_RULE="EXACT_DURATION_FROM_COMPLETION"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_VALIDITY_DAYS="365"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_REASON="local-demo environment bootstrap"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_EVIDENCE_REFERENCE="local-demo:bootstrap"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_CORRELATION_ID="local-demo-bootstrap-$$"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_TOKEN_FILE="$DEMO_WORKLOAD_TOKEN_FILE"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_JWK_SET_FILE="$DEMO_JWKS_FILE"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ISSUER="$BEANFLOW_DEMO_ISSUER"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_AUDIENCE="$BEANFLOW_DEMO_WORKLOAD_AUDIENCE"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_ALLOWED_SUBJECTS="$BEANFLOW_DEMO_WORKLOAD_SUBJECT"
export BEANFLOW_POINT_ACCRUAL_BOOTSTRAP_DEPLOYMENT_RUN_CLAIM="$BEANFLOW_DEMO_DEPLOYMENT_RUN_CLAIM"

if ./gradlew --quiet ordinary-accrual-policy-bootstrap >"${DEMO_RUNTIME_DIR}/policy-bootstrap.log" 2>&1; then
  ok "ordinary accrual policy bootstrap completed"
elif grep -qiE "ALREADY_INITIALIZED|already" "${DEMO_RUNTIME_DIR}/policy-bootstrap.log" 2>/dev/null; then
  ok "ordinary accrual policy already present; continuing"
else
  tail -30 "${DEMO_RUNTIME_DIR}/policy-bootstrap.log" >&2 || true
  fail "Policy bootstrap failed. The demo does not start without the required policy."
fi

log "4/5 starting the application with profiles local,local-demo"
if [ -f "$DEMO_APP_PID_FILE" ] && kill -0 "$(cat "$DEMO_APP_PID_FILE")" 2>/dev/null; then
  ok "application already running (pid $(cat "$DEMO_APP_PID_FILE"))"
else
  ./gradlew --quiet bootRun >"$DEMO_APP_LOG" 2>&1 &
  echo $! >"$DEMO_APP_PID_FILE"
fi
wait_until 300 "the application health endpoint" app_healthy
ok "application healthy on http://127.0.0.1:${DEMO_APP_PORT}"

log "5/5 environment ready"
cat <<EOF

  database    ${DEMO_DB_URL}
  application ${DEMO_APP_BASE_URL}
  jwk set     ${BEANFLOW_DEMO_JWKS_URI}
  runtime dir ${DEMO_RUNTIME_DIR}  (untracked; holds the run-time key material)

  next: bash scripts/demo/seed.sh && bash scripts/demo/smoke.sh
EOF
