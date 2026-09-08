# ADR-121: 성능 관측성과 trace-profile 상관 경계

- **Status:** Accepted
- **Date:** 2026-09-04
- **Implementation owner:** [BeanFlow 성능 관측성과 부하 테스트 기반](../exec-plans/completed/performance-observability-and-load-test-foundation.md)

## Context

BeanFlow에는 HTTP Actuator health와 다수의 Micrometer domain metric이 있지만 Prometheus registry,
분리된 management listener, OpenTelemetry trace, 구조화 log와 JVM profile 전송 계약이 없다. 따라서
사용자 지연이나 오류를 route에서 발견해 JDBC·외부 Provider 호출, 같은 요청의 log와 JVM flame graph로
이어지는 진단 경로가 없다. Hikari pool과 PostgreSQL lock도 같은 시간대에 비교할 수 있는 실행 가능한
대시보드가 없다.

성능 환경은 실제 Toss, AIStor와 Vault의 운영 자격 증명이나 데이터를 사용해서는 안 된다. Toss sandbox는
요청 한도와 외부 변동성 때문에 반복 가능한 부하 원천으로 사용할 수 없고, AIStor와 Vault를 in-memory
대체하면 실제 직렬화·TLS·network·Provider failure 경계를 측정할 수 없다.

## Decision

### 1. signal 소유권을 분리한다

- Micrometer와 Prometheus registry가 HTTP·domain·JVM·Hikari metric을 제공한다.
- OpenTelemetry Java agent가 inbound HTTP, JDBC와 outbound HTTP trace를 OTLP로 전송한다.
- Pyroscope OTel Java agent extension이 root span에 `pyroscope.profile.id`를 기록하고 wall-time profile을
  Pyroscope로 전송한다.
- Spring Boot ECS JSON stdout을 유지하고 OTel Logback appender가 native trace context와 검증된 perf MDC를
  OTLP log record로 함께 전송한다. Loki의 `trace_id`는 index label이 아닌 structured metadata다.
- 애플리케이션은 Prometheus Pushgateway에 요청 metric을 push하지 않는다. 지속 서비스 metric은 pull,
  k6 결과는 Prometheus remote write를 사용한다.

### 2. exact link와 correlation을 구분한다

- Prometheus exemplar에서 Tempo trace, Tempo trace에서 `trace_id` Loki log, profile sample이 존재하는
  Tempo span에서 Pyroscope flame graph로 이동하는 연결은 같은 trace에 대한 exact link다.
- 선택 trace의 JDBC와 Toss·AIStor·Vault child span도 같은 trace의 직접 증거다.
- Hikari metric과 PostgreSQL `pg_stat_activity`/`pg_locks`는 request trace와 같은 시간대·route·scenario의
  상관관계다. trace ID를 SQL comment, `application_name` 또는 metric label에 주입하지 않는다.
- 제어된 perf fixture lock 시나리오는 알려진 row와 시간 구간으로 인과를 재현하지만 임의 요청의
  PostgreSQL backend PID를 trace로 증명한다고 표현하지 않는다.

### 3. cardinality와 개인정보 경계를 고정한다

- metric label과 Loki index label은 route template, status class, service, environment, provider,
  operation과 닫힌 outcome만 사용한다.
- customer/order/payment/store/object ID, raw URL, SQL text, error message, token, credential와 개인정보는
  metric/profile label 또는 log에 넣지 않는다.
- perf `test_id`와 `scenario`는 검증된 bounded 값만 trace/log field로 기록하며 애플리케이션 metric
  label에는 넣지 않는다.

### 4. 성능 runtime을 기존 제품 runtime과 분리한다

- `perf` profile은 `local`, `toss-perf`, `vault-enforced`를 묶고 `prod`, `portfolio`, `test`,
  `local-demo`, `toss-sandbox`와 함께 활성화되면 시작을 거부한다.
- Toss는 perf network 내부의 계약 드라이버를 사용한다. load path의 success, decline, timeout, unknown과
  driver contract의 malformed, 5xx를 결정적으로 재현한다.
- AIStor와 Vault는 전용 bucket, Transit mount/key와 최소 권한 credential을 사용해 실제 요청한다.
- OpenTelemetry와 Pyroscope agent는 container image에 버전 고정하며 runtime flag로 명시적으로
  활성화한다. 비활성 상태를 telemetry 성공으로 위장하지 않는다.
- **Startup verification amendment (2026-09-07):** `perf`도 config tree의 필수 Cursor HMAC secret과
  active key ID를 자체 profile에서 연결한다. `portfolio` profile을 함께 켜거나 fixture key로 대체하지
  않는다. Pyroscope native library를 로드하기 위해 perf에 한해 JVM temporary directory를
  `/run/beanflow-jvm-tmp`로 지정한다. 이 tmpfs는 UID/GID 10001 전용 `0700`, 최대 128MiB,
  `exec,nosuid,nodev`이며 기존 `/tmp`와 secret tmpfs의 `noexec`는 유지한다. 이미지 배포 전 검증은
  실제 병합한 perf Compose의 JVM 옵션·tmpfs로 packaged application을 시작하고 health, Prometheus와
  agent 초기화 오류를 확인한다. 이 검증은 중앙 profile/trace/log ingest의 증거를 대신하지 않는다.
