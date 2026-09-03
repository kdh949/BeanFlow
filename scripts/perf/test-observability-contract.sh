#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTRACT_SECRETS="$(mktemp -d "${TMPDIR:-/tmp}/beanflow-observability-contract.XXXXXX")"
trap 'rm -rf "$CONTRACT_SECRETS"' EXIT

for secret_name in \
  BEANFLOW_POSTGRES_PASSWORD \
  BEANFLOW_KEYCLOAK_DB_PASSWORD \
  BEANFLOW_KEYCLOAK_ADMIN_PASSWORD \
  BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL \
  BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL \
  BEANFLOW_AISTOR_ACCESS_KEY \
  BEANFLOW_AISTOR_SECRET_KEY \
  TOSS_CLIENT_KEY \
  TOSS_SECRET_KEY \
  BEANFLOW_VAULT_ROLE_ID \
  BEANFLOW_VAULT_SECRET_ID \
  BEANFLOW_VAULT_CA_PEM
do
  printf 'contract-only\n' >"$CONTRACT_SECRETS/$secret_name"
done

export COMPOSE_PROJECT_NAME=beanflow-observability-contract
export BEANFLOW_SECRETS_DIR="$CONTRACT_SECRETS"
export BEANFLOW_BIND_ADDRESS=127.0.0.1
export BEANFLOW_MONITORING_BIND_ADDRESS=127.0.0.1
export BEANFLOW_PUBLIC_ORIGIN=https://beanflow.invalid
export BEANFLOW_API_IMAGE_REPOSITORY=beanflow-api
export BEANFLOW_WEB_IMAGE_REPOSITORY=beanflow-web
export BEANFLOW_IMAGE_TAG=contract
export BEANFLOW_AUTH_TRUSTED_PROXY_CIDRS=127.0.0.1/32
export BEANFLOW_AISTOR_ENDPOINT=https://aistor.invalid
export BEANFLOW_AISTOR_PUBLIC_ENDPOINT=https://aistor-public.invalid
export BEANFLOW_AISTOR_BUCKET=beanflow-contract
export BEANFLOW_CURSOR_HMAC_ACTIVE_KEY_ID=contract
export BEANFLOW_VAULT_ADDR=https://vault.invalid
export BEANFLOW_VAULT_BLIND_INDEX_WRITE_VERSION=1
export BEANFLOW_VAULT_BLIND_INDEX_SEARCH_VERSIONS=1
export BEANFLOW_TEMPO_OTLP_HTTP_ENDPOINT=http://tempo.invalid:4318
export BEANFLOW_LOKI_OTLP_HTTP_ENDPOINT=http://loki.invalid:3100/otlp
export BEANFLOW_PYROSCOPE_SERVER_ADDRESS=http://pyroscope.invalid:4040

cd "$REPOSITORY_ROOT"

docker compose -f compose.portfolio.yml -f compose.perf.yml config --quiet
docker run --rm \
  -e BEANFLOW_TEMPO_OTLP_HTTP_ENDPOINT \
  -e BEANFLOW_LOKI_OTLP_HTTP_ENDPOINT \
  -v "$REPOSITORY_ROOT/infra/observability/alloy/config.alloy:/etc/alloy/config.alloy:ro" \
  grafana/alloy:v1.13.2 validate /etc/alloy/config.alloy
docker run --rm --entrypoint /bin/promtool \
  -v "$REPOSITORY_ROOT/infra/observability/central/beanflow-performance.rules.yml:/etc/prometheus/beanflow-performance.rules.yml:ro" \
  prom/prometheus:v3.14.0 \
  check rules /etc/prometheus/beanflow-performance.rules.yml

jq -e '
  .uid == "beanflow-performance-rca" and
  any(.panels[]; .title == "Route p95 (exemplar → Tempo)") and
  any(.panels[]; .title | contains("Hikari")) and
  any(.panels[]; .title | contains("PostgreSQL waiting")) and
  any(.panels[].targets[]?; .expr == "k6_http_req_duration_p95{testid=\"$test_id\"}") and
  any(.panels[].targets[]?; .expr == "k6_http_req_failed_rate{testid=\"$test_id\"}") and
  any(.panels[]; .title == "Slow traces") and
  any(.panels[]; .type == "logs") and
  any(.panels[]; .type == "flamegraph")
' infra/observability/central/grafana/dashboards/beanflow-performance-rca.json >/dev/null

ruby -e 'require "yaml"; ARGV.each { |path| YAML.safe_load(File.read(path), aliases: true) }' \
  infra/observability/postgres-queries.yaml \
  infra/observability/central/prometheus-scrape.yml \
  infra/observability/central/beanflow-performance.rules.yml \
  infra/observability/central/tempo-metrics-generator.yml \
  infra/observability/central/loki-otlp.yml \
  infra/observability/central/grafana/provisioning/datasources/beanflow.yml \
  infra/observability/central/grafana/provisioning/dashboards/beanflow.yml

rg -q 'expectedQuoteFingerprint: fingerprint' scripts/load/beanflow-load.js
rg -q 'filterByTraceID: true' infra/observability/central/grafana/provisioning/datasources/beanflow.yml
rg -q 'matcherType: label' infra/observability/central/grafana/provisioning/datasources/beanflow.yml
rg -q 'peer_attributes: \[beanflow.provider, peer.service, db.name, db.system\]' \
  infra/observability/central/tempo-metrics-generator.yml
rg -q 'profileTypeId: process_cpu:wall:nanoseconds:wall:nanoseconds' \
  infra/observability/central/grafana/provisioning/datasources/beanflow.yml
rg -q 'DATA_SOURCE_PASS_FILE' compose.perf.yml
rg -q '^  node-exporter:' compose.perf.yml
rg -q 'job_name: beanflow-node' infra/observability/central/prometheus-scrape.yml
rg -q 'BeanFlowPerfDroppedIterations' infra/observability/central/beanflow-performance.rules.yml
rg -q 'releases/download/v2.31.1/opentelemetry-javaagent.jar' Dockerfile
rg -q 'releases/download/v2.1.2/pyroscope-otel-javaagent-extension.jar' Dockerfile
[[ "$(rg -c 'ADD --checksum=sha256:' Dockerfile)" -eq 2 ]]
if rg -q -- '--collector.stat_statements.include_query' compose.perf.yml; then
  echo 'PostgreSQL exporter must not publish SQL query text.' >&2
  exit 1
fi

node --test scripts/load/load-contract.test.mjs infra/perf/toss-driver.test.mjs
bash -n scripts/perf/hold-pickup-slot-lock.sh scripts/perf/postgres-wait-snapshot.sh

echo 'BeanFlow observability contract: PASS'
