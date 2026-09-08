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
docker compose -f compose.portfolio.yml -f compose.perf.yml config --format json | python3 -c '
import json, sys
config = json.load(sys.stdin)
api = config["services"]["api"]
assert "-Djava.io.tmpdir=/run/beanflow-jvm-tmp" in api["environment"]["JAVA_TOOL_OPTIONS"]
assert "/run/beanflow-jvm-tmp:rw,exec,nosuid,nodev,size=128m,uid=10001,gid=10001,mode=0700" in api["tmpfs"]
for path in ("/tmp", "/run/beanflow-vault", "/run/beanflow-secrets"):
    assert any(mount.startswith(path + ":") and "noexec" in mount.split(":", 1)[1].split(",") for mount in api["tmpfs"])
assert api["environment"]["SPRING_PROFILES_ACTIVE"] == "perf"
exporter = config["services"]["postgres-exporter"]
assert exporter["user"] == "0:0"
assert exporter["entrypoint"] == ["/bin/sh", "/opt/beanflow/postgres-exporter-entrypoint.sh"]
assert exporter["environment"]["DATA_SOURCE_PASS_FILE"] == "/run/beanflow-postgres-exporter/password"
assert "/run/beanflow-postgres-exporter:rw,noexec,nosuid,nodev,size=1m,uid=65534,gid=65534,mode=0700" in exporter["tmpfs"]
for service in ("postgres-exporter", "node-exporter"):
    assert "egress" in config["services"][service]["networks"]
assert set(config["services"]["toss-driver"]["networks"]) == {"observability"}
assert config["networks"]["observability"]["internal"] is True
'
if docker compose -f compose.portfolio.yml -f compose.perf.yml config --services | rg -qx 'cadvisor'; then
  echo 'cAdvisor must remain disabled in the default perf profile.' >&2
  exit 1
fi
docker compose --profile container-metrics -f compose.portfolio.yml -f compose.perf.yml \
  config --services | rg -qx 'cadvisor'
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
  any(.panels[]; .title == "Route p95 / p99 (exemplar → Tempo)") and
  any(.panels[]; .title | contains("Hikari")) and
  any(.panels[]; .title | contains("PostgreSQL waiting")) and
  any(.panels[]; .title == "Container CPU cores") and
  any(.panels[]; .title == "Container memory working set") and
  any(.panels[].targets[]?; (.expr // "") | startswith("k6_http_req_duration_p95{")) and
  any(.panels[].targets[]?; (.expr // "") | startswith("k6_beanflow_workflow_failures_rate{")) and
  any(.panels[]; .title == "Slow traces") and
  any(.panels[]; .type == "logs") and
  any(.panels[]; .type == "flamegraph")
' infra/observability/central/grafana/dashboards/beanflow-performance-rca.json >/dev/null

ruby -e 'require "yaml"; ARGV.each { |path| YAML.safe_load(File.read(path), aliases: true) }' \
  infra/observability/postgres-queries.yaml \
  infra/observability/central/prometheus-scrape.yml \
  infra/observability/central/prometheus-cadvisor-scrape.yml \
  infra/observability/central/prometheus-container-stats-scrape.yml \
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
rg -q 'profileTypeId: wall:wall:nanoseconds:wall:nanoseconds' \
  infra/observability/central/grafana/provisioning/datasources/beanflow.yml
rg -q 'DATA_SOURCE_PASS_FILE' compose.perf.yml
rg -q '^  node-exporter:' compose.perf.yml
rg -q 'job_name: beanflow-node' infra/observability/central/prometheus-scrape.yml
rg -q 'job_name: beanflow-cadvisor' infra/observability/central/prometheus-cadvisor-scrape.yml
rg -q 'beanflow.*-cadvisor' infra/observability/central/beanflow-performance.rules.yml
rg -q 'BeanFlowPerfDroppedIterations' infra/observability/central/beanflow-performance.rules.yml
rg -q 'releases/download/v2.31.1/opentelemetry-javaagent.jar' Dockerfile
rg -q 'releases/download/v2.1.2/pyroscope-otel-javaagent-extension.jar' Dockerfile
[[ "$(rg -c 'ADD --checksum=sha256:' Dockerfile)" -eq 2 ]]
if rg -q -- '--collector.stat_statements.include_query' compose.perf.yml; then
  echo 'PostgreSQL exporter must not publish SQL query text.' >&2
  exit 1
fi

ruby -ryaml -e '
  cadvisor = YAML.safe_load(File.read("compose.perf.yml"), aliases: true)
    .fetch("services").fetch("cadvisor")
  required_mounts = [
    "/:/rootfs:ro",
    "/var/run:/var/run:ro",
    "/sys:/sys:ro",
    "/var/lib/docker:/var/lib/docker:ro",
    "/dev/disk:/dev/disk:ro"
  ]
  abort "cAdvisor profile boundary is invalid" unless cadvisor.fetch("profiles") == ["container-metrics"]
  abort "cAdvisor privilege boundary is invalid" unless cadvisor.fetch("privileged") && cadvisor.fetch("read_only")
  abort "cAdvisor host mounts must remain read-only" unless (required_mounts - cadvisor.fetch("volumes")).empty?
  abort "cAdvisor kmsg access must remain read-only" unless cadvisor.fetch("devices") == ["/dev/kmsg:/dev/kmsg:r"]
'

node --test scripts/load/load-contract.test.mjs scripts/load/k6-runtime.test.mjs infra/perf/toss-driver.test.mjs
python3 scripts/perf/test-container-stats-exporter.py
python3 scripts/perf/test-dashboard-queries.py
python3 scripts/perf/test-live-dashboard-queries.py
python3 scripts/load/run-contract.test.py
bash -n scripts/perf/hold-pickup-slot-lock.sh scripts/perf/postgres-wait-snapshot.sh
sh -n scripts/perf/postgres-exporter-entrypoint.sh
bash scripts/perf/test-postgres-exporter-runtime.sh

echo 'BeanFlow observability contract: PASS'
