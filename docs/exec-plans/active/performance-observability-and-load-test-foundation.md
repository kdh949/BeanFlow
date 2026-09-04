# BeanFlow 성능 관측성과 부하 테스트 기반 구현

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

별도 VPN 부하 발생기에서 BeanFlow 핵심 거래 흐름을 재현하고, 기존 중앙 Prometheus 3.14,
Loki 3.7, Tempo 2.10, Pyroscope 2.2와 Grafana 13.2에서 사용자 지연·오류를 route부터 Hikari와
PostgreSQL wait, 실제 외부 Provider child span, 느린 trace, 같은 요청 log와 JVM wall flame graph까지
진단할 수 있게 한다. repository 설정만 통과한 상태와 실제 ingest/UI 검증을 구분한다.

## Current State

- Spring Boot Actuator와 다수의 `beanflow.*` Micrometer metric은 있으나 Prometheus registry와 histogram
  publication 설정이 없다.
- health endpoint가 application port에 공개되어 있고 별도 management listener가 없다.
- OTel trace, JSON structured logging, Pyroscope profiling과 signal correlation 설정이 없다.
- 실제 Toss sandbox, MinIO-compatible AIStor와 Vault Transit adapter가 있고 external call은 DB transaction
  밖에서 실행된다.
- `compose.portfolio.yml`은 실제 배포 경계지만 관측성 collector, exporter와 반복 가능한 Toss driver가 없다.
- authenticated k6 핵심 흐름과 중앙 monitoring configuration contract가 없다.

## Definitions

- RED는 request Rate, Error, Duration이다.
- exemplar는 Prometheus histogram sample과 해당 Tempo trace ID를 연결하는 표본이다.
- exact link는 같은 trace ID 또는 span profile ID로 증명되는 연결이다.
- correlation은 같은 time range, route와 scenario에서 비교하는 Hikari/PostgreSQL 증거다.
- RCA mode는 bounded 시간 동안 trace와 wall profile을 강하게 수집하는 진단 실행이다.

## Scope

### In Scope

- Prometheus metric endpoint, bounded histograms, JSON log와 OTel/Pyroscope runtime
- order/quote와 Toss·AIStor·Vault provider span/metric
- perf Compose overlay, Alloy, PostgreSQL exporter와 deterministic Toss driver
- quote→fingerprint→order, board polling, provider, idempotent command header와 DB lock k6/diagnostic 시나리오
- 중앙 monitoring server 설정 조각, RCA dashboard, runbook와 contract tests
- `pg_stat_statements` preload/extension, query-text 없는 exporter 수집과 SQL 진단 패널
- 측정 전 threshold임을 명시한 사용자 영향·telemetry 중단 recording rules와 alert rules

### Non-goals

- 고객용 API/OpenAPI, Aggregate state, DB schema 또는 transaction boundary 변경
- 중앙 monitoring server에 직접 배포하거나 secret을 저장소에 기록
- 임의 alert/SLA/capacity 수치 결정
- trace ID의 SQL comment/application name 주입
- stress/spike/soak에서 capacity 결론 도출

## Business Rules and Invariants

- 주문 부하는 quote 응답의 `quoteFingerprint`와 동일 입력으로 order를 생성한다.
- 외부 Provider 호출은 장시간 DB transaction 안으로 이동하지 않는다.
- Toss transport/response 불명은 기존 `UNKNOWN`과 reconciliation 의미를 유지한다.
- AIStor PUT 실패는 HEAD 검증 없이는 성공으로 처리하지 않는다.
- Vault/AIStor/Toss 실패를 fake, cache, stale 또는 no-op 성공으로 대체하지 않는다.
- token, credential, PII, raw provider payload, SQL value와 identifier를 telemetry label/log에 남기지 않는다.

## Architecture and Transaction Boundaries

- Micrometer/Actuator가 Prometheus metric을 pull 방식으로 제공한다.
- OTel Java agent가 OTLP/gRPC로 local Alloy에 trace를 전송하고 Alloy가 중앙 Tempo로 전달한다.
- Pyroscope OTel extension이 profile을 중앙 Pyroscope로 직접 전송하고 trace span에 profile ID를 기록한다.
- application은 운영자가 로컬에서도 읽을 수 있는 ECS JSON stdout을 유지하고, OTel Java agent의
  Logback appender가 같은 log event와 trace context를 OTLP로 Alloy에 전달한다. Docker socket이나
  host log directory를 Alloy에 마운트하지 않는다.
