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
if ./gradlew --quiet ordinary-accrual-policy-bootstrap --args="\
--rate-bps=100 \
--rounding-mode=FLOOR \
--issuer-type=PLATFORM \
--issuer-reference=local-demo:platform \
--expiry-rule=EXACT_DURATION_FROM_COMPLETION \
--validity-days=365 \
--reason=local-demo environment bootstrap \
--evidence-reference=local-demo:bootstrap \
--correlation-id=local-demo-bootstrap \
--token-file=${DEMO_WORKLOAD_TOKEN_FILE} \
--jwk-set-file=${DEMO_JWKS_FILE} \
--issuer=${BEANFLOW_DEMO_ISSUER} \
--audience=${BEANFLOW_DEMO_WORKLOAD_AUDIENCE} \
--allowed-subjects=${BEANFLOW_DEMO_WORKLOAD_SUBJECT} \
--deployment-run-claim=${BEANFLOW_DEMO_DEPLOYMENT_RUN_CLAIM}" >"${DEMO_RUNTIME_DIR}/policy-bootstrap.log" 2>&1; then
  ok "ordinary accrual policy bootstrap completed"
else
  tail -30 "${DEMO_RUNTIME_DIR}/policy-bootstrap.log" >&2 || true
  if grep -qi "already" "${DEMO_RUNTIME_DIR}/policy-bootstrap.log" 2>/dev/null; then
    ok "ordinary accrual policy already present; continuing"
  else
    fail "Policy bootstrap failed. The demo does not start without the required policy."
  fi
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
