#!/usr/bin/env bash
set -euo pipefail

SLOT_ID="${1:-}"
HOLD_SECONDS="${2:-15}"

if [[ ! "$SLOT_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]]; then
  echo "usage: $0 <perf-pickup-slot-uuid> [hold-seconds:1..30]" >&2
  exit 2
fi
if [[ ! "$HOLD_SECONDS" =~ ^[0-9]+$ ]] || (( HOLD_SECONDS < 1 || HOLD_SECONDS > 30 )); then
  echo "hold-seconds must be an integer from 1 through 30" >&2
  exit 2
fi

docker compose -f compose.portfolio.yml -f compose.perf.yml exec -T postgres \
  psql --username beanflow --dbname beanflow_perf --set=ON_ERROR_STOP=1 \
    --set=slot_id="$SLOT_ID" --set=hold_seconds="$HOLD_SECONDS" <<'SQL'
BEGIN;
SET LOCAL statement_timeout = '35s';
SELECT EXISTS (
  SELECT 1 FROM fulfillment_pickup_slot WHERE id = :'slot_id'::uuid
) AS slot_exists \gset
\if :slot_exists
  SELECT id FROM fulfillment_pickup_slot WHERE id = :'slot_id'::uuid FOR UPDATE;
  SELECT pg_sleep(:'hold_seconds'::integer);
  ROLLBACK;
\else
  ROLLBACK;
  \echo 'The allowlisted perf pickup slot does not exist.'
  \quit 3
\endif
SQL
