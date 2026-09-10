# BeanFlow 성능 관측성과 부하 테스트 기반 구현

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-08`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

별도 VPN 부하 발생기에서 BeanFlow 핵심 거래 흐름을 재현하고, 기존 중앙 Prometheus 3.14,
Loki 3.7, Tempo 2.10, Pyroscope 2.2와 Grafana 13.2에서 사용자 지연·오류를 route부터 Hikari와
PostgreSQL wait, 실제 외부 Provider child span, 느린 trace, 같은 요청 log와 JVM wall flame graph까지
진단할 수 있게 한다. repository 설정만 통과한 상태와 실제 ingest/UI 검증을 구분한다.

## Current State

2026-09-08 저장소 반영 확인: 기반 PR #134와 후속 PR #135~#140이 병합됐고
`main`의 `8038e71`에서 CI, CodeQL과 이미지 build가 통과했다. 아래 배포·수집 상태는
각 검증 시점의 기록이며 이 문서 정리에서 서버 배포·부하를 다시 실행하지 않았다.
구현·실측 범위를 완료해 계획을 종료한다. 서비스 용량/SLO 및 남은 금융 publication 처리는
[잠금 구간 재측정의 한계와 후속 항목](../../quality/performance-lock-critical-section-retest-2026-09-08.md)을 따른다.

2026-09-07 실측 후속: 공유 자원 사용량으로 인한 견적 stale 정책 개선은
[공유 자원 변동에 안정적인 주문 견적](../completed/order-quote-shared-resource-stability.md)에서 구현·배포·재측정을 완료했다.
동일 5/s의 견적 충돌 37/450→0/450, 20/s의 실제 잠금 대기와 후속 범위는
[2026-09-08 재측정](../../quality/performance-quote-stability-retest-2026-09-08.md)에 기록했다.
관측성 자체의 범위와 거래 조건 변경을 구분하며 이 문서의 Non-goals를 소급 변경하지 않는다.

2026-09-07 후속 단계는 승인된 app/monitoring 서버 반영을 포함한다. 아래 초기 상태는 최초 구현 전
baseline이다. 현재 API와 네 scrape target은 정상이며 wall profile type 오류, 부하 실행 판정/비교 지표
누락, AIStor HTTPS/HTTP 불일치가 실측됐다. 기존 DB 재사용과 Doppler(app)/env(monitoring)를 유지한다.

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
- secret을 저장소에 기록
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
  [ADR-121](../../adr/ADR-121-performance-observability-and-trace-profile-correlation.md)의 이유로 제외한다.

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

- ADR-121
- 이 ExecPlan
- `docs/operations/performance-observability-runbook.md`
- 중앙 monitoring server 적용 checklist와 datasource provisioning snippets
- README의 perf entrypoint

## Progress

- [x] 2026-09-07: 승인된 기존 perf DB에 합성 fixture를 준비하고 외부 발생기에서 단계 부하·결제 실패 실행.
- [x] 2026-09-07: 각 실행의 Prometheus ingest, Grafana UI, trace/profile 증거와 원인 분석을 실측 보고서로 기록.

- [x] 2026-09-07: workflow 성공/거절/결과불명과 발생량 분리, 실제 k6 fixture server의 9개 경로 검증.
- [x] 2026-09-07: 실행 manifest·summary·target snapshot·비교·Grafana 시간 링크와 annotation 게시기 제공.
- [x] 2026-09-07: dashboard query/unit/profile 수정 및 JVM/DB/I/O/backlog 패널 배포.
- [x] 2026-09-07: app Doppler AIStor 주소와 검증된 image 반영, 제한된 Docker stats 수집 연결.
- [x] 2026-09-07: 기존 datasource runtime 백업·보존, 실제 query/profile sample/UI 검증.

- [x] 2026-09-02: repository 상태, 기존 metric/provider/deployment 경계 조사
- [x] 2026-09-02: 관측성 방식과 exact/correlation 경계를 ADR-121에 기록
- [x] 2026-09-04: 새 격리 worktree와 feature branch에서 P0/P1 전체 범위 재확인
- [x] 2026-09-04: application telemetry와 perf-only runtime 구현
- [x] 2026-09-04: perf infrastructure, P1 PostgreSQL 진단과 load scenarios 구현
- [x] 2026-09-04: dashboard, alert rules와 runbook 구현
- [x] 2026-09-04: repository 정적·단위·통합·build 검증과 결과 기록
- [x] 2026-09-04: cAdvisor의 privileged host/container 경계를 ADR-121에 기록하고 사용자 승인
- [x] 2026-09-04: cAdvisor opt-in Compose, scrape, dashboard와 contract 구현
- [x] 2026-09-07: perf Cursor HMAC binding과 native profiler tmpfs 수정, 실제 YAML/config tree 및 packaged perf startup 회귀 검증
- [x] 2026-09-07: exporter secret staging·권한 하강과 bridge 연결 수정, 서버 적용 및 중앙 네 개 target/Grafana 조회 검증
- [x] 2026-09-07: 승인된 perf 서버에서 중앙 ingest, Grafana correlation과 실제 부하 실행 검증

## Surprises & Discoveries

- 2026-09-07: 단일 공유 자원의 5 workflow/s에서 450건 중 48건이 ORDER_QUOTE_STALE이었다.
  예약 수/version이 fingerprint에 포함되어 금액과 메뉴가 같아도 다른 주문이 견적을 무효화했다.
  별도 두 견적/두 주문의 순차 재현으로 확인했다. ADR-116 변경은 이번 관측 작업에서 수행하지 않았다.
- 2026-09-07: Tempo 검색은 table frame인데 traces panel로 렌더링해 No data가 나왔다. table로 수정했다.
  또한 Grafana 15초 query step이 10초 scrape의 짧은 pool 포화를 놓쳐 구간 최대와 10초 간격으로 보완했다.
- 2026-09-07: 실제 PostgreSQL exporter의 `nobody`는 root 소유 DB secret을 읽지 못해 재시작했다.
  `internal` network에만 연결된 exporter는 port 설정이 있어도 host publish가 생성되지 않았다.
  격리 Docker 실험에서 동일 현상과 `egress` 연결 후 endpoint 응답을 재현했다.
- 2026-09-07: 중앙 Prometheus의 디스크 config에서 실행 중인 다른 서비스 job/rule이 누락돼 있었다.
  현재 config와 container mount의 rule을 보존해 BeanFlow 네 개 job을 추가하고 재시작 후 확인했다.
  Grafana reload 시 기존 datasource의 runtime JSON과 provisioning 파일 차이도 발견했다. 기존 파일이
  재적용됐으며 전체 datasource 연결 검증은 통과했다. reload 직전 기존 datasource JSON 백업이 없어
  이전 runtime 옵션의 완전한 보존은 주장하지 않는다. 향후 적용 절차에 사전 export·비교를 추가했다.
- 2026-09-07: `perf`에는 portfolio의 Cursor HMAC mapping이 없고 inherited `/tmp:noexec`는
  Pyroscope native library 로딩을 막았다. 기존 이미지 smoke가 portfolio만 시작해 두 결함을 놓쳤다.
- 기존 portfolio runtime은 Vault Proxy와 실제 AIStor를 이미 fail-closed로 사용한다. perf runtime도 이
  경계를 재사용해야 하며 별도 fake configuration을 만들면 안 된다.
- 기존 Toss sandbox configuration은 official HTTPS host만 허용한다. deterministic driver에는 production과
  portfolio에 겹칠 수 없는 별도 `toss-perf` profile과 startup guard가 필요하다.
- Orbit의 cAdvisor 구성은 `privileged`와 host root, Docker data, kernel 경로 mount를 요구한다. 전용 perf
  host가 아닌 곳에서는 blast radius가 크므로 명시적 보안 결정 없이 기본 Compose에 복제하지 않는다.

## Decision Log

- 2026-09-07: 고객·매장·메뉴·슬롯이 없는 DB에 전용 합성 데이터만 추가한다. 고객은 정상 가입/로그인을
  사용하며, 매장 fixture는 기존 엔티티와 DB 제약을 준수하는 단일 트랜잭션으로 준비한다. 기존 GLOBAL
  정책은 변경하지 않는다. 별도 Mac 발생기에서 공개 도메인을 경유해 1→5→10 workflow/s부터 측정한다.
  오류·pool 대기·메모리 증가 시 증량을 중단하며 fault injection은 정상 처리량 결과와 구분한다.
  단일 매장·소규모 데이터셋과 강한 telemetry 설정의 결과를 전체 서비스 capacity/SLO로 일반화하지 않는다.
- 2026-09-07: 공유 host에는 cAdvisor를 활성화하지 않고 배포 계정의 제한된 Docker stats 수집기를
  systemd로 운영한다. root 소유 unit/script 설치는 sudo 인증을 통해 적용했다. Doppler 읽기 전용
  배포 토큰의 쓰기 거절 이후 권한 보유자가 endpoint를 변경하고 배포는 검증만 수행했다.
- 2026-09-07: k6 remote write의 실제 1,000ms trend 입력이 값 1로 수집되는 것을 확인해 dashboard
  단위를 초로 수정했다. expected 422는 요청별 response callback과 body code를 함께 검증한다.
- 2026-09-07: `beanflow-*` datasource UID를 canonical source에도 적용하고 기존 default를 보존한다.
  reload 전 파일의 `$$` interpolation을 고려해 runtime 일치를 검증하고 reload 후 모든 datasource의
  주요 필드를 비교한다. 허용된 차이는 BeanFlow Tempo의 wall profile type 한 항목이다.

- 2026-09-07: 원본 DB secret의 권한을 유지하고 exporter 전용 tmpfs에 최소 권한 사본을 만든 뒤
  `nobody`로 실행한다. node/PostgreSQL exporter만 기존 `egress` bridge에 추가하며 Toss 격리는 유지한다.
  앱 서버 Doppler와 중앙 서버의 기존 env-file 기반 runtime 설정은 유지한다.
- 2026-09-07: perf 자체 HMAC mapping과 UID 10001 전용 exec tmpfs를 사용한다. 기존 profile guard,
  secret 검증, `/tmp:noexec`와 제품 transaction은 유지하고 실제 packaged perf startup을 배포 전 검증한다.
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

최신 실측은 [2026-09-07 부하 및 원인 분석](../../quality/performance-load-rca-2026-09-07.md)에 있다.
9회/809 workflow/2,686 HTTP 요청을 실행했고 중앙 ingest, annotation, 실제 Grafana 패널과 exact span
profile을 확인했다. 5/s에서 주문 충돌 10.7%로 증량을 중단했다. 18초 합성 DB 잠금은 pool 10/10,
pending 5를 만들었고 rollback 후 멱등성 46건 재검증이 통과했다. timeout 16건은 이후 APPROVED로
수렴했으며 지속 UNKNOWN 6건의 최종 manual review는 미검증이다. 전체 capacity/SLO는 주장하지 않는다.

아래는 구현 단계별 역사적 검증 기록이다. 이전 시점의 `Not run`은 위 최신 실측으로 갱신된 항목과
당시 제한을 구분해 읽어야 한다.

### 2026-09-07 후속 배포·대시보드 보완 결과

- Passed: API image `sha256:94979cf47cb2354f7152f4330725b324188090977bf6a81be89eb7c01ae9ddd6`
  배포 및 healthy 확인. 임시 additional config는 현재 runtime에서 제외됐고 기존 DB volume을 유지했다.
  실제 HTTP AIStor endpoint 반영 후 media availability 1, DB exporter scrape error 0을 확인했다.
- Passed: 중앙 scrape 5개 UP, 지정 project의 container 7개/49개 자원 표본, API memory limit
  2 GiB 확인. 공유 host의 cAdvisor는 활성화하지 않았다.
- Passed: dashboard 61개 항목(구획 포함), PromQL 83개 구문과 실제 조회, 7개 DB wait의 정상·연결 실패·
  scrape 실패·누락 경우 검증. wall profile은 실제 Grafana UI에서 non-zero flame graph로 표시됐다.
- Passed: 로컬 실제 k6의 성공·거절·결과불명·5xx·invalid JSON·멱등성 실패와 실행 wrapper 9개 경우,
  기존 계약/Toss 9개 tests 및 exporter/Compose/Alloy/rules 검증. k6 remote write는 별도 로컬 Prometheus의
  값·단위를 확인하고 중앙에 격리된 telemetry probe 1건을 보내 수집 경로를 확인했다.
- Passed: 실행 manifest/비교/annotation 유효성 tests, 실제 SSH annotation 게시기, target snapshot,
  문서 검증(18 tests, 53 policies, 122 ADRs, 905 Markdown files, 86 ExecPlans)과 diff check 통과.
  CI용 k6 2.2.0 Linux artifact의 다운로드·SHA-256은 확인했으며 원격 CI는 Not run이다.
- Passed: monitoring datasource runtime을 백업하고 기존 값 보존을 검증했다. monitoring env와 app Doppler
  경계를 유지했으며 수집/annotation용 credential 사본을 만들지 않았다.
- Not run: 실제 BeanFlow 인증 fixture를 사용한 거래 부하·capacity·장시간 회복 측정, 선택 span에 한정한
  profile의 end-to-end 인과 검증, public presigned image GET. 이 결과를 API healthy나 overview flame graph
  검증으로 대신하지 않는다. k6 실행별 수집은 각 manifest의 상태와 중앙 series로 별도 확인한다.

이하 내용은 후속 배포 전 각 단계의 검증 기록이며, 당시 Not run 상태를 현재 배포 상태로 해석하지 않는다.

- Passed (2026-09-07): `SignedCursorConfigurationTest`, `DeploymentSecretBindingTest`,
  `PerformanceProfileSafetyConfigurationTest`, `TossPerfPaymentGatewayConfigurationTest` — 15 tests,
  failures/errors/skipped 0. 수정 전에는 실제 perf YAML/config tree를 읽는 새 HMAC test가 실패했다.
- Passed (2026-09-07): `spotlessCheck`, `bootJar`, observability/deployment/backend-image contracts,
  shell syntax, `git diff --check`와 document/OpenAPI verification.
- Passed (2026-09-07): 현재 Dockerfile의 Linux/amd64 이미지 build 및 그 이미지에 대한
  `scripts/deploy/test-backend-entrypoint.sh`. 실제 PostgreSQL/PostGIS migration 71개, Vault TLS/AppRole,
  14개 missing/empty secret, AppRole 거부/Transit 권한 거부/sealed Vault 실패, portfolio 기동·재시작,
  perf config-tree HMAC, health/Prometheus와 전용 tmpfs의 native profiler JVM mapping을 검증했다.
  수정 전 `/tmp:noexec`에서 동일한 `UnsatisfiedLinkError`도 별도로 재현했다.
- Failed (2026-09-07, 예비 검증): 이전 runtime 이미지에 별도 host-built JAR를 교체한 실행은 portfolio
  검증 후 perf startup 검증에 실패했다. 이 실행은 통과 증거로 사용하지 않는다. 최종 검증은 현재
  Dockerfile로 새로 빌드한 이미지 내부 JAR에 대해 전체 경로가 통과한 결과다.
- Passed (2026-09-07): exporter runtime smoke의 missing/empty 실패, root 소유 `0600` 원본 유지,
  UID/GID 65534 프로세스와 `0400` tmpfs 사본, publish endpoint의 `pg_up 1` 및 scrape error 0.
  observability contract 전체 검증에도 포함했다.
- Passed (2026-09-07, 실제 서버): API/frontend healthy, exporter 수정 적용 후 네 개 중앙 target 모두 `UP`,
  PostgreSQL `pg_up 1` 및 scrape error 0. Grafana의 기존/BeanFlow datasource 8개 health 성공과
  datasource proxy를 통한 네 개 up series, BeanFlow log 37개(조회 시점 최근 2시간), 최근 trace 5개,
  조회 trace의 profile-link span 1개와 Pyroscope `service_name=beanflow`를 확인했다.
- Not run (2026-09-07): 새 API 이미지 서버 배포와 임시 startup override 제거, 실제 Grafana UI에서
  exemplar → trace → log → profile 이동, 실제 부하/용량 측정. 서버 API는 기존 이미지와 startup
  override를 유지한다. 격리 이미지 fixture의 collector export 비활성 검증과 실제 중앙 조회 결과를 구분한다.

- Passed: `./gradlew spotlessCheck test bootJar` — 48분 51초, 1,472 tests, failures 0, errors 0,
  skipped 2, bootJar 생성 성공.
- Passed: `bash scripts/perf/test-observability-contract.sh` — Compose merge, Alloy validate, Prometheus
  7 rules, Grafana JSON/YAML, k6/Toss 9 contract tests와 shell syntax 통과.
- Passed: `./scripts/verify-docs.sh` — 18 tests, 52 policies, 120 ADRs, 318 Markdown files,
  79 ExecPlans와 OpenAPI semantic checks 통과.
- Passed: cAdvisor는 기본 Compose service 목록에서 제외되고 `container-metrics` profile에서만 포함되며,
  선택형 Prometheus scrape, container CPU/memory dashboard와 target-down rule의 정적 계약이 통과했다.
  제한형 node-exporter는 CPU, memory, disk, network의 host signal만 read-only `/proc`와 `/sys`에서 수집한다.
- Not run: 전용 perf credential/fixture가 필요한 AIStor/Vault smoke, 중앙 Tempo/Loki/Pyroscope ingest,
  Grafana exemplar → trace → log → profile UI 확인, 실제 k6 부하/용량 측정과 전용 Linux perf host의
  privileged cAdvisor 기동·scrape.

## Revision Notes

- 2026-09-08: 미반영 검증 기록과 실제 PR 병합 결과를 정리하고 completed로 이동했다.
- 2026-09-07: 공개 경로 합성 부하, 제어된 Toss/DB 장애, 회복/멱등성, trace/profile 원인 분석과
  Grafana table/짧은 피크 렌더링 보완 결과를 기록했다.
- 2026-09-07: 실행 판정/재현성/단위/profile query를 보강하고 앱 새 이미지 및 중앙 dashboard를 실제
  배포했다. 공유 host의 제한된 container 수집, Doppler 권한 경계와 실제 검증/미실행 범위를 기록했다.

- 2026-09-07: 실제 서버 exporter 권한·network 결함 수정, Doppler/env-file 경계 유지와 중앙 수집 적용,
  Grafana 조회 증거 및 provisioning drift의 검증 한계 기록.
- 2026-09-07: perf startup의 HMAC mapping·native library mount 결함과 이미지 검증 누락을 수정하고,
  임시 보정 파일 제거 절차 및 재현/검증 결과 기록.
- 2026-09-06: main 병합 시 쿠폰 캠페인 ADR 번호와의 중복을 해소해 관측성 ADR을 ADR-121로 이동하고,
  AIStor 외부 호출 계측과 캠페인 배너 대상별 metric 및 페이지 단위 정리를 함께 보존.
- 2026-09-02: 최초 작성.
- 2026-09-04: P0/P1 전체 구현 범위와 SQL 개인정보 경계 보강.
- 2026-09-04: repository 구현과 검증 결과, cAdvisor 보안 결정 및 live 검증 경계 기록.
- 2026-09-04: 승인된 cAdvisor opt-in profile과 정적 계약 구현 결과 기록.