- Tempo metrics-generator의 span-metrics/service-graphs는 중앙 Prometheus remote-write receiver로 보낸다.
- provider instrumentation은 기존 adapter 바깥 또는 안쪽에서 시간을 재지만 호출·transaction 순서는
  바꾸지 않는다.

## Alternatives Considered

- SDK 직접 초기화, Pushgateway 기반 application metric, SQL trace ID 주입과 fake Provider 전체 대체는
  [ADR-120](../../adr/ADR-120-performance-observability-and-trace-profile-correlation.md)의 이유로 제외한다.

## Failure Semantics

- 필수 perf endpoint/resource/config가 없으면 Compose interpolation 또는 startup guard가 실패한다.
- Alloy/Tempo/Pyroscope export 실패를 application transaction 성공/실패로 바꾸지는 않지만 collector 상태와
  dropped telemetry를 별도 metric/log로 노출한다.
- Toss driver timeout/malformed/5xx는 기존 payment UNKNOWN 결과로 수렴해야 한다.
- invalid perf correlation header는 perf profile에서 400이며 header가 없는 일반 요청은 그대로 처리한다.
- DB lock script는 perf DB와 allowlisted fixture가 아니면 실행하지 않고 bounded timeout 뒤 rollback한다.

## Data and Migration

Flyway migration은 없다. PostgreSQL `pg_stat_statements` extension/collector는 perf Compose와 운영 checklist가
소유하며 제품 schema 계약에 포함하지 않는다.

## API and Event Contracts

고객 API와 event contract 변경은 없다. perf profile에만 `X-BeanFlow-Test-Id`와
`X-BeanFlow-Scenario` request metadata를 허용한다. management port에는 health와 prometheus만 노출한다.

## Milestones

1. ADR/ExecPlan과 repository contract tests를 작성한다.
2. Prometheus, structured logging, OTel agent와 Pyroscope extension runtime을 구성한다.
3. bounded provider/route telemetry를 추가하고 단위·Spring test를 통과시킨다. (P0)
4. perf Compose, Alloy, PostgreSQL exporter와 Toss driver를 구성한다. (P0)
5. k6 핵심 시나리오와 DB lock/snapshot 도구를 구성한다. (P0)
6. RCA dashboard, central snippets, recording/alert rules와 runbook을 구성한다. (P0)
7. perf PostgreSQL의 `pg_stat_statements` preload/extension과 query text 없는 집계를 구성한다. (P1)
8. static/unit/integration/build 검증과 가능한 local telemetry smoke를 수행한다.

## Required Tests

- perf profile 조합, management endpoint와 metric exposure Spring test
- perf header validation/MDC/span attribute unit test
- provider operation/outcome vocabulary와 exception span status test
- Toss driver response/timeout contract test
- quote→fingerprint→order 및 polling k6 script contract test
- Compose secret, network, image, agent, collector/exporter contract test
- Alloy/Prometheus/Tempo/Grafana JSON/config와 Prometheus rule contract test
- 기존 ordering/payment/media/Vault regression test

