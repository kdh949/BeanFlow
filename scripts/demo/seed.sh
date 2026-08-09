#!/usr/bin/env bash
#
# Writes the deterministic demo fixture. Re-running is idempotent: fixed identifiers mean the second
# run inserts nothing and reports the same fixture. A partial failure rolls the whole seed back.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

require_cmd docker
cd "$DEMO_ROOT"

postgres_ready >/dev/null 2>&1 || fail "The demo database is not running. Run scripts/demo/start.sh first."
load_identity_env
export_app_env

log "seeding the local-demo fixture"
if ./gradlew --no-daemon --quiet local-demo-seed >"${DEMO_RUNTIME_DIR}/seed.log" 2>&1; then
  grep -E '^LOCAL_DEMO_SEED_' "${DEMO_RUNTIME_DIR}/seed.log" || true
  inserted="$(grep -Eo 'inserted=[0-9]+' "${DEMO_RUNTIME_DIR}/seed.log" | head -1 | cut -d= -f2)"
  ok "seed completed (rows inserted this run: ${inserted:-unknown})"
else
  tail -40 "${DEMO_RUNTIME_DIR}/seed.log" >&2 || true
  fail "Seed failed. The transaction rolled back; no partial fixture remains."
fi
