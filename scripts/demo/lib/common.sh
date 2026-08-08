#!/usr/bin/env bash
# Shared settings and helpers for the local-demo scripts.
#
# Nothing here weakens authentication or domain validation. The demo runs the real application with
# the real resource server; only the provider adapters and the key source are local.
set -euo pipefail

DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
export DEMO_ROOT

# The reset guard compares against this exact name. Nothing else may ever be dropped.
export DEMO_DB_NAME="beanflow_demo"
export DEMO_DB_USER="beanflow_demo"
export DEMO_DB_PASSWORD="beanflow_demo_local_only"
export DEMO_DB_PORT="55432"
export DEMO_DB_URL="jdbc:postgresql://127.0.0.1:${DEMO_DB_PORT}/${DEMO_DB_NAME}"

export DEMO_COMPOSE_FILE="${DEMO_ROOT}/docker-compose.demo.yml"
export DEMO_COMPOSE_PROJECT="beanflow-demo"
export DEMO_CONTAINER="beanflow-demo-postgres"

export DEMO_RUNTIME_DIR="${DEMO_ROOT}/.demo-runtime"
export DEMO_IDENTITY_ENV="${DEMO_RUNTIME_DIR}/demo-identity.env"
export DEMO_JWKS_FILE="${DEMO_RUNTIME_DIR}/jwks.json"
export DEMO_WORKLOAD_TOKEN_FILE="${DEMO_RUNTIME_DIR}/workload-token.txt"
export DEMO_IDENTITY_PORT="18081"
export DEMO_APP_PORT="18080"
export DEMO_APP_BASE_URL="http://127.0.0.1:${DEMO_APP_PORT}/api/v1"
export DEMO_IDENTITY_PID_FILE="${DEMO_RUNTIME_DIR}/identity.pid"
export DEMO_APP_PID_FILE="${DEMO_RUNTIME_DIR}/app.pid"
export DEMO_IDENTITY_LOG="${DEMO_RUNTIME_DIR}/identity.log"
export DEMO_APP_LOG="${DEMO_RUNTIME_DIR}/app.log"

log()  { printf '\033[0;36m[demo]\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m[ ok ]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[warn]\033[0m %s\n' "$*"; }
fail() { printf '\033[0;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

require_cmd() {
  for cmd in "$@"; do
    command -v "$cmd" >/dev/null 2>&1 || fail "'$cmd' is required but not on PATH."
  done
}

# Bounded poll. Never an unbounded sleep: exceeding the deadline is a failure, not a slow success.
#   wait_until <seconds> <description> <command...>
wait_until() {
  local deadline_seconds="$1"; shift
  local description="$1"; shift
  local deadline=$(( SECONDS + deadline_seconds ))
  while (( SECONDS < deadline )); do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "Timed out after ${deadline_seconds}s waiting for ${description}."
}

load_identity_env() {
  [ -f "$DEMO_IDENTITY_ENV" ] || fail "Missing ${DEMO_IDENTITY_ENV}. Run scripts/demo/start.sh first."
  set -a
  # shellcheck disable=SC1090
  . "$DEMO_IDENTITY_ENV"
  set +a
}

compose() {
  docker compose -p "$DEMO_COMPOSE_PROJECT" -f "$DEMO_COMPOSE_FILE" "$@"
}

postgres_ready() {
  docker exec "$DEMO_CONTAINER" pg_isready -U "$DEMO_DB_USER" -d "$DEMO_DB_NAME"
}

app_healthy() {
  curl -fsS -m 3 "http://127.0.0.1:${DEMO_APP_PORT}/actuator/health" | grep -q '"status":"UP"'
}

jwks_ready() {
  curl -fsS -m 3 "http://127.0.0.1:${DEMO_IDENTITY_PORT}/jwks.json" | grep -q '"keys"'
}

# Exports the environment the application and the CLIs need. Requires load_identity_env first.
export_app_env() {
  export BEANFLOW_DB_URL="$DEMO_DB_URL"
  export BEANFLOW_DB_USERNAME="$DEMO_DB_USER"
  export BEANFLOW_DB_PASSWORD="$DEMO_DB_PASSWORD"
  export BEANFLOW_JWK_SET_URI="$BEANFLOW_DEMO_JWKS_URI"
  export SPRING_PROFILES_ACTIVE="local,local-demo"
  export SERVER_PORT="$DEMO_APP_PORT"
  # Relaxed env-var binding strips the hyphen (cursor-hmac -> CURSORHMAC) and indexed list
  # properties are easy to get wrong, so the key ring is passed as explicit JSON instead.
  # The datasource is passed explicitly too: the seed CLI is a separate Spring application and
  # must not depend on placeholder resolution from the main application.yaml.
  export SPRING_APPLICATION_JSON="{\"spring\":{\"datasource\":{\"url\":\"${DEMO_DB_URL}\",\"username\":\"${DEMO_DB_USER}\",\"password\":\"${DEMO_DB_PASSWORD}\",\"driver-class-name\":\"org.postgresql.Driver\"}},\"beanflow\":{\"pagination\":{\"cursor-hmac\":{\"active-key-id\":\"local-demo\",\"keys\":[{\"id\":\"local-demo\",\"secret-base64-url\":\"${BEANFLOW_DEMO_CURSOR_SECRET}\"}]}}}}"
}
