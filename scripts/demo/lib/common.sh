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
# A checkout owns an isolated compose project, container and port block.  The stable key is derived
# from its canonical path, never supplied by a user, so a second checkout cannot reset this one.
DEMO_INSTANCE_CHECKSUM="$(printf '%s' "$DEMO_ROOT" | cksum | awk '{print $1}')"
export DEMO_INSTANCE_KEY="${DEMO_INSTANCE_CHECKSUM}"
DEMO_PORT_OFFSET=$(( DEMO_INSTANCE_CHECKSUM % 1000 ))
export DEMO_DB_PORT="$(( 55000 + DEMO_PORT_OFFSET ))"
export DEMO_DB_URL="jdbc:postgresql://127.0.0.1:${DEMO_DB_PORT}/${DEMO_DB_NAME}"

export DEMO_COMPOSE_FILE="${DEMO_ROOT}/docker-compose.demo.yml"
export DEMO_COMPOSE_PROJECT="beanflow-demo-${DEMO_INSTANCE_KEY}"
export DEMO_CONTAINER="${DEMO_COMPOSE_PROJECT}-postgres"

export DEMO_RUNTIME_DIR="${DEMO_ROOT}/.demo-runtime"
export DEMO_IDENTITY_ENV="${DEMO_RUNTIME_DIR}/demo-identity.env"
export DEMO_JWKS_FILE="${DEMO_RUNTIME_DIR}/jwks.json"
export DEMO_WORKLOAD_TOKEN_FILE="${DEMO_RUNTIME_DIR}/workload-token.txt"
export DEMO_IDENTITY_PORT="$(( 18000 + DEMO_PORT_OFFSET ))"
export DEMO_APP_PORT="$(( 19000 + DEMO_PORT_OFFSET ))"
export DEMO_APP_BASE_URL="http://127.0.0.1:${DEMO_APP_PORT}/api/v1"
export DEMO_FRONTEND_PORT="$(( 4000 + DEMO_PORT_OFFSET ))"
export DEMO_FRONTEND_BASE_URL="http://127.0.0.1:${DEMO_FRONTEND_PORT}"
export DEMO_IDENTITY_PID_FILE="${DEMO_RUNTIME_DIR}/identity.pid"
export DEMO_APP_PID_FILE="${DEMO_RUNTIME_DIR}/app.pid"
export DEMO_FRONTEND_PID_FILE="${DEMO_RUNTIME_DIR}/frontend.pid"
export DEMO_IDENTITY_LOG="${DEMO_RUNTIME_DIR}/identity.log"
export DEMO_APP_LOG="${DEMO_RUNTIME_DIR}/app.log"
export DEMO_FRONTEND_LOG="${DEMO_RUNTIME_DIR}/frontend.log"

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

# The Gradle wrapper starts JVM children. A raw PID is not safe enough to own that process tree:
# PID reuse could otherwise signal another checkout's process. Each demo process therefore becomes
# the leader of a dedicated session/process group and records a nonce which remains in the Gradle
# command line. Stop verifies PID, group, cwd and nonce before signalling that group.
read_owned_process_record() {
  local record="$1" expected_kind="$2"
  OWNED_PROCESS_PID=""
  OWNED_PROCESS_PGID=""
  OWNED_PROCESS_NONCE=""
  OWNED_PROCESS_KIND=""
  OWNED_PROCESS_ROOT=""
  [ -f "$record" ] || return 1

  local key value
  while IFS='=' read -r key value; do
    case "$key" in
      pid) OWNED_PROCESS_PID="$value" ;;
      pgid) OWNED_PROCESS_PGID="$value" ;;
      nonce) OWNED_PROCESS_NONCE="$value" ;;
      kind) OWNED_PROCESS_KIND="$value" ;;
      root) OWNED_PROCESS_ROOT="$value" ;;
      *) return 1 ;;
    esac
  done < "$record"

  [[ "$OWNED_PROCESS_PID" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$OWNED_PROCESS_PGID" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$OWNED_PROCESS_NONCE" =~ ^[a-f0-9]{32}$ ]] || return 1
  [ "$OWNED_PROCESS_KIND" = "$expected_kind" ] || return 1
  [ "$OWNED_PROCESS_ROOT" = "$DEMO_ROOT" ] || return 1
}

owned_process_record_is_live() {
  local record="$1" expected_kind="$2"
  read_owned_process_record "$record" "$expected_kind" || return 1
  kill -0 "$OWNED_PROCESS_PID" 2>/dev/null || return 1

  local actual_pgid command cwd
  actual_pgid="$(ps -o pgid= -p "$OWNED_PROCESS_PID" 2>/dev/null | tr -d '[:space:]')"
  [ "$actual_pgid" = "$OWNED_PROCESS_PGID" ] || return 1
  # `start_owned_gradle` makes the leader's PID and PGID identical with setsid(2).
  [ "$OWNED_PROCESS_PGID" = "$OWNED_PROCESS_PID" ] || return 1
  command="$(ps eww -o command= -p "$OWNED_PROCESS_PID" 2>/dev/null)"
  if [[ "$command" != *"beanflowLocalDemoNonce=${OWNED_PROCESS_NONCE}"* ]] &&
    [[ "$command" != *"DEMO_PROCESS_NONCE=${OWNED_PROCESS_NONCE}"* ]] &&
    [[ "$command" != *"beanflow-local-demo-${OWNED_PROCESS_NONCE}"* ]]; then
    return 1
  fi
  cwd="$(lsof -a -p "$OWNED_PROCESS_PID" -d cwd -Fn 2>/dev/null | awk '/^n/{sub(/^n/, ""); print; exit}')"
  [ "$cwd" = "$DEMO_ROOT" ] || return 1
}

