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
                                    |-- host CPU/memory/I/O <-- node_exporter <-- central Prometheus
                                    `-- container CPU/memory <-- cAdvisor (opt-in) <-- central Prometheus

central Grafana --> Prometheus + Tempo + Loki + Pyroscope
```

Pushgateway는 이 경로에 사용하지 않는다. BeanFlow와 exporter는 pull 대상이고, k6와 Tempo
metrics-generator만 Prometheus remote-write receiver를 사용한다.

## 1. 중앙 monitoring 서버 적용

모든 ingest와 scrape 주소는 VPN 또는 동등한 사설망에서만 연다. 아래 파일은 기존 설정을 대체하는
완전한 설정이 아니라 병합용 조각이다.

1. [prometheus-scrape.yml](../../infra/observability/central/prometheus-scrape.yml)의 네 target에서
   `BEANFLOW_PERF_VPN_HOST`를 실제 perf 서버 VPN 주소로 바꿔 `scrape_configs`에 병합한다.
   cAdvisor를 활성화하는 실행에서만
   [prometheus-cadvisor-scrape.yml](../../infra/observability/central/prometheus-cadvisor-scrape.yml)의 다섯 번째
   target도 병합한다. cAdvisor를 끈 상태에서 이 job만 남겨 두면 target-down 경보가 발생한다.
   공유 host의 제한된 Docker stats 수집기를 사용하는 경우에는 cAdvisor 대신
   [prometheus-container-stats-scrape.yml](../../infra/observability/central/prometheus-container-stats-scrape.yml)의
   `beanflow-containers` job을 병합한다. 수집기는 배포 계정의 기존 Docker 권한으로 지정 project만
   읽으며 private port 19101에서 허용된 모니터링 서버에만 응답한다.
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
   UID는 `beanflow-prometheus`, `beanflow-loki`, `beanflow-tempo`, `beanflow-pyroscope`이며 기존 default
   datasource를 변경하지 않는다. 기존 generic UID로 가져온 dashboard는 이 JSON으로 함께 갱신한다.

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

컨테이너별 CPU와 memory가 필요한 경우에만 전용·폐기 가능 perf host에서 cAdvisor를 별도로 활성화한다.
이 명령은 `privileged` 컨테이너에 host root, `/var/run`, `/sys`, Docker data와 disk device의 read-only
접근을 허용한다. production, portfolio 또는 다른 workload와 공유하는 host에서는 실행하지 않는다.

```bash
docker compose --profile container-metrics \
  -f compose.portfolio.yml -f compose.perf.yml \
  up -d cadvisor

curl --fail http://10.0.0.21:18080/metrics
```

중앙 Prometheus에 선택형 scrape 조각을 병합한 뒤 `up{job="beanflow-cadvisor"} == 1`을 확인한다.
사용이 끝나면 cAdvisor만 먼저 중지할 수 있다.

