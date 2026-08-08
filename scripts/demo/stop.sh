#!/usr/bin/env bash
#
# Stops the local-demo environment. With --reset it also removes the demo database container and
# the run-time key material.
#
# The reset guard only ever acts on the container whose name and database match the demo values
# exactly. It never issues a DROP against an arbitrary database and never touches another project's
# compose stack.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

RESET="no"
for argument in "$@"; do
  case "$argument" in
    --reset) RESET="yes" ;;
    *) fail "Unknown argument: $argument (supported: --reset)" ;;
  esac
done

stop_pid_file() {
  local pid_file="$1" name="$2"
  [ -f "$pid_file" ] || { log "$name was not running"; return 0; }
  local pid; pid="$(cat "$pid_file")"
  if kill -0 "$pid" 2>/dev/null; then
    # The Gradle wrapper spawns the real JVM, so stop the process group.
    pkill -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
    ok "stopped $name (pid $pid)"
  else
    log "$name was not running"
  fi
  rm -f "$pid_file"
}

cd "$DEMO_ROOT"
stop_pid_file "$DEMO_APP_PID_FILE" "application"
stop_pid_file "$DEMO_IDENTITY_PID_FILE" "identity server"
# bootRun and the identity server are Gradle daemons' children; make sure the JVMs are gone.
pkill -f "io.github.kdh949.beanflow.demo.LocalDemoIdentityServerKt" 2>/dev/null || true
pkill -f "beanflow.*bootRun" 2>/dev/null || true

if [ "$RESET" != "yes" ]; then
  compose stop >/dev/null 2>&1 || true
  ok "database container stopped. Run with --reset to delete demo data and key material."
  exit 0
fi

# --- reset guard -------------------------------------------------------------------------------
# Refuse unless the running container is exactly the demo container serving exactly the demo
# database. Anything else means we are pointed at the wrong environment and must not delete.
if docker inspect "$DEMO_CONTAINER" >/dev/null 2>&1; then
  actual_database="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$DEMO_CONTAINER" \
    | grep '^POSTGRES_DB=' | cut -d= -f2 | tr -d '\r')"
  [ "$actual_database" = "$DEMO_DB_NAME" ] || fail \
    "Reset refused: container ${DEMO_CONTAINER} serves database '${actual_database}', not '${DEMO_DB_NAME}'."
  ok "reset guard passed: ${DEMO_CONTAINER} serves ${DEMO_DB_NAME}"
else
  log "demo container is not present; nothing to remove"
fi

compose down --volumes --remove-orphans >/dev/null 2>&1 || true
ok "demo database removed"

if [ -d "$DEMO_RUNTIME_DIR" ]; then
  # Confined to the demo runtime directory inside this repository.
  case "$DEMO_RUNTIME_DIR" in
    "${DEMO_ROOT}/.demo-runtime") rm -rf "$DEMO_RUNTIME_DIR"; ok "run-time key material deleted" ;;
    *) fail "Reset refused: unexpected runtime directory ${DEMO_RUNTIME_DIR}" ;;
  esac
fi
