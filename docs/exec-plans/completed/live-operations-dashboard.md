# 현재 상태를 확인하는 Grafana 대시보드 추가

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-08`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

부하 실행을 선택하지 않고 현재 API 요청·오류·지연, DB 대기, 자원 사용과 수집 상태를 확인한다.
기본 시간은 최근 15분, 화면 갱신은 10초다. 운영자가 이상 구간을 고르면 기존 Performance RCA로
같은 시간 범위를 넘겨 상세 진단한다.

## Current State

기존 `beanflow-performance-rca.json`은 실행 ID, workflow 판정과 기준선 비교가 먼저 나온다.
현재 중앙 Prometheus에서 environment=perf의 API, Alloy, PostgreSQL, node, container target을 확인했다.
datasource UID는 `beanflow-prometheus`다. API/DB/node scrape는 10초, Alloy/container는 15초다.

## Definitions

- 현재 값: Grafana instant query 평가 시점의 최신 유효 표본.
- RED: 요청률, 오류율, 응답 시간.
- 수집 신선도: 현재 시각에서 Prometheus가 마지막으로 target을 scrape한 시각을 뺀 시간.
- worker 관측값: worker가 마지막 실행에서 갱신한 gauge. DB를 직접 실시간 조회한 값이 아니다.

## Scope

### In Scope

새 dashboard JSON, PromQL 의미 검증, 운영 안내, 현재 Grafana에 새 대시보드 추가와 화면 확인.

### Non-goals

기존 RCA 재구성, 신규 exporter, application 계측, 제품 API, 신규 SLO·경보, 부하 실행.

## Business Rules and Invariants

ADR-121의 bounded label과 신호 소유권을 유지한다. UNKNOWN을 성공으로 합치지 않는다.
데이터 없음·수집 실패를 0 또는 정상으로 대체하지 않는다. 지연과 오류율은 무요청 상태를 구분한다.

## Architecture and Transaction Boundaries

기존 Prometheus read query만 사용한다. Aggregate, 트랜잭션, 외부 Provider 호출 변경은 없다.
환경과 단일 host를 선택하며 현재 topology의 같은 host·다른 port인 exporter를 함께 조회한다.
분리 DB host 또는 IPv6 topology 지원이 필요하면 selector 계약을 먼저 재검토한다.
instance selector는 scrape 설정의 고정 port(API 18081, DB 19187, node 19100, Alloy 12345,
container 19101)를 사용한다. host의 IPv4 점을 regex로 해석하지 않도록 exact matcher를 사용한다.

## Alternatives Considered

기존 RCA 개조는 실행 보고서 사용 흐름을 바꾸므로 별도 dashboard를 선택한다.
새 수집기는 현재 질문에 필요한 지표가 이미 있어 도입하지 않는다.

## Failure Semantics

현재 통계는 instant query이며 과거 구간의 lastNotNull로 정상값을 남기지 않는다.
API/DB/node는 30초, Alloy/container는 45초 이내 scrape만 사용한다. 이는 scrape 주기의 세 배인
표본 유효성 기준이며 제품 SLO가 아니다. DB는 pg_up와 scrape error도 확인한다. container는
collection_success와 마지막 성공 시각도 확인한다. 없는 target은 수집 안 됨으로 남는다.
worker gauge에는 자체 마지막 성공 시각이 없으므로 최신 DB 상태·worker 정상의 증거로 쓰지 않는다.

## Data and Migration

없음. 저장소에는 dashboard와 합성 query fixture만 추가한다.

## API and Event Contracts

공개 계약 변경 없음. 기존 Prometheus metric과 Grafana provisioning 경로를 재사용한다.

## Milestones

1. 현재 metric과 수집 주기를 확인한다.
2. 새 dashboard와 의미 검증을 작성한다.
3. Promtool·실제 Prometheus 조회·문서 검증을 통과한다.
4. 새 Grafana dashboard를 추가하고 10초 갱신과 렌더링을 확인한다.

## Required Tests

정상·유휴·미수집·scrape 실패·오래된 표본·DB 오류·container 수집 실패·환경/host 격리.
모든 PromQL의 parser 검증과 live query 결과, 기존 dashboard query 회귀 검증.

## Validation Commands

    python3 scripts/perf/test-live-dashboard-queries.py
    python3 scripts/perf/test-dashboard-queries.py
    bash scripts/verify-docs.sh
    git diff --check

## Observability

운영 질문: 지금 응답하는가, 요청이 얼마나 실패/지연되는가, DB·자원이 막히는가, 지표 자체를 믿을 수 있는가.
API scrape 성공은 외부 WAF 경로·로그인·결제 성공을 증명하지 않는다.

## Documentation Updates

`docs/operations/live-operations-dashboard.md`, 기존 관측성 runbook의 설치 목록, 이 계획.
Accepted ADR-121을 그대로 적용하며 제품 정책 또는 새 ADR 변경은 없다.

## Progress

- [x] 현재 metric·scrape target·Grafana 폴더와 datasource 계약 확인.
- [x] dashboard, query tests, runbook 작성.
- [x] 정적·live query·Grafana 검증.

## Surprises & Discoveries

`beanflow_reservation_due_count`는 전체 backlog가 아니라 마지막 batch 후보 수다.
worker gauge는 실패 뒤 이전 값을 유지할 수 있어 정상 판정 카드에서 제외한다.
Grafana의 명시적 regex 변수 포맷은 IPv4 점을 단일 역슬래시로 보간해 PromQL 문자열 오류를 발생시켰다.
host는 exact instance matcher, Route는 Prometheus datasource의 기본 변수 보간으로 수정했다.

## Decision Log

- 2026-09-08: 새 `beanflow-live-operations` UID, 최근 15분/10초 갱신, 환경·단일 host 선택.
- 2026-09-08: 오류율 분모가 0이면 값 없음. 성공 scrape를 확인한 DB wait 빈 결과만 0으로 표시.

## Outcomes & Retrospective

- Passed: `test-live-dashboard-queries.py` — 실제 JSON의 47개 PromQL과 35개 가용성/격리 시나리오.
- Passed: 기존 `test-dashboard-queries.py` — 단위·label·DB wait 가용성 7개 시나리오.
- Passed: 중앙 Prometheus에 실제 환경/host와 1분 rate window로 47개 instant query를 조회해 모두
  success, query warning 없음. 유휴 요청의 p95=NaN/오류율 빈 결과, 무제한 CPU limit과 미생성
  충돌 counter의 빈 결과는 의미에 맞게 구분했다. 관측 중 발생한 기존 트래픽에서도 RPS/p95를 확인했다.
- Passed: Grafana 13.2의 BeanFlow 폴더에 `BeanFlow / Live Operations`를 UI import하고 수정본까지 저장.
  API 수집 중, 현재 요청 0, 유휴 지연/오류율 설명, DB 대기/heap/CPU/I/O/container/provider/worker
  그래프, 5개 scrape target과 내부 수집 상태를 실제 화면에서 확인했다. 중괄호가 있는 payment-attempts
  Route 선택과 전체 Route 복원, 같은 시간 RCA 링크의 URL, 최근 15분/10초 자동 갱신 설정을 확인했다.
- Passed: `scripts/verify-docs.sh`, `git diff --check`.
- Not run: 제품 application 전체 테스트/빌드, 부하 또는 장애 주입, WAF/로그인/결제 E2E,
  개별 trace→log→profile 재검증. 제품 코드·API·schema 변경은 없다.
- Grafana DB 저장으로 즉시 사용할 수 있다. monitoring host의 파일 provisioning 배치는 Not run이며
  재현 가능한 JSON과 기존 provider 경로를 제공했다. 기존 RCA와 datasource는 변경하지 않았다.
- 자체 성공 시각이 없는 worker gauge의 한계는 패널과 runbook에 남겼다. worker freshness 계측은
  후속 backend 변경이 필요하며 이 dashboard가 worker 정상 또는 전체 backlog를 보장하지 않는다.

## Revision Notes

- 2026-09-08: 현재 수집 계약을 근거로 계획 작성.
- 2026-09-08: PromQL/Grafana 검증과 새 dashboard 저장 완료, completed로 이동.
- 2026-09-08: main 반영 시 기존 검증을 재실행하고 CI preflight에 Live/RCA 쿼리 검증을 연결했다.

## main 반영 검증

- main `8038e71`에서 별도 branch를 만들고 검증된 dashboard JSON과 query test를 그대로 옮겼다.
  기존 RCA 무관측 지연 쿼리·회귀 테스트, Toss driver 멱등키 보강과 제품 코드는 유지한다.
- Passed: 실제 dashboard query 47개와 가용성·격리 35개 사례, 기존 DB wait 7개와 pickup mean 6개 사례.
- Passed: 전체 observability contract의 Compose, Alloy, Prometheus rules, 24개 Node/k6 사례,
  container/runner tests와 PostgreSQL exporter의 권한·누락 실패·실제 publish endpoint 검증.
- Passed: 문서/OpenAPI 검증과 diff check. CI preflight에서도 두 dashboard query suite를 실행하고
  실패 로그를 artifact로 보존한다. 원격 실행 결과는 해당 PR의 정확한 head check에서 확인한다.
- Not run: 이 저장소 반영 작업에서 서버 재배포, 새 부하·장애 주입, Grafana UI 재검증.
  앞 절의 Grafana/중앙 query 결과는 최초 구현 당시의 검증 기록이다.