```bash
docker compose --profile container-metrics \
  -f compose.portfolio.yml -f compose.perf.yml \
  stop cadvisor
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

### 필수 키와 profiler runtime 검증

- `BEANFLOW_CURSOR_HMAC_ACTIVE_KEY_ID`와 config tree의 `BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL`은
  perf에서도 필수다. `application-perf.yaml`이 직접 연결하므로 `portfolio` profile을 함께 활성화하거나
  임의의 키를 생성하지 않는다. 비밀키 누락·오류는 startup failure다.
- `compose.perf.yml`의 JVM 전용 `/run/beanflow-jvm-tmp` tmpfs는 Pyroscope native library를 위해
  실행을 허용한다. UID/GID 10001, mode `0700`, 128MiB 제한과 `nosuid,nodev`를 유지하며 기존 `/tmp`와
  secret directory의 `noexec`는 해제하지 않는다. JVM 옵션을 override할 때는 `-Djava.io.tmpdir`와
  OTel `-javaagent` 옵션을 함께 보존한다.
- health가 `UP`이어도 `OpenTelemetry Javaagent failed to start` 또는 `UnsatisfiedLinkError`가 있으면
  관측성 기동 검증은 실패다. 중앙 전송 여부는 별도로 확인한다.

이미지 publish 전 CI는 아래 검증에서 실제 perf Compose의 JVM 옵션과 tmpfs를 사용한다. 격리된
PostgreSQL/Vault fixture 위에서 portfolio 재시작, perf config-tree binding, health/Prometheus와 JVM에
매핑된 Pyroscope native library를 확인한다. 실제 Keycloak 로그인·Toss 결제·중앙 ingest 증거는 아니다.

```bash
bash scripts/deploy/test-backend-entrypoint.sh "$BEANFLOW_API_IMAGE_REPOSITORY:$BEANFLOW_IMAGE_TAG"
```

### 임시 startup 보정 파일을 적용했던 서버 갱신

이 수정 이전 배포에 `compose.runtime-fix.yml`과 `application-perf-fix.yml`을 추가했다면, 수정된
`compose.perf.yml`과 **수정된 `application-perf.yaml`을 포함하는 새 이미지 SHA**를 함께 배포한다.
checkout만 갱신하고 이전 이미지를 재사용하면 HMAC 오류는 남는다. 새 이미지 적용 시 Compose 명령에서
`-f /etc/beanflow/perf/compose.runtime-fix.yml`만 제외하고 API를 재생성한다. 기존 DB·Vault·AIStor 자원 선택을
담당하는 별도 override는 유지한다. Doppler 실행 형식과 secret directory도 기존 배포 입력을 유지한다.
health/Prometheus, agent 초기화와 중앙 ingest를 확인한 뒤 임시 보정 파일을 정리한다. DB 재초기화나
볼륨 삭제는 이 수정에 필요하지 않다.

기동 확인:

```bash
curl --fail http://10.0.0.21:18081/actuator/health
curl --fail http://10.0.0.21:18081/actuator/prometheus
curl --fail http://10.0.0.21:12345/-/ready
curl --fail http://10.0.0.21:19187/metrics
curl --fail http://10.0.0.21:19100/metrics
```

### Exporter 권한과 port publish 검증

PostgreSQL exporter는 root 소유 `0600` 배포 secret을 전용 tmpfs에 복사한 뒤 UID/GID 65534로 실행한다.
원본 secret을 `chmod 644`로 바꾸거나 exporter 프로세스를 root로 계속 실행하지 않는다.
`DATA_SOURCE_PASS_FILE`은 `/run/beanflow-postgres-exporter/password`를 가리켜야 한다.
node/PostgreSQL exporter에는 `internal` network 외에 `egress` 연결도 필요하다. `HostConfig.PortBindings`에
설정이 있어도 `NetworkSettings.Ports`가 비어 있으면 실제 publish된 상태가 아니다. network를 수동으로
연결해 복구했다면 다음 Compose 재생성에도 유지되도록 `compose.perf.yml`까지 반영한다.

```bash
bash scripts/perf/test-postgres-exporter-runtime.sh
```

위 격리 검증은 secret 누락·빈 값 실패, 원본 `0600` 유지, 실제 exporter PID의 UID/GID 65534,
호스트에 publish된 endpoint의 `pg_up 1`, `pg_exporter_last_scrape_error 0`과 DB lock metric을 확인한다.
실제 서버에서도 같은 값을 확인하고 중앙 Prometheus의 네 개 기본 target이 모두 `UP`인지 확인한다.

### 기존 중앙 stack에 적용할 때

- 앱 서버의 설정 주입은 기존 `doppler run --no-fallback --` 경로를 유지한다. 모니터링 서버의 Compose
  실행은 해당 서버의 기존 `--env-file`을 사용한다. 이번 수정을 위해 secret을 다른 파일에 복제하지 않는다.
- Prometheus 실행 중 설정과 디스크 파일을 먼저 비교한다. 실행 중인 다른 서비스 job/rule이 디스크에서
  사라졌다면 그대로 재시작하지 않는다. 현재 config와 container에 mount된 rule을 백업하고 함께 보존한다.
  원자적으로 교체한 bind source 파일은 기존 container가 이전 inode를 계속 볼 수 있으므로 재시작 후
  `/api/v1/status/config`, `/api/v1/targets`와 실제 query 결과까지 확인한다.
- Grafana의 기존 datasource UID를 공유한다면 `traceID`/`trace_id`, Loki structured metadata와 profile type
  차이를 먼저 비교한다. 별도 `beanflow-prometheus`, `beanflow-loki`, `beanflow-tempo`, `beanflow-pyroscope`
  UID를 사용할 경우 dashboard와 datasource 간 참조도 함께 바꾼다.
- Grafana provisioning reload는 기존 파일도 재적용한다. 실행 중 datasource JSON과 provisioning 파일을
  **reload 전에** 백업·비교하고 기존 연결 옵션이 바뀌지 않도록 맞춘다. dashboard provider 경로도 다른
  provider의 재귀 탐색 경로와 겹치지 않게 둔다.
- datasource health만으로 수집 성공을 단정하지 않는다. Grafana datasource proxy를 통한 PromQL 결과,
  Loki log 수, Tempo trace와 Pyroscope profile 조회를 확인한다. 실제 UI의 exemplar → trace → log →
  profile 이동과 부하/용량 검증은 별도 결과로 기록한다.

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
export K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),min,max'
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

### 실행 기록·기준선 비교 도구

[run.py](../../scripts/load/run.py)는 실제 배포 ID·자원 제한·agent 설정, 시나리오, 목표 도착률, VU,
데이터셋 ID, fixture/script digest, k6 버전과 발생기 자원 사용량을 기록한다. session과 CSRF 값은
manifest에 복사하지 않는다. 결과 디렉터리는 저장소 밖의 `0700`, 파일은 `0600`으로 생성하고 같은
test ID 디렉터리는 재사용하지 않는다. 아래 명령은 앱과 다른 VPN 부하 발생기에서 실행한다.

```bash
export BEANFLOW_BASE_URL=https://beanflow.dhkim.cloud
export BEANFLOW_TEST_ID="quote-order-$(date -u +%Y%m%dT%H%M%SZ)"
export BEANFLOW_LOAD_FIXTURE="$PERF_FIXTURE"
export BEANFLOW_DATASET_ID=baseline-v1 # 준비한 데이터셋의 실제 버전으로 지정
export BEANFLOW_LOAD_SCENARIO=quote-order
export BEANFLOW_RATE=1
export BEANFLOW_DURATION=1m
export K6_PROMETHEUS_RW_SERVER_URL=http://172.16.16.18:9090/api/v1/write
export BEANFLOW_GRAFANA_URL=http://172.16.16.18:3000
export BEANFLOW_MONITORING_SSH=kdh949@172.16.16.18
export BEANFLOW_MONITORING_SSH_KEY="$HOME/.ssh/id_ed25519"
export BEANFLOW_TARGET_CONFIG_FILE="${TMPDIR:-/tmp}/beanflow-target-config.json"

