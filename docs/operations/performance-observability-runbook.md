# BeanFlow 성능 관측성과 부하 테스트 Runbook

## 목표와 완료 조건

이 Runbook은 전용 perf 환경에서 다음 진단 흐름을 실제 데이터로 확인한다.

`사용자 지연/오류 → route → Hikari/PostgreSQL wait → Toss·AIStor·Vault child span → 느린 trace → 같은 trace log → JVM flame graph`

완료는 설정 파일의 존재가 아니라 아래 증거가 모두 같은 Grafana 시간 범위에 나타나는 상태다.

- Prometheus route histogram에 Tempo로 이동하는 exemplar가 있다.
- Tempo trace에 HTTP root, JDBC와 실행한 Provider child span이 있다.
- trace 상세에서 같은 `trace_id`의 Loki log가 열린다.
- `pyroscope.profile.id`가 있는 root span에서 `Profiles for this span` flame graph가 열린다.
- DB lock 실행 중 Hikari pending 또는 PostgreSQL wait/미획득 lock이 같은 시간에 보인다. 이 둘은
  trace와 동일 ID인 증거가 아니라 제어된 시간 구간의 상관 증거다.
- k6 오류, check 실패와 `dropped_iterations`를 성공 처리하지 않는다.

중앙 서버에 실제 설정을 적용하거나 아래 smoke를 실행하기 전까지 상태는 `정적 계약 통과`일 뿐
`운영 가능`이 아니다.

## 토폴로지

```text
VPN load generator --HTTPS/WAF--> BeanFlow frontend/API
                                    |-- Prometheus /actuator/prometheus <-- central Prometheus
                                    |-- OTel trace + log --> local Alloy --> central Tempo/Loki
                                    |-- span profile ---------------------> central Pyroscope
                                    |-- PostgreSQL <-- postgres_exporter <-- central Prometheus
                                    `-- host CPU/memory/I/O <-- node_exporter <-- central Prometheus

central Grafana --> Prometheus + Tempo + Loki + Pyroscope
```

Pushgateway는 이 경로에 사용하지 않는다. BeanFlow와 exporter는 pull 대상이고, k6와 Tempo
metrics-generator만 Prometheus remote-write receiver를 사용한다.

## 1. 중앙 monitoring 서버 적용

모든 ingest와 scrape 주소는 VPN 또는 동등한 사설망에서만 연다. 아래 파일은 기존 설정을 대체하는
완전한 설정이 아니라 병합용 조각이다.

1. [prometheus-scrape.yml](../../infra/observability/central/prometheus-scrape.yml)의 네 target에서
   `BEANFLOW_PERF_VPN_HOST`를 실제 perf 서버 VPN 주소로 바꿔 `scrape_configs`에 병합한다.
2. [성능 recording/alert rules](../../infra/observability/central/beanflow-performance.rules.yml)을 중앙
   Prometheus rule directory에 두고 `rule_files`에 병합한 뒤 `promtool check rules`를 실행한다. 이 기준은
   초기 부하 테스트 guardrail이며 측정된 운영 SLO가 아니다.
3. Prometheus에 `--web.enable-remote-write-receiver`를 추가한다. 이 receiver는 VPN load generator와
   Tempo에서만 접근 가능하게 제한한다.
4. [tempo-metrics-generator.yml](../../infra/observability/central/tempo-metrics-generator.yml)을 Tempo 2.10
   설정에 병합한다. 기존 `overrides`가 있으면 덮어쓰지 말고 `service-graphs`, `span-metrics` processor를
   기존 목록에 추가한다. `service_graphs.peer_attributes`도 전체 목록을 병합해 `beanflow.provider`가
   Toss·AIStor·Vault의 bounded virtual node 이름으로 먼저 선택되게 한다.
5. [loki-otlp.yml](../../infra/observability/central/loki-otlp.yml)을 Loki 3.7 설정에 병합한다. ID와 request
   metadata는 index label이 아니라 structured metadata로 유지한다.
6. [BeanFlow datasource provisioning](../../infra/observability/central/grafana/provisioning/datasources/beanflow.yml)을
   Grafana provisioning directory에 복사하고 중앙 container에 다음 query URL을 지정한다.

   ```bash
   BEANFLOW_PROMETHEUS_URL=http://prometheus:9090
   BEANFLOW_LOKI_URL=http://loki:3100
   BEANFLOW_TEMPO_URL=http://tempo:3200
   BEANFLOW_PYROSCOPE_URL=http://pyroscope:4040
   ```

7. [dashboard provider](../../infra/observability/central/grafana/provisioning/dashboards/beanflow.yml)와
   [Performance RCA dashboard](../../infra/observability/central/grafana/dashboards/beanflow-performance-rca.json)을
   각각 provisioning/dashboards와 `/var/lib/grafana/dashboards/beanflow`에 배치한다.
8. 중앙 구성 요소를 재시작한 뒤 Prometheus Targets, Tempo metrics-generator remote write, Loki `/ready`,
   Pyroscope와 Grafana datasource의 상태를 확인한다.

Alloy endpoint에는 경로를 중복하지 않는다.

- Tempo: `http://MONITORING_HOST:4318` — Alloy가 `/v1/traces`를 붙인다.
- Loki: `http://MONITORING_HOST:3100/otlp` — Alloy가 `/v1/logs`를 붙인다.
- Pyroscope: `http://MONITORING_HOST:4040`