start_owned_gradle() {
  local record="$1" name="$2" log_file="$3"
  shift 3
  local nonce launcher_pid pid pgid ready_file
  nonce="$(python3 -c 'import secrets; print(secrets.token_hex(16))')" || fail "Could not create process ownership nonce for ${name}."
  ready_file="${record}.starting"
  rm -f "$ready_file"
  # The shell can place a background environment-assignment wrapper in the caller's process
  # group. The Python child therefore writes its post-setsid PID before exec; `$!` is not treated
  # as proof of ownership.
  python3 -c \
    'import os, sys; nonce, ready, command = sys.argv[1:4]; os.environ["DEMO_PROCESS_NONCE"] = nonce; os.setsid(); open(ready, "w", encoding="ascii").write(str(os.getpid())); os.execvp(command, [command, *sys.argv[4:]])' \
    "$nonce" "$ready_file" ./gradlew "-PbeanflowLocalDemoNonce=${nonce}" --quiet "$@" >"$log_file" 2>&1 &
  launcher_pid=$!

  # A process may take a moment to create the ownership record immediately after the shell
  # backgrounds its launcher.
  local attempts=0
  while [ "$attempts" -lt 20 ]; do
    [ -f "$ready_file" ] && break
    kill -0 "$launcher_pid" 2>/dev/null || break
    attempts=$((attempts + 1))
    sleep 0.1
  done
  pid="$(cat "$ready_file" 2>/dev/null || true)"
  rm -f "$ready_file"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || fail "Could not establish an owned process leader for ${name}."
  pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
  [ "$pgid" = "$pid" ] || fail \
    "Could not establish an owned process group for ${name} (pid=${pid}, observed-pgid=${pgid:-none})."

  (
    umask 077
    {
      printf 'pid=%s\n' "$pid"
      printf 'pgid=%s\n' "$pgid"
      printf 'nonce=%s\n' "$nonce"
      printf 'kind=%s\n' "$name"
      printf 'root=%s\n' "$DEMO_ROOT"
    } > "$record"
  )
}

start_owned_frontend() {
  local record="$1" name="$2" log_file="$3"
  local nonce launcher_pid pid pgid ready_file
  nonce="$(python3 -c 'import secrets; print(secrets.token_hex(16))')" || fail "Could not create process ownership nonce for ${name}."
  ready_file="${record}.starting"
  rm -f "$ready_file"
  BEANFLOW_API_ORIGIN="http://127.0.0.1:${DEMO_APP_PORT}" \
    python3 -c \
      'import os, sys; nonce, ready, command = sys.argv[1:4]; os.environ["DEMO_PROCESS_NONCE"] = nonce; os.setsid(); open(ready, "w", encoding="ascii").write(str(os.getpid())); os.execvp(command, [command, *sys.argv[4:]])' \
      "$nonce" "$ready_file" npm --prefix "${DEMO_ROOT}/frontend" run dev -- \
      --host 127.0.0.1 --port "$DEMO_FRONTEND_PORT" --strictPort \
      --mode "beanflow-local-demo-${nonce}" >"$log_file" 2>&1 &
  launcher_pid=$!

  local attempts=0
  while [ "$attempts" -lt 20 ]; do
    [ -f "$ready_file" ] && break
    kill -0 "$launcher_pid" 2>/dev/null || break
    attempts=$((attempts + 1))
    sleep 0.1
  done
  pid="$(cat "$ready_file" 2>/dev/null || true)"
  rm -f "$ready_file"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || fail "Could not establish an owned process leader for ${name}."
  pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
  [ "$pgid" = "$pid" ] || fail \
    "Could not establish an owned process group for ${name} (pid=${pid}, observed-pgid=${pgid:-none})."

  (
    umask 077
    {
      printf 'pid=%s\n' "$pid"
      printf 'pgid=%s\n' "$pgid"
      printf 'nonce=%s\n' "$nonce"
      printf 'kind=%s\n' "$name"
      printf 'root=%s\n' "$DEMO_ROOT"
    } > "$record"
  )
}

postgres_ready() {
  docker exec "$DEMO_CONTAINER" pg_isready -U "$DEMO_DB_USER" -d "$DEMO_DB_NAME"
}

app_healthy() {
  curl -fsS -m 3 "http://127.0.0.1:${DEMO_APP_PORT}/actuator/health" | grep -q '"status":"UP"'
}

frontend_healthy() {
  curl -fsS -m 3 "${DEMO_FRONTEND_BASE_URL}/app" | grep -q '<div id="root"></div>'
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
  export SPRING_APPLICATION_JSON="{\"spring\":{\"datasource\":{\"url\":\"${DEMO_DB_URL}\",\"username\":\"${DEMO_DB_USER}\",\"password\":\"${DEMO_DB_PASSWORD}\",\"driver-class-name\":\"org.postgresql.Driver\"}},\"beanflow\":{\"checkout\":{\"frontend-base-url\":\"${DEMO_FRONTEND_BASE_URL}\"},\"pagination\":{\"cursor-hmac\":{\"active-key-id\":\"local-demo\",\"keys\":[{\"id\":\"local-demo\",\"secret-base64-url\":\"${BEANFLOW_DEMO_CURSOR_SECRET}\"}]}}}}"
}