umask 077
ssh -i "$HOME/.ssh/id_ed25519" orbit-runner@172.16.16.22 \
  'python3 beanflow-perf-tools/scripts/perf/capture-load-target.py' \
  > "$BEANFLOW_TARGET_CONFIG_FILE"
python3 scripts/load/run.py --output-dir "$HOME/beanflow-load-runs"
```

target snapshot은 실행 전 10분 이내에 생성해야 한다. `manifest.json`의 `grafana_url`은 실행 시작부터
종료 후 1분까지를 연다. 종료 뒤 더 긴 회복 구간도 별도로 관찰한다. `summary.json`은 실제 k6 최종
측정값·threshold 판정이며 manifest의 `PASSED`는 이 실행의 guardrail 통과만 의미한다.
`telemetry_ingest=NOT_VERIFIED`는 해당 실행의 중앙 수집 완료를 보장하지 않는다는 뜻이다. Prometheus에서
선택 test ID의 series를 확인해야 하며 로컬 fixture 검증은 `--local-only`로 명시한다.

`--baseline /path/to/previous/manifest.json`을 추가하면 `comparison.json`을 만든다. 부하 설정,
데이터셋, script, k6/발생기와 target runtime이 다르거나 target snapshot이 없으면 비교 불가로 기록한다.
동일 설정이라는 검사만으로 capacity나 성능 향상을 판정하지 않는다. 다른 agent 설정을 의도적으로
비교하는 실험은 차이를 남긴 채 별도로 해석한다. Grafana의 기준선 ID 입력은 최근 7일의 workflow
p95 최종 표본을 나란히 보여주며, 서로 다른 실행이나 route의 percentile을 평균내지 않는다.

`BEANFLOW_MONITORING_SSH`를 지정하면 모니터링 서버의
[publish-annotation.py](../../scripts/load/publish-annotation.py)가 기존 Grafana runtime 자격증명을
메모리에서만 사용해 실행 구간 annotation을 게시한다. 모니터링 env 파일과 datasource는 바꾸지 않는다.
SSH 권한이 없는 발생기는 `annotation.json`을 보존하고 `NOT_PUBLISHED`로 남긴다. 별도 annotation
쓰기 토큰이 이미 있는 경우 `BEANFLOW_GRAFANA_TOKEN`도 지원하며 이 값은 k6 프로세스에 전달하지 않는다.

측정 의미는 다음과 같다.

| 지표 | 의미 |
| --- | --- |
| workflow started/completed/successes | 시작·검사 종료·시나리오 기대 결과 일치 건수 |
| workflow failure rate | 중간 실패와 JSON 오류를 포함한 workflow 단위 실패율 |
| orders created / payments approved | 실제 생성된 주문과 관측된 APPROVED 결제; 멱등 replay는 추가 주문으로 세지 않음 |
| payment outcomes | confirmation과 follow-up의 관측 상태; 거절/UNKNOWN은 승인으로 세지 않음 |
| HTTP/workflow p95·p99 | k6 remote write는 초, summary는 밀리초; workflow에는 검사·대기 시간이 포함됨 |
| dropped iterations | 목표 부하 미생성; 발생기 VU 부족과 서버 지연을 함께 조사 |

`toss-decline`의 confirmation에만 기대 HTTP 422를 허용하며 body의 `PAYMENT_DECLINED`도 검사한다.
예상하지 못한 4xx/5xx를 포괄적으로 정상 취급하지 않는다. `toss-timeout/unknown`의 1초 뒤 조회는
명시적 상태를 확인하는 검사이며 최종 reconciliation 완료 검증은 아니다.

### 공유 host의 컨테이너 수집기 재설치

[systemd unit](../../infra/observability/beanflow-container-stats.service)의 배포 계정·bind·허용 IP를
실제 환경에 맞춘다. 계정은 기존 Docker 관리 계정을 사용하며 새 privileged container를 띄우지 않는다.
Docker 권한 자체는 높은 권한이므로 이 서비스와 코드의 쓰기 권한은 root에 둔다.

```bash
sudo install -d -m 0755 /opt/beanflow-observability
sudo install -m 0644 scripts/perf/container-stats-exporter.py /opt/beanflow-observability/
sudo install -m 0644 infra/observability/beanflow-container-stats.service /etc/systemd/system/
sudo systemd-analyze verify /etc/systemd/system/beanflow-container-stats.service
sudo systemctl daemon-reload
sudo systemctl enable --now beanflow-container-stats.service
```

수집 실패 시 `beanflow_container_collection_success=0`과 마지막 성공 시각만 남기고 이전 container
수치를 재사용하지 않는다. `restart_count`는 재생성 시 초기화되는 gauge이고 CPU/memory limit 0은
무제한을 뜻한다. 실제 기동 상태와 메모리 제한 2 GiB 같은 배포 자원도 snapshot과 함께 확인한다.

### 2026-09-07 반영 상태와 복구

- 앱: Doppler의 AIStor endpoint를 `http://172.16.16.22:9000`으로 명시적으로 수정했다. 실제 listener는
  HTTP이며 public signing endpoint와 TLS 검증 정책은 별도다. 읽기 전용 배포 토큰은 값을 수정할 수
  없으므로 쓰기 권한 보유자가 Doppler에서 변경하고 배포는 값을 읽어 검증한다.