인증 또는 tenancy가 있다면 secret 값을 repository YAML에 쓰지 말고 중앙 reverse proxy나 Alloy가 읽는
secret file로 추가한다. `X-Scope-OrgID`는 write와 Grafana query 양쪽에 같은 값을 사용한다.

## 2. perf 애플리케이션 서버 기동

기존 portfolio secret set을 복사하지 말고 perf 전용 PostgreSQL, AIStor bucket, Vault Transit mount/key와
최소 권한 credential을 만든다. Compose는 필요한 endpoint/secret이 없으면 시작하지 않는다. 특히
Vault AppRole policy가 perf mount/key만 허용하고 AIStor credential이 perf bucket 밖을 쓰지 못하는지
먼저 확인한다.

```bash
export COMPOSE_PROJECT_NAME=beanflow-perf
export BEANFLOW_MONITORING_BIND_ADDRESS=10.0.0.21
export BEANFLOW_PERF_AISTOR_BUCKET=beanflow-perf
export BEANFLOW_AISTOR_BUCKET="$BEANFLOW_PERF_AISTOR_BUCKET"
export BEANFLOW_TEMPO_OTLP_HTTP_ENDPOINT=http://10.0.0.10:4318
export BEANFLOW_LOKI_OTLP_HTTP_ENDPOINT=http://10.0.0.10:3100/otlp
export BEANFLOW_PYROSCOPE_SERVER_ADDRESS=http://10.0.0.10:4040

docker compose -f compose.portfolio.yml -f compose.perf.yml config --quiet
docker compose -f compose.portfolio.yml -f compose.perf.yml build api
docker compose -f compose.portfolio.yml -f compose.perf.yml up -d
```

기존 perf PostgreSQL volume을 재사용한다면 init script가 다시 실행되지 않는다. 이 경우 PostgreSQL을
`shared_preload_libraries=pg_stat_statements` 설정으로 재시작한 뒤 perf DB에서 다음 명령을 한 번 실행한다.
query text나 bind value를 dashboard/exporter label로 노출하지 않는다.

```bash
docker compose -f compose.portfolio.yml -f compose.perf.yml exec postgres \
  psql -U beanflow -d beanflow_perf -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements;'
```

위 예시 외에 `compose.portfolio.yml`이 요구하는 public origin, image, trusted proxy, Vault, AIStor와 secret
directory 변수가 필요하다. 실제 값은 shell history나 repository에 남기지 않는 secret runner에서 주입한다.

기동 확인:

```bash
curl --fail http://10.0.0.21:18081/actuator/health
curl --fail http://10.0.0.21:18081/actuator/prometheus
curl --fail http://10.0.0.21:12345/-/ready
curl --fail http://10.0.0.21:19187/metrics
curl --fail http://10.0.0.21:19100/metrics
```

동일 부하에서 agent overhead를 분리할 때는 먼저 metrics-only 기준선을 측정하고, 별도 재기동 후 RCA
측정을 한다. 두 실행의 fixture, 도착률, 기간과 JVM limit을 같게 유지한다.

```bash
export BEANFLOW_OTEL_ENABLED=false
export BEANFLOW_PYROSCOPE_ENABLED=false
# metrics-only 기준선 재기동 및 측정

export BEANFLOW_OTEL_ENABLED=true
export BEANFLOW_PYROSCOPE_ENABLED=true
# trace/profile RCA 재기동 및 측정
```

## 3. 부하 fixture 준비

fixture는 repository 밖의 권한 `0600` JSON 파일로 만든다. 운영 계정·bucket·Vault grant를 사용하지 않는다.
Session은 부하 직전에 전용 계정의 정상 login/CSRF API로 발급하고 종료 후 폐기한다. target iteration보다
충분한 재고와 픽업 슬롯 수용량을 준비하지 않으면 business conflict를 서버 처리량 한계로 오해하게 된다.

