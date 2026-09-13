# Grafana에서 병목 후보를 좁히기 위한 관측성 준비

작성일: 2026-09-13 KST. 코드 기준: `503d15831cc3b775bb073c8bcefb534ad89f36a0`.
이 문서는 [안정 처리량 검증 ExecPlan](../exec-plans/active/performance-capacity-and-journey-load-validation.md)의
M0 관측성 gate와 M7 조사 기록 계약이다. O1~O6의 저장소 구현은 2026-09-13에 반영했지만,
새 revision의 배포와 실제 Grafana 수집·표시 smoke는 별도 상태로 남긴다.

## 목표와 증거 경계

부하 중 이상을 Grafana에서 발견하고, 같은 시간의 Dashboard/Explore에서 후보를 좁힌 뒤 코드를 확인한다.
완료 기준은 다른 실행자가 같은 시간·필터·쿼리·trace로 판단 과정을 재현할 수 있는 것이다.
미리 정한 코드 원인을 관측 결과처럼 쓰거나, 나중에 발견한 증거를 먼저 본 것처럼 기록하지 않는다.
이번 문서는 코드·설정과 현재 수집을 대조한 준비 상태 감사이며, 실제 부하 병목을 발견한 보고서가 아니다.

모든 장애의 코드 원인을 Grafana만으로 확정할 수 있다는 보장은 하지 않는다.
필요한 신호가 없으면 `OBSERVABILITY_GAP`으로 기록하고 계측을 보완한 뒤 같은 조건을 다시 측정한다.
직접 SQL/SSH와 소스 분석은 후보 확인 및 정합성 검증에 사용할 수 있지만 Grafana 관측을 대체하지 않는다.

## 현재 확인한 것

2026-09-13 03:45~03:47 KST의 read-only 확인 결과다. 부하·장애 주입은 실행하지 않았다.