- API: `beanflow-api:perf-observability-20260907`, image ID
  `sha256:94979cf47cb2354f7152f4330725b324188090977bf6a81be89eb7c01ae9ddd6`을 배포했다.
  `/etc/beanflow/perf/compose.observability.yml`이 API image를 선택하며 기존 runtime-fix overlay는 현재
  Compose 실행에서 제외했다. 다음 이미지 갱신 때 이 image override도 함께 갱신해야 한다.
- DB: `beanflow-staging_postgres-data`를 유지했다. unused perf volume은 삭제하지 않았다.
- 수집기: app의 `beanflow-container-stats.service`, private 19101 → `beanflow-containers` job을 연결했다.
  cAdvisor는 공유 host에서 활성화하지 않았다.
- 도구: app/monitoring 각각 `~/beanflow-perf-tools/scripts/load/`에 실행 도구를 배치했다.
  target snapshot은 app의 `~/beanflow-perf-tools/scripts/perf/capture-load-target.py`, annotation 게시기는
  monitoring의 `~/beanflow-perf-tools/scripts/load/publish-annotation.py`에 있다.
- 앱 설정 백업은 `/var/backups/beanflow-rca-20260907T122306Z`, monitoring 백업은
  `/home/kdh949/beanflow-rca-backup-20260907T123030Z`다. rollback은 백업의 image/runtime overlay를
  선택해 API만 재생성하고, monitoring은 백업 config/JSON을 복원 후 reload한다. DB volume을 지우지 않는다.