```json
{
  "customerSessions": [
    {"session": "REDACTED", "xsrf": "REDACTED"}
  ],
  "merchantSessions": [
    {"session": "REDACTED", "xsrf": "REDACTED"}
  ],
  "order": {
    "storeId": "PERF_STORE_UUID",
    "pickupSlotIds": ["PERF_SLOT_UUID"],
    "lines": [
      {"menuId": "PERF_MENU_UUID", "optionIds": ["PERF_OPTION_UUID"], "quantity": 1}
    ],
    "pointsToUseKrw": 0
  },
  "aistorProbes": [
    {"storeId": "PERF_STORE_UUID", "session": "REDACTED", "xsrf": "REDACTED"}
  ],
  "vaultRevealProbes": [
    {
      "grantId": "ONE_SHOT_ACTIVE_GRANT_UUID",
      "bearerToken": "REDACTED",
      "accessReason": "approved performance verification",
      "fields": ["CUSTOMER_PRIMARY_PHONE"]
    }
  ]
}
```

각 Vault reveal은 승인된 grant 예산을 실제로 한 번 소비한다. `provider-probes.js`가 그래서
`per-vu-iterations` 1회만 허용한다. AIStor probe도 전용 store의 대표 이미지를 바꾸므로 테스트 종료 후
fixture 복원 여부를 확인한다.

## 4. 단계별 k6 실행

load generator에서 Prometheus remote write endpoint를 먼저 지정한다. 인증 값은 shell에 직접 쓰기보다
secret file/runner를 사용한다.

```bash
export PERF_TEST_ID=baseline-20260902-01
export PERF_FIXTURE=/secure/beanflow-perf-fixture.json
export K6_PROMETHEUS_RW_SERVER_URL=http://10.0.0.10:9090/api/v1/write
export K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),min,max
```

가장 작은 quote→fingerprint→order 기준선부터 시작한다.

```bash
k6 run \
  --tag testid="$PERF_TEST_ID" \
  -e BEANFLOW_BASE_URL=https://perf.beanflow.example \
  -e BEANFLOW_TEST_ID="$PERF_TEST_ID" \
  -e BEANFLOW_LOAD_FIXTURE="$PERF_FIXTURE" \
  -e BEANFLOW_LOAD_SCENARIO=quote-order \
  -e BEANFLOW_RATE=1 \
  -e BEANFLOW_DURATION=1m \
  -o experimental-prometheus-rw \
  scripts/load/beanflow-load.js
```

통과한 도착률만 `1 → 2 → 5 → ...`처럼 한 단계씩 올린다. `checks`, business failure, HTTP 오류,
p95/p99, Hikari pending, PostgreSQL wait, CPU/GC와 `dropped_iterations`를 같은 test ID와 Grafana 시간 범위로
기록한다. `dropped_iterations > 0`이면 설정한 arrival rate를 생성하지 못한 것이므로 그 실행은 용량
기준선 통과가 아니다.

다른 시나리오는 같은 명령의 scenario와 executor 인자만 바꾼다.

```bash
# ETag를 재사용하는 3초 주기 점주 보드 polling
k6 run --tag testid="$PERF_TEST_ID" \
  -e BEANFLOW_BASE_URL=https://perf.beanflow.example \
  -e BEANFLOW_TEST_ID="$PERF_TEST_ID" \
  -e BEANFLOW_LOAD_FIXTURE="$PERF_FIXTURE" \
  -e BEANFLOW_LOAD_SCENARIO=board-polling \
  -e BEANFLOW_BOARD_VUS=4 -e BEANFLOW_DURATION=5m \
  -o experimental-prometheus-rw scripts/load/beanflow-load.js

# deterministic Toss: toss-success, toss-decline, toss-timeout, toss-unknown 중 하나
k6 run --tag testid="$PERF_TEST_ID" \
  -e BEANFLOW_BASE_URL=https://perf.beanflow.example \
  -e BEANFLOW_TEST_ID="$PERF_TEST_ID" \
  -e BEANFLOW_LOAD_FIXTURE="$PERF_FIXTURE" \
  -e BEANFLOW_LOAD_SCENARIO=toss-timeout \
  -e BEANFLOW_RATE=1 -e BEANFLOW_DURATION=1m \
  -o experimental-prometheus-rw scripts/load/beanflow-load.js
```

실제 AIStor/Vault probe는 부하가 아니라 연결·상관 smoke다.