| 범위 | 결과 | 해석 한계 |
| --- | --- | --- |
| [Live Operations](http://172.16.16.18:3000/d/beanflow-live-operations) | 환경 perf, 호스트 172.16.16.22에서 API 수집 중, 현재 요청 약 0 req/s, Hikari pending 0 표시 | 유휴 시점이다. 처리량·병목·회복 통과 증거가 아님 |
| 중앙 Prometheus target | API/PostgreSQL/node/container/Alloy 5개 `up=1`; PostgreSQL `pg_up=1`, scrape error=0 | exporter 생존과 개별 collector/OTLP 종단 수집은 별개 |
| Grafana Explore, BeanFlow Prometheus | 최근 15분 `count by(__name__) ({job="beanflow-postgres",__name__=~"pg_stat_statements.*"})`가 **No data** | SQL 통계 수집 부재 원인은 아직 미확정. SQL이 빠르다는 뜻이 아님 |
| 중앙 Prometheus의 실행기 지표 | `applicationTaskExecutor`, `taskScheduler`의 `executor_*`, Hikari connection usage 지표 존재 | 현재 두 대시보드의 주요 진단 패널에는 노출되지 않음 |
| 중앙 Prometheus의 Alloy 지표 | queue size/capacity, send_failed/refused의 trace/log 지표 존재; 확인한 최근 15분 실패·거부 증가 0 | 성공 응답한 저장소에서 검색/보존/profile 연결까지 보장하지 않음 |
| DB 선택 | `beanflow`와 `beanflow_perf_20260913` 모두 DB 통계 시계열에 존재 | DB 변수 선택만으로 앱 DB 및 custom wait collector 대상이 같다고 보장하지 못함 |
| trace → log → profile 전체 연결 | 이번 감사에서 재검증하지 않음 | 준비 smoke에서 별도 입증해야 함 |

현재 마련된 기반은 HTTP route histogram/exemplar, JDBC 자동 span, Toss·AIStor·Vault의
operation/outcome timer와 span, Hikari/JVM/host/container 지표, 일부 업무 timer와 복구 지표다.
데이터를 전부 새로 만드는 작업보다 **누락 복원, 기존 신호의 화면 연결, 부족한 경계 계측**이 먼저다.

## 2026-09-13 구현 상태

| 항목 | 저장소 구현·격리 검증 | 실제 perf 배포·Grafana 표시 |
| --- | --- | --- |
| O1 SQL 통계/DB 식별 | exporter runtime에서 실제 `pg_stat_statements_*` 증가와 DB identity 확인 | **NOT_RUN** |
| O2 blocker/snapshot | transactionid wait, 다른 DB 제외, bounded/redacted snapshot unit·runtime 확인 | **NOT_RUN** |
| O3 범위/전송 상태 | environment/host/database query 계약, Alloy queue와 실패 패널, datasource mapping 반영 | **NOT_RUN** |
| O4 worker/freshness | 9개 실제 owner의 닫힌 상태 backlog와 worker run/item/freshness 계측 구현 | **NOT_RUN** |
| O5 내부 단계/실행기 | 주문·결제·점주 전이 bounded phase와 task executor/Tomcat 분리 패널 구현 | **NOT_RUN** |
| O6 앱 host/container | throttling/OOM 지원 여부, allowlisted filesystem, 수집 실패 상태 구현 | **NOT_RUN** |

새 주요 metric 계약은 다음과 같다. owner/state/outcome/operation/stage는 ADR-121의 닫힌 사전만 사용한다.

| 영역 | metric | 의미 |
| --- | --- | --- |
| DB | `beanflow_pg_database_identity_info`, `beanflow_pg_blocked_sessions`, `beanflow_pg_lock_wait_max_seconds` | 선택 DB identity와 현재 blocker 상태 |
| DB snapshot | `beanflow_db_diagnostics_collection_success`, `beanflow_db_diagnostics_log_export_success`, `beanflow_db_diagnostics_last_success_timestamp_seconds`, `beanflow_db_diagnostics_snapshot_truncated` | DB 읽기와 OTLP 로그 전송을 분리한 collector 상태 |
| worker backlog | `beanflow_worker_backlog_items`, `beanflow_worker_backlog_oldest_due_age_seconds` | DB 전체의 owner/state/claimability별 수와 실제 claim 가능한 가장 오래된 due age |
| worker runtime | `beanflow_worker_runs_total`, `beanflow_worker_items_total`, `beanflow_worker_data_last_success_timestamp_seconds`, `beanflow_worker_business_last_success_timestamp_seconds` | 실행 결과·처리 결과·데이터/업무 성공 신선도 |
| worker latency | `beanflow_worker_enqueue_to_claim_duration_seconds`, `beanflow_worker_claim_to_outcome_duration_seconds` | 대기와 실제 claim부터 terminal outcome까지를 분리한 histogram |
| request phase | `beanflow_operation_phase_duration_seconds` | operation/stage/outcome별 bounded 내부 단계 histogram |
| app host | `beanflow_container_cpu_throttled_periods_total`, `beanflow_container_oom_killed`, `beanflow_container_signal_supported`, `beanflow_host_filesystem_*` | throttling/OOM 지원 여부와 allowlisted filesystem 상태 |

## 보완 순서와 완료 조건

P0는 M2 기준선 이전 필수다. P1은 관련 M3/M4 단건 smoke 이전에 구현하고 M5 증량 전에 통과한다.
P2는 해당 영역이 실험 범위이면 증량 전에 준비한다. 빠진 필수 신호를 후속 코드 추측으로 통과시키지 않는다.

### O1 · P0 — SQL 통계 복원과 DB 대상 식별

**현재:** RCA 패널 130/131은 `pg_stat_statements_seconds_total`과 `calls_total`을 사용한다.
위 live 확인에서는 metric family 자체가 없었다. `compose.perf.yml`에는 collector 활성화가 있지만
배포 collector flags, 현재 DB extension/권한, exporter 버전 및 필터 상태까지 확인한 것은 아니다.

**작업:**

1. Explore의 같은 시간 범위에서 `pg_up`, scrape error, 해당 metric family 부재를 기록한다.
2. 배포의 collector flags와 exporter 로그, 연결 DB의 extension/통계 접근 권한,
   실제 `/metrics` 이름·label 및 수집 필터를 읽어 누락 원인을 구분한다. extension 누락으로 미리 단정하지 않는다.
3. 앱 연결 DB, exporter 연결 DB와 패널 database가 일치하도록 target snapshot과 연결한다.
   현재 custom wait/lock에는 `datname` label이 없으므로 명시적인 대상 DB 식별을 추가한다.
4. 기존 stat-statements collector를 복원한다. 버전에 따른 실제 이름/label로 쿼리와 테스트를 함께 맞춘다.
   raw SQL·bind parameter를 Prometheus label이나 Loki로 보내는 대안은 사용하지 않는다.
5. 관측 준비용 단건 호출 뒤 호출 수와 실행시간이 증가하고, RCA 130/131에서 선택 DB의 series를 볼 수 있어야 한다.
   순간 snapshot으로 평균을 구하지 않고 양의 calls rate가 있는 구간만 평균을 표시한다.

**변경 후보:** `compose.perf.yml`, `infra/observability/postgres-queries.yaml`, 실제 배포 collector 설정,
`infra/observability/central/grafana/dashboards/beanflow-performance-rca.json`,
`scripts/perf/test-dashboard-queries.py`. 원인 확인 전 DB 설정/extension을 임의 변경하지 않는다.

**완료 증거:** exporter 실제 metric 이름/label 목록, DB binding, Explore 쿼리와 절대 시간 링크,
실제 호출 이후 130/131 표시. 빈 결과를 0으로 채우면 실패다.

### O2 · P0 — 잠금 누락 수정과 blocker 관측

**현재:** `postgres-queries.yaml`의 ungranted 집계는 `pg_locks.database = 현재 DB oid`만 센다.
transaction ID 잠금은 database가 NULL이므로 빠질 수 있다. row lock 대기가 transactionid로
나타날 수 있다는 점도 [PostgreSQL 17 문서](https://www.postgresql.org/docs/17/view-pg-locks.html)에 명시돼 있다.
RCA 5는 wait 종류를 보여 주지만 6만 보고 잠금이 없다고 결론 내릴 수 없다.

**작업:**

1. 대기 세션 `pg_stat_activity.datname`으로 대상 DB를 한정하고 그 PID의 ungranted lock을 센다.
   `OR database IS NULL`로 전체 클러스터의 다른 DB 잠금까지 섞지 않는다.
2. `pg_blocking_pids(waiter.pid)`를 이용해 blocked session 수, 최고 lock wait 시간,
   최고 transaction age와 idle-in-transaction 세션 수를 추가한다. locks 수와 blocked sessions 수는 별도 단위다.
3. Explore에서 blocker를 좁힐 수 있도록 제한된 진단 snapshot을 추가한다.
   10초 간격·최대 10개 waiter·1초 query timeout을 초기 예산으로 두고 collector 비용을 측정한다.
   DB 집계와 snapshot은 business transaction 밖 read-only connection에서 수집한다.
4. snapshot에는 관측 시각, DB, waiter/blocker PID, state, wait type/event, query ID,
   transaction age와 확인 가능한 relation만 둔다. PID/query ID는 Loki 비색인 필드에만 둔다.
   relation은 해당 DB에서 확인되는 경우만 기록하고 transactionid만으로 테이블을 추정하지 않는다.
5. 원문 SQL 대신 제한된 `query_family` 사전(픽업 counter, 슬롯 예약 등)으로 분류할 수 있다.
   매핑 실패는 `unmapped`다. 원문·literal·고객/주문 식별자를 로그에 보내지 않는다.
   수집 경로와 필드는 구현 전에 ADR-121의 bounded amendment에 기록한다.
6. collection success/last success와 snapshot 잘림 여부를 함께 표시한다. 실패한 수집은 빈 잠금 목록으로 대체하지 않는다.

**변경 후보:** `postgres-queries.yaml`, 신규 `scripts/perf/db-diagnostics-exporter.py`,
`infra/observability/alloy/config.alloy`, 두 dashboard와 query contract tests.
신규 collector의 배치·전송·권한은 M0 배포 설계에 명시하고 현재 파일로 간주하지 않는다.

**검증:** 격리 PostgreSQL에서 두 connection의 transactionid wait를 재현해 0 초과를 확인한다.
다른 DB waiter 제외, 긴 transaction, idle-in-transaction, collector 실패/stale를 검증한다.
그 뒤 허용된 perf fixture에 짧은 잠금 smoke를 수행하고 Grafana에서 발견→해제→회복을 확인한다.
HTTP trace와 PostgreSQL PID의 직접 연결은 증명하지 않는다. SQL comment/application_name에 trace ID를 넣지 않는다.

### O3 · P0 — 대시보드 범위와 수집 파이프라인 상태

**현재:** Live Operations에는 environment/host가 있지만 RCA는 route/test_id/baseline_test_id/database만 있다.
서버 쿼리 다수가 `job="beanflow"` 전체를 집계한다. test_id 선택도 서버 metric을 해당 실행으로 필터하지 않는다.
RCA 145의 `up=1`은 Alloy가 Tempo/Loki에 정상 전송·저장한다는 증거가 아니다.

**작업:**

1. RCA에도 environment/host를 추가하고 HTTP/Hikari/JVM/DB/container/Alloy 쿼리와 data link에 전파한다.
   같은 시간 링크에 DB/test_id를 명시적으로 선택하게 한다. 이관 전 DB를 기본값으로 고정하지 않는다.
2. Tempo/Loki/profile에도 현재 배포를 구분할 bounded resource 식별자를 일관되게 전달한다.
   실제 resource label을 먼저 조회하고 metric의 instance 문자열이 그대로 존재한다고 가정하지 않는다.
3. Alloy의 전송 실패·거부 증가와 exporter queue size/capacity를 dashboard에서 보여 준다.
   아래 Explore 쿼리로 이미 수집되는 값을 재사용한다. 존재하지 않는 metric은 0으로 대체하지 않는다.
4. 오래된 표본, target 자체 부재, collector 실패와 무요청을 구별한다.
   Hikari 획득 대기 등의 평균도 관측 count가 0이면 지연 0으로 표시하지 않는다.
5. 짧은 smoke에서 실제 exemplar → Tempo의 JDBC/Provider span → 해당 trace의 Loki →
   sample이 있는 Pyroscope를 클릭해 확인한다. sample이 없는 짧은 trace 하나로 전체 profile 실패를 단정하지 않는다.

**변경 후보:** RCA/Live JSON, datasource provisioning, Compose resource attributes,
`beanflow-performance.rules.yml`, dashboard query tests. 기존 sample ratio/agent 버전과 비용을 기록한다.

**완료 증거:** 두 environment/host fixture의 교차 혼입 방지 테스트, 실제 필터된 패널,
동일 trace의 log/profile 연결, Alloy 전송 경로의 정상/실패 표시. `up`만 확인하면 미완료다.

### O4 · P1 — 비동기 owner와 데이터 신선도

**현재:** `EventPublicationRecoveryWorker`는 전역 pending/oldest를 갱신하고,
`ReservationExpiryWorker`의 due count는 마지막 batch 후보 수(기본 최대 100)다.
`PaymentMetrics`, `OrderIdempotencyMetrics`에도 자체 last-success가 없다.
worker 정지 시 scrape는 성공하면서 이전 gauge가 계속 노출될 수 있다.
event pending에는 미구현 Analytics target도 들어가므로 총량으로 현재 복구 병목 owner를 판별하기 어렵다.

**작업:**

1. publication recovery, payment reconciliation, reservation expiry, acceptance timeout,
   refund/benefit restoration, notification의 실제 worker/owner 목록을 M1에서 확정한다.
2. 각 실행의 성공/부분 실패/실패 횟수, 실행 시간, 마지막 시작/성공 시각을 기록한다.
   데이터 조회 성공 시각과 business 처리 성공 시각을 분리한다. 일부 실패한 batch를 전부 성공 처리하지 않는다.
3. 활성 owner별 due 수·가장 오래된 due age, 미래 scheduled 수, in-progress/unknown/manual-review 수를 표시한다.
   미구현 target은 별도 범주로 보존한다. batch size를 전체 backlog라고 이름 붙이지 않는다.
4. 완료·실패 처리량과 enqueue→claim 대기, 실제 claim→terminal outcome 시간을 분리한다.
   batch claim owner는 batch claim 반환 직후의 공통 기준시각을 사용해 뒤 항목의 순차 대기를 포함한다.
   lease가 없는 owner는 claim latency를 만들지 않고 item outcome만 기록한다.
   owner별 지표는 폐쇄된 owner/state/outcome enum만 label로 사용한다.
5. 비동기 작업에는 명시적인 작업 span과 안전한 correlation 필드를 기록한다.
   현재 perf HTTP MDC가 영속 이벤트·재시작 후 worker까지 자동 전파된다고 가정하지 않는다.
   최초 HTTP trace 연결이 없는 경우 owner/state/time 상관관계까지만 주장한다.
6. scrape 성공이어도 refresh 시각이 `2 × 갱신 주기 + scrape 주기`를 넘으면 stale로 표시한다.
   갱신 실패 시 이전 값과 실패 상태를 함께 남기며 backlog=0으로 덮지 않는다.

**변경 후보:** 위 worker/metrics 클래스와 해당 Query Repository, `PaymentReconciliationWorker.kt`,
`AcceptanceTimeoutWorkWorker.kt`, owner별 실제 구현, 두 dashboard. 새 계측 이름·단위·상태 사전은
구현 전에 ADR-121에 기록하고, 제품 retry/deadline/transaction은 바꾸지 않는다.

**검증:** worker 예외·부분 실패·한 batch보다 큰 backlog·미래 due·미구현 target 혼재를 격리 DB에서 재현한다.
M3/M4 단건 거래의 owner 처리/회복이 Grafana에서 구별돼야 장시간 실행을 시작할 수 있다.
주문별 금액·원장 정합성은 별도 verifier로 대조한다. Grafana 집계가 원장 검증을 대체하지 않는다.

### O5 · P1 — 요청 내부 단계와 실행기 대기

**현재:** 전체 주문 생성/견적/결제/보드 timer, pickup 순번 timer와 JDBC/외부 span은 있다.
하지만 멱등 등록, quote 재검증, 자원 예약, snapshot/저장/commit의 일관된 업무 단계 span은 없다.
전체 요청이 느려도 JDBC 실행·connection 획득·애플리케이션 계산 중 어디가 긴지 분리가 어려울 수 있다.

**작업:**

1. 기존 quote/create/payment/board timer와 `hikaricp_connections_usage_seconds_*`,
   `executor_active_threads`, `executor_queued_tasks`, pool/max를 먼저 Explore와 패널에 연결한다.
   Spring task executor 지표를 HTTP 수신 thread pool 지표로 설명하지 않는다.
2. `CreateOrderService`의 idempotency 등록, `OrderCreationTransaction` proxy 호출 전체,
   `OrderQuoteCoordinator`, `OrderCreationWorkflow`의 예약/순번/snapshot 경계에 bounded 내부 span을 둔다.
   결제 승인과 점주 transition도 준비/외부 호출/결과 반영 경계를 같은 원칙으로 계측한다.
3. 실제 트랜잭션 proxy **밖**에서 잰 전체 시간만 commit 포함 시간이라고 부른다.
   메서드 내부 timer를 flush/commit timer로 바꿔 부르지 않는다. 하위 JDBC와 부모 span 시간을 합산하지 않는다.
4. 필요한 단계 timer에만 histogram을 설정한다. 여러 instance의 client-side p95를 평균하지 않는다.
   label은 operation/stage/outcome만 사용하고 key/주문/매장 ID를 넣지 않는다.
5. 실제 HTTP executor 종류를 확인한 뒤 busy/max/queue/rejection을 관측한다.
   Tomcat/virtual-thread 여부를 확인하지 않고 존재할 것으로 예상한 metric을 panel에 넣지 않는다.

**완료 증거:** 정상 단건과 제어된 지연의 Tempo waterfall에서 어느 단계가 길어진지 식별,
오류에도 span 종료/MDC 정리, transaction 결과 불변, 추가 계측 overhead 비교.
phase 계측은 모든 repository 메서드에 일괄 적용하지 않는다.

### O6 · P2 — 앱 서버 자원 제한의 사각지대

**현재:** 앱 서버의 CPU/메모리/재시작 패널이 있다.
Docker stats exporter는 CPU throttled time/period와 OOM 이벤트를 내보내지 않는다.
node exporter의 명시 collector 목록에는 filesystem이 없다. 따라서 디스크 I/O busy만으로 가용 공간을 알 수 없다.

**관측 범위:** O6는 앱 서버 host/container의 자원 사용량과 제한에 집중한다.
부하 발생기의 자원·네트워크 관측, k6 전송 단계 시간·dropped/VU의 Grafana 연결,
WAF/proxy 로그 수집·연결과 ingress 전용 패널은 이번 구현 및 O6 완료 조건에서 제외한다.
k6 원본 결과는 부하 입력·결과 및 실행 유효성 기록에 사용한다. 앱 관측만으로 발생기 한계나 WAF 지연·차단 원인을 확정하지 않는다.

**작업:**

- 앱 서버의 기존 CPU/메모리/재시작 지표를 같은 environment/host와 실행 시간 범위로 연결한다.
- CPU quota가 있는 container는 Docker API가 제공하는 throttling과 OOM 상태를 기존 exporter에 추가하고,
  filesystem available/size를 승인된 앱 서버 host 경로에서 수집한다. 공유 host에서 privileged cAdvisor를 새로 켜지 않는다.
  데이터가 없는 환경에서는 `unsupported`/`unavailable`을 표시하고 0으로 대체하지 않는다.

**완료 증거:** 앱 서버의 CPU/메모리 사용량·재시작과 filesystem guardrail 확인 가능,
할당된 CPU와 실제 throttling 구별, 지원되는 환경의 OOM 상태 확인 가능.
관측 범위 밖 구간의 원인은 미확정으로 기록한다.

## 지금 복사해서 확인할 Explore 쿼리

Data source는 **BeanFlow Prometheus**, 시간은 실행의 절대 시작/종료로 고정한다.
아래 host/environment는 이번 확인값이다. 다른 배포에서는 manifest 값으로 바꾼다.
각 code block은 별도 query다. 결과가 비어 있으면 count=0이라고 기록하지 않는다.

```promql
count by (__name__) ({job="beanflow-postgres",environment="perf",instance="172.16.16.22:19187",__name__=~"pg_stat_statements.*"})
```

```promql
{job="beanflow-postgres",environment="perf",instance="172.16.16.22:19187",__name__=~"up|pg_up|pg_exporter_last_scrape_error|beanflow_pg_wait_sessions|beanflow_pg_ungranted_locks"}
```

```promql
executor_queued_tasks{job="beanflow",environment="perf",instance="172.16.16.22:18081"}
```

```promql
rate(hikaricp_connections_usage_seconds_sum{job="beanflow",environment="perf",instance="172.16.16.22:18081"}[$__rate_interval])
/
(rate(hikaricp_connections_usage_seconds_count{job="beanflow",environment="perf",instance="172.16.16.22:18081"}[$__rate_interval]) > 0)
```

```promql
otelcol_exporter_queue_size{job="beanflow-alloy",environment="perf",instance="172.16.16.22:12345"}
/
(otelcol_exporter_queue_capacity{job="beanflow-alloy",environment="perf",instance="172.16.16.22:12345"} > 0)
```

```promql
sum by (exporter) (increase(otelcol_exporter_send_failed_spans_total{job="beanflow-alloy",environment="perf",instance="172.16.16.22:12345"}[5m]))
```

로그는 마지막 query의 metric 이름을 `otelcol_exporter_send_failed_log_records_total`로 바꿔 확인한다.
전송 실패에는 재시도 가능한 실패도 포함되므로 실패 횟수를 영구 누락 수로 부르지 않는다.
[Alloy OTLP exporter](https://grafana.com/docs/alloy/latest/reference/components/otelcol/otelcol.exporter.otlphttp/)의
debug metrics와 실제 버전에서 얻은 metric 이름을 대조한다.
이 쿼리는 현재 상태 확인용이다. 신규 worker/DB snapshot/phase metric이 이미 있다는 뜻이 아니다.

## 실제 조사 순서와 기록

1. **준비:** O1~O3의 증거와 대상 식별값을 확인한다. M5에서는 O4~O6의 해당 조건도 만족해야 한다.
2. **이상 발견:** Live/RCA의 앱 서버 HTTP route 지연·오류와 자원 지표를 보고 이상 시각을 기록한다.
3. **후보 비교:** 같은 시간의 RCA 4/117/118, 5/6/130/131, 119~124, 7/8, 143/144,
   신규 worker/executor/앱 서버 자원/collector 패널을 비교한다. CPU만 보고 원인을 정하지 않는다.
4. **후보 선정:** 어떤 지표가 먼저 변했는지, 동시인지, 반대 증거가 무엇인지 기록한다.
   scrape 간격 이하의 선후관계는 단정하지 않는다. 좁힐 수 없다면 부족한 신호를 명시한다.
5. **Explore:** RCA 2/150에서 같은 실행·route의 느린 trace를 골라 정상 trace와 비교한다.
   JDBC/외부/업무 단계를 보고 Loki와 Pyroscope로 이동한다. DB wait/blocker snapshot은 같은 시간으로 대조한다.
6. **코드 확인:** 관측으로 좁힌 operation·SQL family·stack의 소스와 transaction 경계를 확인한다.
7. **재검증:** 한 번에 한 요인만 변경하고 같은 조건에서 지연·대기·처리량·회복을 비교한다.
   정합성이나 Provider 실패 의미를 바꿔 성능을 개선하지 않는다.

다음은 조사 순서 예시이며 관측한 결과가 아니다.
`주문 route p95 상승 → Hikari pending 증가 → Lock wait 증가 → 긴 JDBC/업무 단계 →
blocker snapshot/정상 trace 비교 → 해당 transaction의 코드 확인`으로 진행한다.
pending만으로 pool 부족을 확정할 수 없고 repository span 전체를 SQL lock 대기로 볼 수 없다.

실행의 private 출력에 `investigation.md`와 `observability-readiness.json`을 저장한다.
JSON은 각 O 항목의 `status: PASSED|FAILED|NOT_RUN|NOT_APPLICABLE`, 대상, 확인 시각, 증거 링크와 이유를 필수로 둔다.
P0 누락, 관련 P1 누락, 설명하지 않은 P2 누락이 남은 상태로 `PASSED`를 기록하지 않는다.

| 기록 열 | 필수 내용 |
| --- | --- |
| observedAt | 실제 관측·판단한 UTC 시각 |
| interval | 데이터의 절대 from/to와 timezone. 관측 시각과 별도 |
| evidence | Dashboard UID/panel ID/변수, Explore query, trace ID, 저장 결과 링크 |
| observation | 수치·단위·결측. 관측한 사실만 기록 |
| hypothesis | 후보와 반대 증거, 아직 확정하지 못한 부분 |
| nextCheck | 다음에 무엇을 보면 후보를 지지하거나 배제할 수 있는지 |
| sourceCheck | 이후 확인한 SHA/path/행, transaction과 대조 결과 |
| conclusion | 확정/유력 후보/상관관계만 확인/OBSERVABILITY_GAP, 재측정 결과 |

## 검증과 구현 경계

- 이번 구현에서 실행: application/test compile, bounded telemetry unit test, dashboard/query contract,
  collector unit test, 격리 PostgreSQL의 실제 exporter/transactionid wait/다른 DB 제외 검증,
  Compose/Alloy/Prometheus rule 정적 계약 검증.
- 이번 구현에서 미실행: 새 revision 배포, 중앙 Prometheus ingest, 실제 Grafana dashboard 표시,
  exemplar→Tempo→Loki→Pyroscope 연결, perf fixture 잠금/worker smoke, 계측 overhead 비교, 부하 측정.
- 따라서 저장소 검증이 통과해도 O1~O3의 M0 live gate와 O4~O6의 관련 live gate는 아직 `NOT_RUN`이다.
- 신규 문서 자체 상대 링크·whitespace 검사: Passed. ADR-130과 연결된 이관 계획·Runbook 복원 후
  전체 `scripts/verify-docs.sh`도 검증기 18개와 전체 문서 링크 검사를 예외 없이 통과했다.
  복원 전 ADR-130 상대 링크 2개 실패는 역사적 결과이며 현재 gate에서 무시하지 않는다.
- 전체 `./gradlew check`: Failed (`1,755` tests, `1` failed, `2` skipped). 실패한
  `StoreCatalogQueryMigrationTest`는 PostgreSQL이 예상한 전용 index 대신 기존 lifecycle index를 선택한
  실행계획 단언이며, `--rerun-tasks` 격리 재실행은 Passed다. 전체 suite 결과를 통과로 바꾸지는 않는다.
- 구현 시 `scripts/perf/test-dashboard-queries.py`, `test-live-dashboard-queries.py`,
  `test-observability-contract.sh`와 변경한 collector/worker 테스트를 실행한다.
  실제 인자와 필요한 도구는 각 파일 및 현재 Runbook에서 확인한다.
- DB collector는 격리 PostgreSQL에서 대기·다른 DB·실패를 재현한다. dashboard는 정상·무관측·결측·stale·다른 host 혼입을 검증한다.
- 새 계측 계약은 구현 전에 ADR-121에 기록한다. 원문 SQL의 Loki 전송이나 PID와 request의 직접 연결을
  이 계획의 승인 사항으로 취급하지 않는다. 제품 API·불변식·retry 시간·transaction을 변경하지 않는다.