- 검증: API healthy, media availability 1, scrape 5개 UP, container 7개 수집, dashboard 63개 항목의
  PromQL 84개 구문·조회, 7개 DB wait 가용성 경우와 실제 wall flame graph를 확인했다. 이후 9회 합성 부하의
  809 workflow/2,686 HTTP 요청, 중앙 ingest, exact span profile과 회복을 검증했다. 견적 충돌로 증량을
  중단했으며 capacity 측정 결과로 일반화하지 않는다. 상세 결과는 아래 실측 보고서를 참조한다.

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
6. 공유 host의 제한된 container stats 수집기 또는 전용 host의 opt-in `container-metrics`가 있다면
   Container CPU/Memory 패널을 확인한다. Hikari 패널은 짧은 포화를 보존하는 구간 최대이므로
   active와 pending 최대값이 반드시 같은 순간이라고 해석하지 않는다.
7. trace 상세의 Logs를 열어 같은 `trace_id` log만 남는지 확인한다.
8. `pyroscope.profile.id`가 있는 root span의 `Profiles for this span`을 열어 해당 span profile flame graph를
   확인한다. 짧은 span은 sampling 간격 때문에 profile이 없을 수 있으므로 DB lock 또는 Toss timeout처럼
   충분히 긴 제어 시나리오로 검증한다.

## 7. 정적 검증과 종료

견적 v3 배포는 [ADR-123](../adr/ADR-123-order-quote-trade-terms-and-shared-availability.md)을 따른다.
견적과 최종 주문을 같은 API 이미지로 교체한다. 기존 v2 미제출 견적은 stale 응답 후 재조회·명시적
재확인과 새 Idempotency-Key가 필요하다. terminal 응답 replay는 유지한다. 롤백도 반대 방향의
재조회가 필요하다. 사용량 제외는 자원 보장이 아니며 최종 재고/슬롯 부족 실패는 계속 관측해야 한다.

```bash
bash scripts/perf/test-observability-contract.sh
./scripts/verify-docs.sh
./gradlew spotlessCheck test bootJar
```

테스트 종료 후 k6와 fault injection을 중지하고 DB lock 해제, API health와 collector 상태를 확인한다.
계속 사용하는 perf 배포는 유지한다. 폐기할 전용 실험 stack만 해당 배포의 Doppler/Compose project와
overlay 순서로 내린다. 아래는 기본 전용 stack 예시다. volume 삭제는 fixture 보존/폐기 정책을 확인한
별도 승인 작업으로 수행하며 기본 종료에 포함하지 않는다.

```bash
docker compose --profile container-metrics -f compose.portfolio.yml -f compose.perf.yml down
```

결과에는 `Passed`, `Failed`, `Pending`, `Not run`을 구분해 적는다. 단일 실행이나 정적 검증으로 SLA,
처리 용량 또는 운영 안정성을 주장하지 않는다.

실제 [2026-09-07 부하 및 원인 분석](../quality/performance-load-rca-2026-09-07.md)은 견적 충돌,
Toss 지연, DB 잠금과 최초 JSON 직렬화 비용을 구분한 예시다. 짧은 실행의 최종 건수는 remote-write의
마지막 표본 대신 native summary를 사용한다. Tempo 검색 결과는 table 패널로 보고, 실행 ID 필터와
정확한 시간 범위를 선택한다.

## 참고

- [Grafana Tempo datasource correlation](https://grafana.com/docs/grafana/latest/datasources/tempo/configure-tempo-data-source/)
- [Tempo metrics-generator](https://grafana.com/docs/tempo/latest/metrics-from-traces/metrics-generator/)
- [Loki native OTLP ingestion](https://grafana.com/docs/loki/latest/send-data/otel/)
- [Pyroscope Java span profiles](https://grafana.com/docs/pyroscope/latest/configure-client/trace-span-profiles/java-span-profiles/)
- [k6 Prometheus remote write](https://grafana.com/docs/k6/latest/results-output/real-time/prometheus-remote-write/)