```bash
k6 run --tag testid="$PERF_TEST_ID" \
  -e BEANFLOW_BASE_URL=https://perf.beanflow.example \
  -e BEANFLOW_TEST_ID="$PERF_TEST_ID" \
  -e BEANFLOW_LOAD_FIXTURE="$PERF_FIXTURE" \
  -e BEANFLOW_PROVIDER_PROBE=aistor \
  -o experimental-prometheus-rw scripts/load/provider-probes.js

k6 run --tag testid="$PERF_TEST_ID" \
  -e BEANFLOW_BASE_URL=https://perf.beanflow.example \
  -e BEANFLOW_TEST_ID="$PERF_TEST_ID" \
  -e BEANFLOW_LOAD_FIXTURE="$PERF_FIXTURE" \
  -e BEANFLOW_PROVIDER_PROBE=vault \
  -o experimental-prometheus-rw scripts/load/provider-probes.js
```

## 5. 제어된 DB lock 재현

이 절차는 perf DB의 fixture pickup slot만 대상으로 하고 30초 이내 자동 rollback한다. 첫 terminal에서
lock을 잡고 두 번째 terminal에서 동일 slot을 쓰는 주문 시나리오를 실행한다.

```bash
bash scripts/perf/hold-pickup-slot-lock.sh PERF_SLOT_UUID 15
```

```bash
k6 run --tag testid="$PERF_TEST_ID" \
  -e BEANFLOW_BASE_URL=https://perf.beanflow.example \
  -e BEANFLOW_TEST_ID="$PERF_TEST_ID" \
  -e BEANFLOW_LOAD_FIXTURE="$PERF_FIXTURE" \
  -e BEANFLOW_LOAD_SCENARIO=db-lock \
  -e BEANFLOW_RATE=1 -e BEANFLOW_DURATION=20s \
  -o experimental-prometheus-rw scripts/load/beanflow-load.js
```

재현 중 집계 snapshot을 볼 수 있다. SQL text나 bind value는 출력하지 않는다.

```bash
bash scripts/perf/postgres-wait-snapshot.sh
```

## 6. Grafana에서 진단

1. `BeanFlow / Performance RCA`에서 k6 `test_id`와 정확한 실행 시간 범위를 선택한다.
2. Route p95 또는 오류 패널의 exemplar를 눌러 Tempo trace를 연다.
3. Tempo Service Graph에서 `beanflow → postgresql` 또는 outbound dependency 방향을 확인한다.
4. trace에서 긴 JDBC span과 `beanflow.toss.*`, `beanflow.aistor.*`, `beanflow.vault.*` child span의 닫힌
   `beanflow.outcome`을 확인한다.
5. 같은 시간의 Hikari active/max/pending와 PostgreSQL waiting/ungranted lock을 확인한다. 임의 trace와
   DB backend PID가 exact하게 연결됐다고 표현하지 않는다.
6. trace 상세의 Logs를 열어 같은 `trace_id` log만 남는지 확인한다.
7. `pyroscope.profile.id`가 있는 root span의 `Profiles for this span`을 열어 해당 span profile flame graph를
   확인한다. 짧은 span은 sampling 간격 때문에 profile이 없을 수 있으므로 DB lock 또는 Toss timeout처럼
   충분히 긴 제어 시나리오로 검증한다.

## 7. 정적 검증과 종료

```bash
bash scripts/perf/test-observability-contract.sh
./scripts/verify-docs.sh
./gradlew spotlessCheck test bootJar
```

테스트 종료 후 k6를 중지하고, 더 이상 새 request가 없는지 확인한 다음 perf Compose를 내린다. volume 삭제는
fixture 보존/폐기 정책을 확인한 별도 승인 작업으로 수행하며 이 Runbook의 기본 종료에 포함하지 않는다.

```bash
docker compose -f compose.portfolio.yml -f compose.perf.yml down
```

결과에는 `Passed`, `Failed`, `Pending`, `Not run`을 구분해 적는다. 단일 실행이나 정적 검증으로 SLA,
처리 용량 또는 운영 안정성을 주장하지 않는다.

## 참고

- [Grafana Tempo datasource correlation](https://grafana.com/docs/grafana/latest/datasources/tempo/configure-tempo-data-source/)
- [Tempo metrics-generator](https://grafana.com/docs/tempo/latest/metrics-from-traces/metrics-generator/)
- [Loki native OTLP ingestion](https://grafana.com/docs/loki/latest/send-data/otel/)
- [Pyroscope Java span profiles](https://grafana.com/docs/pyroscope/latest/configure-client/trace-span-profiles/java-span-profiles/)
- [k6 Prometheus remote write](https://grafana.com/docs/k6/latest/results-output/real-time/prometheus-remote-write/)
