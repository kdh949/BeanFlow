#!/usr/bin/env bash
set -euo pipefail
trap 'echo "PostgreSQL exporter smoke failed at line $LINENO." >&2' ERR

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="beanflow-exporter-smoke-$$"
EXPORTER_IMAGE=quay.io/prometheuscommunity/postgres-exporter:v0.20.1
POSTGRES_IMAGE=postgis/postgis:17-3.5
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/beanflow-exporter-smoke.XXXXXX")"
cleanup() {
  docker rm -fv "$FIXTURE-exporter" "$FIXTURE-postgres" >/dev/null 2>&1 || true
  docker network rm "$FIXTURE-internal" "$FIXTURE-egress" >/dev/null 2>&1 || true
  docker volume rm "$FIXTURE-secrets" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

docker volume create "$FIXTURE-secrets" >/dev/null
docker run --rm --user 0:0 --entrypoint /bin/sh \
  -v "$FIXTURE-secrets:/fixture" "$EXPORTER_IMAGE" -ec '
    printf "exporter-runtime-fixture-only\n" >/fixture/BEANFLOW_POSTGRES_PASSWORD
    touch /fixture/empty
    chmod 0600 /fixture/BEANFLOW_POSTGRES_PASSWORD /fixture/empty
  '

runtime=(docker run --rm --user 0:0 --read-only --security-opt no-new-privileges:true
  --entrypoint /bin/sh
  --tmpfs /run/beanflow-postgres-exporter:rw,noexec,nosuid,nodev,size=1m,uid=65534,gid=65534,mode=0700
  -v "$REPOSITORY_ROOT/scripts/perf/postgres-exporter-entrypoint.sh:/entrypoint.sh:ro"
  -e DATA_SOURCE_PASS_FILE=/run/beanflow-postgres-exporter/password)
for case_name in missing empty; do
  case_mount=()
  if [[ "$case_name" == empty ]]; then
    docker run --rm --user 0:0 --entrypoint /bin/sh -v "$FIXTURE-secrets:/fixture" "$EXPORTER_IMAGE" \
      -ec 'mkdir -p /fixture/empty-secret; touch /fixture/empty-secret/BEANFLOW_POSTGRES_PASSWORD; chmod 0600 /fixture/empty-secret/BEANFLOW_POSTGRES_PASSWORD'
    case_mount=(--mount "type=volume,source=$FIXTURE-secrets,target=/run/secrets,volume-subpath=empty-secret,readonly")
  fi
  if "${runtime[@]}" "${case_mount[@]}" "$EXPORTER_IMAGE" /entrypoint.sh --version >"$WORK_DIR/$case_name.log" 2>&1; then
    echo "Exporter incorrectly accepted $case_name password." >&2
    exit 1
  fi
  grep -q 'requires a readable, non-empty DB password file' "$WORK_DIR/$case_name.log"
done

docker network create --internal "$FIXTURE-internal" >/dev/null
docker network create "$FIXTURE-egress" >/dev/null
docker run -d --name "$FIXTURE-postgres" --network "$FIXTURE-internal" --network-alias postgres \
  --label "com.docker.compose.project=$FIXTURE" --label com.docker.compose.service=postgres \
  -v "$FIXTURE-secrets:/run/secrets:ro" \
  -e POSTGRES_PASSWORD_FILE=/run/secrets/BEANFLOW_POSTGRES_PASSWORD \
  -e POSTGRES_USER=beanflow -e POSTGRES_DB=beanflow_perf \
  "$POSTGRES_IMAGE" -c shared_preload_libraries=pg_stat_statements >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$FIXTURE-postgres" pg_isready -h 127.0.0.1 -U beanflow -d beanflow_perf >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$FIXTURE-postgres" psql -v ON_ERROR_STOP=1 -U beanflow -d beanflow_perf \
  -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
      CREATE TABLE lock_fixture (id integer PRIMARY KEY, value integer NOT NULL);
      INSERT INTO lock_fixture VALUES (1, 0);
      CREATE TABLE operations_reprocessing_case (case_type text, owner_reference text, status text);
      CREATE TABLE event_publication (id uuid PRIMARY KEY, listener_id text, publication_date timestamptz, completion_date timestamptz, status text);
      CREATE TABLE payment_reconciliation (status text, next_attempt_at timestamptz);
      CREATE TABLE ordering_order (state text, reservation_expires_at timestamptz);
      CREATE TABLE ordering_acceptance_timeout_work (state text, next_attempt_at timestamptz);
      CREATE TABLE payment_refund (reason text, state text, next_attempt_at timestamptz);
      CREATE TABLE payment_refund_restoration_work (state text, next_attempt_at timestamptz);
      CREATE TABLE payment_refund_point_recovery_work (state text, next_attempt_at timestamptz);
      CREATE TABLE notification_delivery (state text, next_attempt_at timestamptz);
      SELECT count(*) FROM lock_fixture;" >/dev/null
docker exec "$FIXTURE-postgres" createdb -U beanflow other_database
docker exec "$FIXTURE-postgres" psql -v ON_ERROR_STOP=1 -U beanflow -d other_database \
  -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements; CREATE TABLE lock_fixture (id integer PRIMARY KEY, value integer NOT NULL); INSERT INTO lock_fixture VALUES (1, 0);' >/dev/null

docker create --name "$FIXTURE-exporter" --user 0:0 --read-only \
  --security-opt no-new-privileges:true --entrypoint /bin/sh \
  --network "$FIXTURE-internal" -p 127.0.0.1::9187 \
  --tmpfs /run/beanflow-postgres-exporter:rw,noexec,nosuid,nodev,size=1m,uid=65534,gid=65534,mode=0700 \
  -v "$FIXTURE-secrets:/run/secrets:ro" \
  -v "$REPOSITORY_ROOT/scripts/perf/postgres-exporter-entrypoint.sh:/entrypoint.sh:ro" \
  -v "$REPOSITORY_ROOT/infra/observability/postgres-queries.yaml:/queries.yaml:ro" \
  -e DATA_SOURCE_PASS_FILE=/run/beanflow-postgres-exporter/password \
  -e DATA_SOURCE_URI=postgres:5432/beanflow_perf?sslmode=disable -e DATA_SOURCE_USER=beanflow \
  "$EXPORTER_IMAGE" /entrypoint.sh --collector.stat_statements --extend.query-path=/queries.yaml \
  --log.format=json --web.listen-address=:9187 >/dev/null
docker network connect "$FIXTURE-egress" "$FIXTURE-exporter"
docker start "$FIXTURE-exporter" >/dev/null
port="$(docker port "$FIXTURE-exporter" 9187/tcp | cut -d: -f2)"
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 5 "http://127.0.0.1:$port/metrics" -o "$WORK_DIR/metrics" 2>/dev/null && \
    grep -qx 'pg_up 1' "$WORK_DIR/metrics"; then break; fi
  sleep 1
done
grep -qx 'pg_up 1' "$WORK_DIR/metrics"
grep -qx 'pg_exporter_last_scrape_error 0' "$WORK_DIR/metrics"
grep -Eq '^beanflow_pg_ungranted_locks(\{| )' "$WORK_DIR/metrics"
for owner in event_publication payment_reconciliation reservation_expiry acceptance_timeout \
  rejection_refund partial_refund_provider partial_refund_restoration refund_point_recovery notification; do
  grep -Eq "^beanflow_worker_backlog_items\\{[^}]*owner=\"$owner\"[^}]*state=\"due\"[^}]*\\} 0$|^beanflow_worker_backlog_items\\{[^}]*state=\"due\"[^}]*owner=\"$owner\"[^}]*\\} 0$" \
    "$WORK_DIR/metrics"
done
if ! grep -Eq '^beanflow_pg_database_identity_info\{[^}]*database="beanflow_perf"[^}]*\} 1$' "$WORK_DIR/metrics"; then
  grep -E 'beanflow_pg_database|pg_exporter_last_scrape_error' "$WORK_DIR/metrics" >&2 || true
  exit 1
fi
grep -Eq '^pg_stat_statements_calls_total\{[^}]*datname="beanflow_perf"' "$WORK_DIR/metrics"
grep -Eq '^pg_stat_statements_seconds_total\{[^}]*datname="beanflow_perf"' "$WORK_DIR/metrics"

docker exec "$FIXTURE-postgres" psql -v ON_ERROR_STOP=1 -U beanflow -d beanflow_perf \
  -c 'BEGIN; UPDATE lock_fixture SET value = value + 1 WHERE id = 1; SELECT pg_sleep(8); ROLLBACK;' \
  >"$WORK_DIR/blocker.log" 2>&1 &
blocker_pid=$!
sleep 1
docker exec "$FIXTURE-postgres" psql -v ON_ERROR_STOP=1 -U beanflow -d beanflow_perf \
  -c 'UPDATE lock_fixture SET value = value + 1 WHERE id = 1;' >"$WORK_DIR/waiter.log" 2>&1 &
waiter_pid=$!
docker exec "$FIXTURE-postgres" psql -v ON_ERROR_STOP=1 -U beanflow -d other_database \
  -c 'BEGIN; UPDATE lock_fixture SET value = value + 1 WHERE id = 1; SELECT pg_sleep(8); ROLLBACK;' \
  >"$WORK_DIR/other-blocker.log" 2>&1 &
other_blocker_pid=$!
sleep 1
docker exec "$FIXTURE-postgres" psql -v ON_ERROR_STOP=1 -U beanflow -d other_database \
  -c 'UPDATE lock_fixture SET value = value + 1 WHERE id = 1;' >"$WORK_DIR/other-waiter.log" 2>&1 &
other_waiter_pid=$!
sleep 1
curl -fsS --max-time 5 "http://127.0.0.1:$port/metrics" -o "$WORK_DIR/lock-metrics"
grep -Eq '^beanflow_pg_ungranted_locks\{[^}]*database="beanflow_perf"[^}]*\} 1$' "$WORK_DIR/lock-metrics"
grep -Eq '^beanflow_pg_blocked_sessions\{[^}]*database="beanflow_perf"[^}]*\} 1$' "$WORK_DIR/lock-metrics"
grep -Eq '^beanflow_pg_lock_wait_max_seconds\{[^}]*database="beanflow_perf"[^}]*\} [1-9][0-9]*(\.[0-9]+)?$' "$WORK_DIR/lock-metrics"
if grep -E '^beanflow_.*other_database' "$WORK_DIR/lock-metrics"; then
  echo 'Exporter leaked metrics from a different database.' >&2
  exit 1
fi
python3 "$REPOSITORY_ROOT/scripts/perf/db-diagnostics-exporter.py" \
  --bind 127.0.0.1 --allow 127.0.0.1 --project "$FIXTURE" --database beanflow_perf \
  --environment perf --host local-smoke --once --output-only >"$WORK_DIR/db-diagnostics.json"
grep -q 'beanflow_db_diagnostics_blocked_sessions 1' "$WORK_DIR/db-diagnostics.json"
grep -q '"wait_event": "transactionid"' "$WORK_DIR/db-diagnostics.json"
grep -q '"database": "beanflow_perf"' "$WORK_DIR/db-diagnostics.json"
grep -q '"query_family": "unmapped"' "$WORK_DIR/db-diagnostics.json"
if grep -Eq 'other_database|UPDATE|lock_fixture' "$WORK_DIR/db-diagnostics.json"; then
  echo 'Bounded DB snapshot leaked another database or SQL text.' >&2
  exit 1
fi
wait "$blocker_pid" "$waiter_pid" "$other_blocker_pid" "$other_waiter_pid"
docker exec --user 65534:65534 "$FIXTURE-exporter" /bin/sh -ec '
  test ! -r /run/secrets/BEANFLOW_POSTGRES_PASSWORD
  test "$(stat -c %u:%g:%a /run/secrets/BEANFLOW_POSTGRES_PASSWORD)" = 0:0:600
  test "$(stat -c %u:%g:%a /run/beanflow-postgres-exporter/password)" = 65534:65534:400
  awk "/^Uid:/ { if (\$2 != 65534 || \$3 != 65534 || \$4 != 65534 || \$5 != 65534) exit 1; found=1 } END { if (!found) exit 1 }" /proc/1/status
  test "$(cat /proc/1/comm)" = postgres_export
'
echo 'PostgreSQL exporter runtime: PASS (stat_statements, database binding, transactionid wait, cross-DB isolation, runtime security)'