- **Exporter runtime amendment (2026-09-07):** PostgreSQL exporter는 root 소유 `0600` 배포 secret을
  startup에만 root로 읽고, 전용 `noexec,nosuid,nodev` tmpfs에 UID/GID 65534 전용 `0400` 사본을
  만든 뒤 `nobody`로 권한을 내려 실행한다. 원본 secret의 소유권·권한과 DB credential은 변경하지
  않으며, secret 누락·빈 값은 시작 실패다. node/PostgreSQL exporter는 사설 IP에 publish한 port가
  동작하도록 기존 `egress` bridge에도 연결한다. `backend`와 `observability`의 `internal` 설정과
  Toss driver의 내부 전용 연결은 유지한다. exporter에도 outbound 경로가 생기는 비용을 수용하며,
  monitoring host에서만 접근하도록 기존 host/network firewall 경계를 유지한다.

### 5. cAdvisor는 전용 perf host에서만 명시적으로 활성화한다

- 컨테이너별 CPU, memory와 filesystem signal이 필요한 실행에 한해 Compose의 `container-metrics`
  profile로 cAdvisor를 활성화한다. 기본 perf 기동에는 포함하지 않는다.
- cAdvisor가 요구하는 `privileged` 권한과 host root, `/var/run`, `/sys`, Docker data와 disk device의
  read-only mount는 전용이며 폐기 가능한 perf host에서만 허용한다. production, portfolio 또는 다른
  workload와 공유하는 host에서는 활성화하지 않는다.
- exporter port는 VPN bind address에만 publish한다. 중앙 Prometheus의 cAdvisor scrape job도 profile을
  활성화한 실행에서만 병합한다.
- container name/image 같은 bounded infrastructure label만 dashboard에서 사용하고 container ID나 host
  path를 애플리케이션 log/metric label에 복제하지 않는다.

### 6. 실행 판정과 공유 host 진단 (2026-09-07)

- k6는 시작·완료 workflow 수, 시나리오 기대 결과 일치율, 실제 주문 생성·결제 승인 수를 분리한다.
  `toss-decline`의 422만 해당 요청의 정상 응답으로 지정한다. UNKNOWN/RECONCILING은 승인 TPS에
  포함하지 않는다. 중간 요청 실패와 JSON 오류도 workflow 실패를 한 번 기록한다.
- 목표 arrival rate는 workflow/second이며 HTTP RPS와 다르다. p95/p99, VU, dropped iteration과
  전체 workflow 소요 시간을 별도 단위로 표시한다. 기본 threshold는 기존 임시 guardrail이고 SLO가 아니다.
- 실행 ID는 k6에만 붙이고 서버 metric에는 추가하지 않는다. 실행 manifest에 시작·종료, 코드/fixture
  digest, 시나리오·목표·threshold·발생기 정보를 보존하고 같은 시간의 서버 지표와 비교한다.
  baseline 비교는 manifest 조건이 같은 실행에 한하며 실행별 percentile을 평균내지 않는다.
- DB wait의 빈 결과를 0으로 표현할 때는 exporter `up`, `pg_up`, scrape error를 함께 검증한다.
  수집 실패는 0으로 대체하지 않는다. 프로파일 타입은 실제 ProfileTypes 응답의
  `wall:wall:nanoseconds:wall:nanoseconds`이며 non-zero stack sample까지 검증한다.
- 다른 workload가 있는 host에서는 cAdvisor를 켜지 않는다. 이미 Docker 관리 권한이 있는 배포 계정의
  systemd service가 지정 Compose project의 Docker stats/inspect만 읽어 CPU·memory·limit·restart를
  사설 port로 노출할 수 있다. 새 privileged container나 host filesystem mount를 추가하지 않는다.
  이 계정의 Docker daemon 권한 자체는 높은 권한이며 입력을 project/service allowlist로 제한한다.
  수집 실패 시 건강 상태와 수집 시각만 내보내고 이전 수치를 재사용하지 않는다.
- 동일 perf host의 실제 AIStor가 HTTP listener이면 명시적으로 그 HTTP endpoint를 설정한다.
  이는 TLS 실패에 대한 자동 downgrade가 아니다. 사설 host 통신에 한정하며 public presigned endpoint는
  별도로 HTTPS를 유지한다. 서버를 분리하거나 신뢰 경계가 바뀌면 내부 TLS를 재검토한다.

## Alternatives Considered

### Spring tracing starter와 별도 Pyroscope agent

일반 trace와 continuous profile은 만들 수 있지만 span-profile bridge가 없어 선택 trace에서 해당 JVM
profile로 이동할 수 없다. 두 SDK가 중복 초기화될 위험도 있어 채택하지 않았다.

### trace ID를 SQL comment 또는 connection application name에 기록

임의 request와 backend PID를 직접 연결할 수 있지만 SQL text·`pg_stat_statements` cardinality를 오염시키고
pool connection reset 누락 시 잘못된 상관관계를 만들 수 있어 채택하지 않았다.

