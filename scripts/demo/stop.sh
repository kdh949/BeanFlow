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

require_cmd docker lsof ps

RESET="no"
for argument in "$@"; do
  case "$argument" in
    --reset) RESET="yes" ;;
    *) fail "Unknown argument: $argument (supported: --reset)" ;;
  esac
done

stop_owned_process() {
  local record="$1" name="$2"
  [ -f "$record" ] || { log "$name was not running"; return 0; }
  if ! owned_process_record_is_live "$record" "$name"; then
    warn "refusing to signal unverified ${name} process record; removing only the stale record"
    rm -f "$record"
    return 0
  fi

  local pid="$OWNED_PROCESS_PID" pgid="$OWNED_PROCESS_PGID" deadline=$((SECONDS + 10))
  kill -TERM -- "-${pgid}" || fail "Could not stop owned ${name} process group ${pgid}."
  while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do
    sleep 0.2
  done
  if kill -0 "$pid" 2>/dev/null; then
    # Re-check immediately before escalation; never signal a reused PID or an altered record.
    owned_process_record_is_live "$record" "$name" || fail \
      "Refusing to force-stop ${name}: its process ownership could no longer be verified."
    kill -KILL -- "-${pgid}" || fail "Could not force-stop owned ${name} process group ${pgid}."
  fi
  rm -f "$record"
  ok "stopped owned ${name} process group ${pgid}"
}

inspect_demo_container_state() {
  local output status
  if output="$(docker inspect "$DEMO_CONTAINER" 2>&1)"; then
    DEMO_CONTAINER_STATE="present"
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 1 ] && [ "$output" = "Error: No such object: ${DEMO_CONTAINER}" ]; then
    DEMO_CONTAINER_STATE="absent"
    return 0
  fi
  printf '%s\n' "$output" >&2
  return "$status"
}

cd "$DEMO_ROOT"
stop_owned_process "$DEMO_APP_PID_FILE" "application"
stop_owned_process "$DEMO_IDENTITY_PID_FILE" "identity server"

if [ "$RESET" != "yes" ]; then
  compose stop || fail "Demo database stop failed; run-time key material was retained."
  ok "database container stopped. Run with --reset to delete demo data and key material."
  exit 0
fi

# --- reset guard -------------------------------------------------------------------------------
# Refuse unless the running container is exactly the demo container serving exactly the demo
# database. Anything else means we are pointed at the wrong environment and must not delete.
inspect_demo_container_state || fail "Reset refused: could not inspect ${DEMO_CONTAINER}; Docker state is unknown."
if [ "$DEMO_CONTAINER_STATE" = "present" ]; then
  actual_database="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$DEMO_CONTAINER" \
    | grep '^POSTGRES_DB=' | cut -d= -f2 | tr -d '\r')" || \
    fail "Reset refused: could not read the demo container database identity."
  [ "$actual_database" = "$DEMO_DB_NAME" ] || fail \
    "Reset refused: container ${DEMO_CONTAINER} serves database '${actual_database}', not '${DEMO_DB_NAME}'."
  ok "reset guard passed: ${DEMO_CONTAINER} serves ${DEMO_DB_NAME}"
else
  log "demo container is not present; nothing to remove"
fi

compose down --volumes --remove-orphans || fail "Demo database removal failed; run-time key material was retained."
inspect_demo_container_state || fail "Demo database removal failed: could not verify ${DEMO_CONTAINER} is absent."
[ "$DEMO_CONTAINER_STATE" = "absent" ] || fail "Demo database removal failed: ${DEMO_CONTAINER} is still present."
ok "demo database removed"

if [ -d "$DEMO_RUNTIME_DIR" ]; then
  # Confined to the demo runtime directory inside this repository.
  case "$DEMO_RUNTIME_DIR" in
    "${DEMO_ROOT}/.demo-runtime") rm -rf "$DEMO_RUNTIME_DIR"; ok "run-time key material deleted" ;;
    *) fail "Reset refused: unexpected runtime directory ${DEMO_RUNTIME_DIR}" ;;
  esac
fi
