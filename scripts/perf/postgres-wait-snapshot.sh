#!/usr/bin/env bash
set -euo pipefail

docker compose -f compose.portfolio.yml -f compose.perf.yml exec -T postgres \
  psql --username beanflow --dbname beanflow_perf --set=ON_ERROR_STOP=1 <<'SQL'
\pset pager off
SELECT
  COALESCE(wait_event_type, 'none') AS wait_event_type,
  COALESCE(wait_event, 'none') AS wait_event,
  state,
  count(*) AS sessions
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
GROUP BY wait_event_type, wait_event, state
ORDER BY sessions DESC, wait_event_type, wait_event, state;

SELECT locktype, mode, granted, count(*) AS locks
FROM pg_locks
WHERE database = (SELECT oid FROM pg_database WHERE datname = current_database())
GROUP BY locktype, mode, granted
ORDER BY granted, locks DESC, locktype, mode;

SELECT queryid, calls, round(mean_exec_time::numeric, 3) AS mean_ms,
       round(max_exec_time::numeric, 3) AS max_ms, rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;
SQL
