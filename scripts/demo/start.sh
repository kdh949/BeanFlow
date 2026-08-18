#!/usr/bin/env bash
#
# Brings up the local-demo environment:
#   PostGIS -> ephemeral JWKS endpoint -> explicit policy bootstrap -> application -> React frontend
#
# Every wait is bounded. Nothing falls back to a default policy or an unauthenticated mode.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

require_cmd docker curl python3 lsof npm ps

cd "$DEMO_ROOT"
mkdir -p "$DEMO_RUNTIME_DIR"
chmod 700 "$DEMO_RUNTIME_DIR"

port_is_busy() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

assert_port_is_safe_to_start() {
  local port="$1" label="$2" record="${3:-}"
  if ! port_is_busy "$port"; then return 0; fi
  if [ -n "$record" ] && owned_process_record_is_live "$record" "$label"; then return 0; fi
  fail "Port ${port} for ${label} is already in use by an unowned process; refusing to share a checkout-local demo port."
}

# Check the database port too, so a collision cannot leave a partially-created compose stack. This
# also prevents starting the rest of the stack after an unowned process claims a derived port.
assert_port_is_safe_to_start "$DEMO_DB_PORT" "database"
assert_port_is_safe_to_start "$DEMO_IDENTITY_PORT" "identity server" "$DEMO_IDENTITY_PID_FILE"
assert_port_is_safe_to_start "$DEMO_APP_PORT" "application" "$DEMO_APP_PID_FILE"
assert_port_is_safe_to_start "$DEMO_FRONTEND_PORT" "frontend" "$DEMO_FRONTEND_PID_FILE"

log "1/6 starting checkout-isolated PostgreSQL 17 / PostGIS 3.5"
compose up -d
wait_until 180 "PostgreSQL to accept connections" postgres_ready
ok "database ready in compose project ${DEMO_COMPOSE_PROJECT}"

log "2/6 starting the ephemeral identity server"
if owned_process_record_is_live "$DEMO_IDENTITY_PID_FILE" "identity server"; then
  ok "identity server already running in its owned process group"
else
  [ ! -f "$DEMO_IDENTITY_PID_FILE" ] || warn "discarding unverified identity-server process record without signalling it"
  rm -f "$DEMO_IDENTITY_PID_FILE"
  start_owned_gradle "$DEMO_IDENTITY_PID_FILE" "identity server" "$DEMO_IDENTITY_LOG" \
    local-demo-identity-server --args="${DEMO_IDENTITY_PORT} ${DEMO_RUNTIME_DIR}"
  wait_until 240 "the JWK set endpoint" jwks_ready
  ok "identity server listening on http://127.0.0.1:${DEMO_IDENTITY_PORT}/jwks.json"
fi

load_identity_env
export_app_env

log "3/6 bootstrapping the required GLOBAL ordinary point accrual policy"
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

set +e
./gradlew --quiet ordinary-accrual-policy-bootstrap >"${DEMO_RUNTIME_DIR}/policy-bootstrap.log" 2>&1
bootstrap_exit=$?
set -e
if [ "$bootstrap_exit" -eq 0 ] && grep -Fxq \
  "operation=INITIALIZE principal=verified-release-principal result=APPLIED" \
  "${DEMO_RUNTIME_DIR}/policy-bootstrap.log"; then
  ok "ordinary accrual policy bootstrap completed"
elif [ "$bootstrap_exit" -eq 4 ] && grep -Fxq \
  "operation=INITIALIZE principal=verified-release-principal result=POLICY_ALREADY_INITIALIZED" \
  "${DEMO_RUNTIME_DIR}/policy-bootstrap.log"; then
  fail "The GLOBAL accrual policy is already initialized. Run scripts/demo/stop.sh --reset before a deterministic demo run."
else
  tail -30 "${DEMO_RUNTIME_DIR}/policy-bootstrap.log" >&2 || true
  fail "Policy bootstrap failed. The demo does not start without the required policy."
fi

log "4/6 starting the application with profiles local,local-demo"
if owned_process_record_is_live "$DEMO_APP_PID_FILE" "application"; then
  ok "application already running in its owned process group"
else
  [ ! -f "$DEMO_APP_PID_FILE" ] || warn "discarding unverified application process record without signalling it"
  rm -f "$DEMO_APP_PID_FILE"
  start_owned_gradle "$DEMO_APP_PID_FILE" "application" "$DEMO_APP_LOG" bootRun
fi
wait_until 300 "the application health endpoint" app_healthy
ok "application healthy on http://127.0.0.1:${DEMO_APP_PORT}"

log "5/6 starting the React frontend"
if owned_process_record_is_live "$DEMO_FRONTEND_PID_FILE" "frontend"; then
  ok "frontend already running in its owned process group"
else
  [ ! -f "$DEMO_FRONTEND_PID_FILE" ] || warn "discarding unverified frontend process record without signalling it"
  rm -f "$DEMO_FRONTEND_PID_FILE"
  start_owned_frontend "$DEMO_FRONTEND_PID_FILE" "frontend" "$DEMO_FRONTEND_LOG"
fi
wait_until 120 "the React frontend" frontend_healthy
ok "frontend ready on ${DEMO_FRONTEND_BASE_URL}"

log "6/6 environment ready"
cat <<EOF

  application ${DEMO_APP_BASE_URL}
  frontend    ${DEMO_FRONTEND_BASE_URL}/app
  aliases     demo.customer / demo.merchant / demo.othermerchant
  resources   checkout-isolated compose project and runtime directory (untracked key material)

  next: bash scripts/demo/seed.sh && bash scripts/demo/smoke.sh
EOF
