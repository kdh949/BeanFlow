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
  -v "$FIXTURE-secrets:/run/secrets:ro" \
  -e POSTGRES_PASSWORD_FILE=/run/secrets/BEANFLOW_POSTGRES_PASSWORD \
  -e POSTGRES_USER=beanflow -e POSTGRES_DB=beanflow_perf \
  "$POSTGRES_IMAGE" -c shared_preload_libraries=pg_stat_statements >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$FIXTURE-postgres" pg_isready -h 127.0.0.1 -U beanflow -d beanflow_perf >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$FIXTURE-postgres" psql -v ON_ERROR_STOP=1 -U beanflow -d beanflow_perf \
  -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements;' >/dev/null

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
docker exec --user 65534:65534 "$FIXTURE-exporter" /bin/sh -ec '
  test ! -r /run/secrets/BEANFLOW_POSTGRES_PASSWORD
  test "$(stat -c %u:%g:%a /run/secrets/BEANFLOW_POSTGRES_PASSWORD)" = 0:0:600
  test "$(stat -c %u:%g:%a /run/beanflow-postgres-exporter/password)" = 65534:65534:400
  awk "/^Uid:/ { if (\$2 != 65534 || \$3 != 65534 || \$4 != 65534 || \$5 != 65534) exit 1; found=1 } END { if (!found) exit 1 }" /proc/1/status
  test "$(cat /proc/1/comm)" = postgres_export
'
echo 'PostgreSQL exporter runtime: PASS (missing/empty fail, root-only source, nobody process, published DB metrics)'