## Validation Commands

    ./gradlew test --tests '*PerformanceTelemetry*' --tests '*TossPerf*'
    ./gradlew test --tests '*TossOneTimePaymentGatewayTest' --tests '*AistorStorefrontImageStorageTest' --tests '*VaultTransitPersonalDataAdapterTest'
    docker compose -f compose.portfolio.yml -f compose.perf.yml config
    bash scripts/perf/test-observability-contract.sh
    node --test scripts/load/*.test.mjs
    ./scripts/verify-docs.sh
    ./gradlew spotlessCheck test bootJar

실제 AIStor/Vault, 중앙 monitoring ingest와 Grafana UI 검증은 endpoint와 secret이 제공된 perf 서버에서만
실행하며 그렇지 않으면 `Not run`으로 기록한다.

## Observability

운영 질문은 다음 네 가지다.

1. 어느 route와 status class의 p95/p99가 증가했는가?
2. 선택한 느린 trace의 시간이 JDBC, Toss, AIStor, Vault 또는 application code 중 어디에 쓰였는가?
3. 같은 시간대에 Hikari pool saturation 또는 PostgreSQL lock/wait가 있었는가?
4. 동일 trace log의 closed failure reason과 wall flame graph가 무엇을 보여 주는가?

RCA dashboard와 data source correlation은 이 순서를 직접 탐색하게 한다.

## Documentation Updates

- ADR-120
- 이 ExecPlan
- `docs/operations/performance-observability-runbook.md`
- 중앙 monitoring server 적용 checklist와 datasource provisioning snippets
- README의 perf entrypoint

## Progress

- [x] 2026-09-02: repository 상태, 기존 metric/provider/deployment 경계 조사
- [x] 2026-09-02: 관측성 방식과 exact/correlation 경계를 ADR-120에 기록
- [x] 2026-09-04: 새 격리 worktree와 feature branch에서 P0/P1 전체 범위 재확인
- [x] 2026-09-04: application telemetry와 perf-only runtime 구현
- [x] 2026-09-04: perf infrastructure, P1 PostgreSQL 진단과 load scenarios 구현
- [x] 2026-09-04: dashboard, alert rules와 runbook 구현
- [x] 2026-09-04: repository 정적·단위·통합·build 검증과 결과 기록
- [x] 2026-09-04: cAdvisor의 privileged host/container 경계를 ADR-120에 기록하고 사용자 승인
- [ ] cAdvisor opt-in Compose, scrape, dashboard와 contract 구현
- [ ] 전용 perf 서버에서 중앙 ingest, Grafana correlation과 실제 부하 실행 검증

## Surprises & Discoveries

- 기존 portfolio runtime은 Vault Proxy와 실제 AIStor를 이미 fail-closed로 사용한다. perf runtime도 이
  경계를 재사용해야 하며 별도 fake configuration을 만들면 안 된다.
- 기존 Toss sandbox configuration은 official HTTPS host만 허용한다. deterministic driver에는 production과
  portfolio에 겹칠 수 없는 별도 `toss-perf` profile과 startup guard가 필요하다.
- Orbit의 cAdvisor 구성은 `privileged`와 host root, Docker data, kernel 경로 mount를 요구한다. 전용 perf
  host가 아닌 곳에서는 blast radius가 크므로 명시적 보안 결정 없이 기본 Compose에 복제하지 않는다.

## Decision Log

- 2026-09-02: 사용자는 기존 중앙 monitoring stack을 유지하고 BeanFlow repository가 application-side
  integration과 적용 가능한 server snippets를 소유하도록 선택했다.
- 2026-09-02: load generator는 별도 VPN host, Toss는 deterministic driver, AIStor/Vault는 dedicated 실제
  자원을 사용한다.
- 2026-09-02: trace-profile exact link를 포함하고 Hikari/PostgreSQL lock은 time-window correlation으로
  정직하게 표시한다.
- 2026-09-04: P1 SQL 진단은 제품 migration 없이 perf PostgreSQL의 preload/extension과 bounded aggregate로
  제한하고 query text, bind value와 identifier를 중앙 telemetry에 전송하지 않는다.
- 2026-09-04: 사용자는 cAdvisor의 privileged host 접근 위험을 확인하고, 전용·폐기 가능 perf host에서만
  기본 비활성 `container-metrics` profile로 사용하는 구성을 승인했다.

## Outcomes & Retrospective

- Passed: `./gradlew spotlessCheck test bootJar` — 48분 51초, 1,472 tests, failures 0, errors 0,
  skipped 2, bootJar 생성 성공.
- Passed: `bash scripts/perf/test-observability-contract.sh` — Compose merge, Alloy validate, Prometheus
  7 rules, Grafana JSON/YAML, k6/Toss 9 contract tests와 shell syntax 통과.
- Passed: `./scripts/verify-docs.sh` — 18 tests, 52 policies, 120 ADRs, 318 Markdown files,
  79 ExecPlans와 OpenAPI semantic checks 통과.
- In progress: 승인된 cAdvisor를 기본 비활성 `container-metrics` profile로 구현한다. 제한형
  node-exporter는 CPU, memory, disk, network의 host signal만 read-only `/proc`와 `/sys`에서 수집한다.
- Not run: 전용 perf credential/fixture가 필요한 AIStor/Vault smoke, 중앙 Tempo/Loki/Pyroscope ingest,
  Grafana exemplar → trace → log → profile UI 확인과 실제 k6 부하/용량 측정.

## Revision Notes

- 2026-09-02: 최초 작성.
- 2026-09-04: P0/P1 전체 구현 범위와 SQL 개인정보 경계 보강.
- 2026-09-04: repository 구현과 검증 결과, cAdvisor 보안 결정 및 live 검증 경계 기록.