### Toss sandbox와 AIStor/Vault fake만 사용

Toss sandbox는 반복 가능한 부하를 보장하지 않고 fake AIStor/Vault는 실제 dependency latency와 실패를
측정하지 못한다. Toss만 계약 드라이버로 격리하고 AIStor/Vault는 dedicated 실제 자원을 사용한다.

## Rationale

Java agent는 Spring MVC, JDK HTTP, JDBC를 소스 변경 없이 같은 trace context로 연결한다. Pyroscope
extension은 동일 trace/span ID를 profile sample에 기록해 지연 trace에서 wall-time flame graph로 이동하게
한다. metric, trace, log, profile 역할을 분리하고 bounded label만 사용하면 운영 질문을 답하면서도 중앙
관측성 서버의 cardinality와 개인정보 위험을 제한할 수 있다.

## Consequences

- Java agent와 sampling profiler의 CPU·memory·latency overhead를 동일 부하의 metrics-only 실행과
  trace/profile 실행으로 별도 측정해야 한다.
- sampling interval보다 짧은 span에는 profile이 없을 수 있다. trace-profile 계약은 충분히 긴 제어
  시나리오에서 검증한다.
- 중앙 Prometheus, Tempo, Loki, Pyroscope와 Grafana datasource 설정을 실제로 적용하기 전에는 정적
  repository 검증만 가능하며 진단 경로를 운영 가능하다고 주장하지 않는다.
- Hikari/DB lock 패널은 명시적으로 correlated evidence이며 exact request causality가 아니다.
- cAdvisor를 활성화하면 컨테이너가 host metadata를 광범위하게 읽을 수 있다. 해당 위험은 컨테이너별
  포화 원인 분리의 이점과 함께 명시적으로 수용하며, profile을 제거하면 제한형 node-exporter와 JVM
  metric만 남는 안전한 기본 상태로 돌아간다.

## Verification

- application context test로 perf profile 조합과 management endpoint 설정을 검증한다.
- adapter test로 provider operation/outcome metric과 span name이 bounded vocabulary만 쓰는지 검증한다.
- Compose와 Alloy 설정을 render/validate하고 Grafana dashboard JSON contract test를 실행한다.
- 제어된 smoke에서 route exemplar → Tempo trace → provider/JDBC span → Loki log → Pyroscope profile을
  실제 UI에서 탐색한다.
- DB lock 시나리오에서 긴 JDBC span, Hikari pending과 PostgreSQL lock wait가 같은 시간 범위에 나타나는지
  확인한다.

## Metrics

- HTTP request rate, status class와 duration histogram
- Hikari active, idle, pending, max
- PostgreSQL active wait, lock wait와 ungranted lock
- Toss·AIStor·Vault operation duration/count by bounded outcome
- k6 request/iteration/check/failure와 dropped iteration
- JVM CPU, heap, GC와 profile upload 상태
- 기본 host CPU/memory/I/O와 선택형 cAdvisor의 Compose service별 container CPU/memory

실측 전에는 alert threshold, SLA 또는 처리 용량을 결정하지 않는다.

## Revisit Conditions

- request와 PostgreSQL backend를 exact하게 연결해야 하는 장애가 반복될 때
- agent/profile overhead가 기준선 분석을 방해할 때
- 중앙 관측성 서버의 authentication, tenancy 또는 retention 계약이 바뀔 때
- 실제 Toss 부하 테스트 계약과 별도 quota가 마련될 때
- perf host가 전용·폐기 가능 환경이 아니게 되거나 rootless container metric 수집 방식이 마련될 때

## Related Decisions

- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-078](ADR-078-toss-payments-sandbox-gateway-adapter.md)
- [ADR-083](ADR-083-personal-data-encryption-and-blind-index.md)
- [ADR-115](ADR-115-store-and-menu-image-storage.md)
- [ADR-119](ADR-119-portfolio-deployment-runtime.md)

## 2026-09-08 perf 환불 계약 보완

앱이 생성하는 콜론 포함 환불 멱등키를 driver에서 수용한다. 공백 없는 printable ASCII 1~300자,
동일 key/payload 최초 응답 재생, 다른 payload 충돌, 잔여 취소 가능 금액 검증, 결제별 환불 reference
유일성과 confirmation replay 시 취소 이력 보존을 유지한다. 실제 Toss의 자동 fallback이 아니다.
검증은 [환불 계약 보고서](../quality/performance-perf-refund-contract-2026-09-08.md)에 기록한다.

## 2026-09-08 perf 결제 이력 보관 경계

기존 10,000개 초과 시 오래된 결제의 자동 삭제를 제거한다. 보관 한도는 50,000개이며
가득 차면 새 결제만 `503 DRIVER_CAPACITY_EXCEEDED`로 거절하고 기존 lookup·confirmation replay·
refund를 보존한다. 테스트용 `maxRetainedPayments`는 1~50,000 범위만 받는다.
process 재시작에는 명시적 snapshot/restore 절차가 필요하며 persistence로 오인하지 않는다.
검증은 [보관 한도 보고서](../quality/performance-driver-retention-2026-09-08.md)에 기록한다.
