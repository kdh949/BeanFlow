# 거래 흐름을 확장하고 현재 배포의 안정 처리량과 병목 검증

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/performance-observability-and-load-test-foundation.md`, `docs/exec-plans/completed/perf-selective-database-cutover.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 작성 기준일은 2026-09-13 KST다.
ADR-130과 연결된 이관 계획·Runbook은 현재 작업트리에 복원되어 문서 검증을 통과했다.
원본 보존과 현재 대상 확인은 본 문서와 이관 ADR·Runbook을 따른다.
실제 서버 실행은 M0의 대상 확인과 실행 범위가 충족된 경우에만 가능하다.
문서 작성 완료, 도구 구현 완료, 배포 완료, 실제 부하 검증 완료를 구분한다.

## Purpose / Big Picture

대상 origin은 `https://beanflow.dhkim.cloud`다. 현재 자원에서 지정한 부하를 실제로 생성하면서
거래 정합성과 임시 지연 기준을 유지하는 구간, 처음 실패하는 구간, 그 원인과 회복을 측정한다.
확정한 목적은 **현재 자원에서 안정 처리량과 첫 병목 찾기**다.
특정 이용자 수나 운영 SLA를 이미 달성했다고 가정하지 않는다.
병목 조사는 **Grafana Dashboard → Explore → 후보와 반대 증거 기록 → 코드 확인 → 재측정**을 따른다.
필요한 신호가 없으면 관측성을 먼저 보완한다. 코드를 먼저 보고 얻은 추측을 관측된 원인으로 쓰지 않는다.

작업 순서는 다음과 같다. 각 단계의 종료 조건을 충족한 뒤 다음 단계로 간다.

1. M0: 대상 배포·데이터·관측성·기존 도구의 실행 경계를 고정한다.
2. M1: 인증 fixture와 데이터 예산, 거래 집계·정합성 확인 도구를 준비한다.
3. M2: 기존 시나리오로 짧은 기준선을 측정한다.
4. M3: 고객 주문부터 점주 픽업 완료까지의 새 k6 흐름을 구현·검증한다.
5. M4: 취소·거절·혼합 사용·동시 중복을 추가하고 단건 실환경 검증을 한다.
6. M5: 분산/집중 부하를 단계적으로 올리고 지속·급증·회복을 측정한다.
7. M6: 결제 결과불명, 매장 수락 만료와 제어된 DB 잠금을 별도 검증한다.
8. M7: 원본 결과와 서버 증거를 대조하고 재현 가능한 보고서를 남긴다.

M2 실패를 발견하면 해당 실패를 기록하고 M3의 로컬 도구 구현은 계속할 수 있다.
실패한 부하를 통과한 것으로 취급하거나 상위 부하 실행의 근거로 사용하지 않는다.

### 실행자 시작 절차

1. 저장소 공통 필수 문서, 본 문서, 아래 Current State의 연결 문서를 읽는다.
2. `git status --short`와 `git rev-parse HEAD`를 기록한다. 기존 변경을 stash/reset/clean하지 않는다.
3. Progress에서 첫 미완료 단계를 찾고 그 단계의 입력·변경 파일·검증·종료 조건만 수행한다.
4. 추가할 파일은 아래에서 **신규 예정**이라고 표시했다. 아직 존재하지 않는 명령을 실행하지 않는다.
5. 계약과 코드가 다르면 양쪽 파일·내용·영향·추천 해결을 보고한다. 테스트 기대값부터 완화하지 않는다.
6. 매 단계 종료 시 Progress, 실행 증거 위치, Decision Log와 다음 시작점을 갱신한다.
7. commit/push/PR/배포는 해당 작업에 대한 명시적 요청 범위에서만 수행한다.
   이미 승인된 같은 범위를 반복 확인하지 않는다. 아래 Grafana 대시보드 수정·추가·반영은 이 계획의
   승인 범위에 포함한다. 그 밖의 원격 변경까지 포괄 승인된 것으로 확대하지 않는다.

### ADR-130 복원 상태

- `docs/adr/ADR-130-perf-selective-database-cutover.md`와 연결된 이관 완료 계획·Runbook을 함께 보존한다.
- 복원 전 broken relative link는 역사적 검증 결과로만 남기고 더 이상 진행 예외로 사용하지 않는다.
- `bash scripts/verify-docs.sh`는 예외 없이 실행하며 다른 오류도 그대로 실패로 전파한다.
- 현재 DB/revision/Provider 확인, 합성 fixture 소유권, 원본 보존, 거래 정합성과 관측성 gate는 계속 적용한다.

## Current State

### 확인한 기준과 현재 서버 확인의 구분

| 항목 | 문서 작성 시 확인 | 실행 전에 할 일 |
| --- | --- | --- |
| 로컬 코드 | `503d15831cc3b775bb073c8bcefb534ad89f36a0`, main | 실행 코드 SHA와 dirty diff/hash 재기록 |
| 공개 사이트 | 브라우저에서 `/`와 고객 `/app`, 매장 `/store`, 운영 `/ops`, 지원 `/support` 링크 확인 | 이 origin의 API와 선택한 배포가 같은 대상인지 확인 |
| 최근 이관 기록 | [선별 이관 Runbook](../../operations/perf-selective-db-cutover-runbook.md)에 아래 결과 기록 | 기록을 현재 실행 사실로 가정하지 말고 read-only 확인 |
| 앱 서버 기록 | `orbit-runner@172.16.16.22`, Compose project `beanflow-perf` | host·project·service label·실행 image 확인 |
| 대상 DB 기록 | `beanflow_perf_20260913` | 앱이 실제 연결한 DB와 exporter DB를 둘 다 확인 |
| 대상 이미지 bucket 기록 | `beanflow-perf-20260913` | 현재 bucket 및 별도 원본 보존 확인 |
| 보존 원본 | DB `beanflow`, bucket `beanflow-staging` | 쓰기·초기화·orphan cleanup 대상에서 제외 |
| API/web revision 기록 | 둘 다 `503d15831cc3b775bb073c8bcefb534ad89f36a0` | OCI revision label, 실제 image ID와 manifest digest 구분 기록 |
| 현재 배포 설정 기록 | `/home/orbit-runner/beanflow-cutover-503d158/deployment-compose.json` | 현재 컨테이너와 대조; 원문에는 민감값이 있을 수 있어 출력 금지 |
| 모니터링 후보 | 기존 Runbook의 `172.16.16.18` | Prometheus/Grafana endpoint와 권한 실제 확인 |

이관 문서의 API digest는 `sha256:8004525749653b0f0b32f613d235c81e373677f429966068749d2eb6385b8b45`,
web digest는 `sha256:ad629b63ccab594e036926b04e61e1725afcea0586f34439f408d8684a51d457`다.
이는 이번 문서 작성 중 서버에서 재확인한 값이 아니다.
문서 작성 시 이관 ADR/계획/Runbook과 일부 이관 도구는 미추적 상태였다.
다른 checkout에 본 계획을 전달할 때 두 Depends-On 파일과 연결된 Runbook을 함께 전달한다.
현재 ADR-130과 연결 문서는 함께 복원됐다. 실제 실행 대상은 과거 주소를 그대로 사용하지 않고 M0에서 확인한다.

### 기존 기능과 보완점

| 현재 파일 | 확인한 동작 | 이번 작업에서 필요한 보완 |
| --- | --- | --- |
| [beanflow-load.js](../../../scripts/load/beanflow-load.js) | `quote-order`, `idempotency`, `board-polling`, Toss 4종, `db-lock` | 기존 결과 비교용으로 유지하고 새 흐름은 별도 entrypoint에 구현 |
| [run.py](../../../scripts/load/run.py) | private manifest/summary, target snapshot, remote write, baseline 비교 | 새 entrypoint 선택, 모든 입력 hash, 중단·회복·중앙 수집 판정 분리 |
| [capture-load-target.py](../../../scripts/perf/capture-load-target.py) | 고정 컨테이너 이름, image/resource/일부 계측 설정 | project label 확인, 실제 DB/bucket/Hikari/worker 설정·driver 세대 추가 |
| [hold-pickup-slot-lock.sh](../../../scripts/perf/hold-pickup-slot-lock.sh) | 과거 Compose 조합과 `beanflow_perf` DB를 고정 | 현재 배포 DB 명시 입력, fixture 소유 확인 후에만 실행 |
| [postgres-wait-snapshot.sh](../../../scripts/perf/postgres-wait-snapshot.sh) | DB 대기 수집 | 같은 target binding 적용 |
| [PerformanceTelemetryContextFilter.kt](../../../src/main/kotlin/io/github/kdh949/beanflow/shared/internal/PerformanceTelemetryContextFilter.kt) | scenario 이름 allowlist 밖이면 HTTP 400 | 신규 이름을 코드·테스트·ADR-121에 함께 추가 |
| [toss-driver.mjs](../../../infra/perf/toss-driver.mjs) | 메모리 결제 사실, 50,000개 한도, 기존 lookup/replay/refund 유지 | 누적 예산과 재시작 경계 확인; 한도 초과를 앱 포화로 분류하지 않음 |

과거 Runbook/도구의 `beanflow_perf`와 이관 후 `beanflow_perf_20260913`은 서로 다른 대상이다.
도구 입력을 검증하기 전 기존 DB 잠금 명령을 복사해 실행하지 않는다.
현재 잠금 스크립트는 슬롯 존재만 확인하므로 "allowlisted"라는 메시지가 fixture 소유 증거는 아니다.

기존 k6는 단일 `fixture.order.storeId`/메뉴 구성과 순환 계정을 사용한다.
기존 `toss-success`는 승인에서 끝나며 매장 수락이 없어 3분 뒤 자동 거절·환불 작업이 생긴다.
`quote-order`는 결제 전 예약을 남겨 최대 5분 또는 슬롯 시작 경계에 만료 작업이 생긴다.
`toss-timeout/unknown`의 1초 뒤 조회는 최종 reconciliation 검증이 아니다.
기존 `workflow_completed`는 성공 수가 아니라 finally까지 도달한 검사 종료 수다.

### 비동기 구현의 현재 한계

[금융 이벤트 producer](../../../src/main/kotlin/io/github/kdh949/beanflow/eventing/internal/FinancialEventPublicationService.kt)는
Analytics target publication을 저장하지만 현재 Analytics 구현은
[별도 active 계획](analytics-refund-and-late-event-projection.md)에 남아 있다.
`event_publication.status='FAILED'`만 보고 모든 row가 새 런타임 장애라고 판단해서도,
등록 listener가 없는 target을 완료로 바꿔서도 안 된다.

`SettlementItemCreationService`의 OrderCompletedV2 listener, Loyalty 적립 owner,
Notification owner와 실제 등록 listener를 M1에서 목록화한다.
매장 거절의 pre-completion 환불은 `CustomerCancellationRefundExclusionService`에서
정산 reconciliation 경로로 갈 수 있다. 고객 취소의 `PRE_ACCEPTANCE_CANCELLATION`과 같다고 가정하지 않는다.
거래 완료, 활성 소비자 처리, 미구현 소비자 적체를 각각 보고하고 Analytics까지 포함한 전체 성공을 주장하지 않는다.

## Definitions

- workflow: 한 번의 업무 흐름. 여러 HTTP 요청과 명시한 대기를 포함한다.
- arrival rate: 초당 새 workflow 시작 목표. HTTP RPS나 로그인 계정 수가 아니다.
- VU: k6 실행 worker. 한 worker 안에서는 한 iteration이 진행된다.
- cohort: 한 test ID로 생성한 주문·결제·이벤트 집합. 시간 범위만으로 소유권을 정하지 않는다.
- fixture: 합성 계정 세션, 매장 소속, 메뉴/옵션, 슬롯·혜택 allocation과 검증한 데이터 조건.
- capacity run: 정상 시나리오에서 부하 달성·지연·오류·정합성·활성 후속 처리 조건을 함께 평가하는 실행.
- fault run: 의도한 장애 상태와 중복 부수효과 부재·회복을 확인하는 실행. 정상 지연 기준과 분리한다.
- quiet: 고정된 대기 시간의 경과가 아니라 활성 작업의 적체·대기·자원이 정의한 수준으로 돌아온 상태.
- 미구현 target: 저장된 publication에 대응하는 consumer가 현재 소스/등록 목록에 없는 상태. 성공이 아니다.

## Scope

### In Scope

- 현재 origin과 perf 배포의 read-only 사전 점검 및 보호된 실행 기록.
- 합성 fixture/session 준비와 매장별 분산·집중 allocation.
- 기존 짧은 기준선, 공개 주문번호 기반 정상 전체 흐름, 취소·거절·혼합 조회·동시 중복.
- 도착률 기반 측정, 단계별 판정, 종료 후 정합성·적체·회복 확인.
- 필요한 perf 진단 allowlist와 테스트 도구 변경, 실행 보고서.
- [Grafana 진단 준비 계약](../../operations/performance-grafana-diagnosis-readiness.md)의
  누락된 수집·업무 단계·worker 신선도 보완과 기존 지표의 dashboard 연결.
- 부하 테스트 준비·실행·회복·원인 조사 중 필요한 Grafana 패널/쿼리/변수/링크 수정,
  전용 대시보드 추가 및 BeanFlow Grafana 반영. 아래 Observability의 변경 절차를 따른다.

### Non-goals

- 성능 수치를 맞추기 위한 상품 정책, transaction, retry 시간, Hikari 크기의 선제 변경.
- 실제 Toss 부하, 실사용자 계정 사용, 외부 SMS/email 전송, 운영자/지원 업무 쓰기 부하.
- Analytics/POS 등 미구현 제품 기능을 이 계획 안에서 구현.
- DB/volume/bucket 초기화, 기존 결제·환불·MANUAL_REVIEW 삭제, Flyway repair.
- 프론트엔드 수정, 브라우저 렌더링 성능, 이미지 대용량 전송/업로드 성능.
- 임의의 commit/push/PR/배포, 새로운 production dependency.

## Business Rules and Invariants

필수 근거는 [Business Policy](../../product/business-policy-decisions.md),
[실패 의미](../../architecture/failure-semantics.md),
[ADR-006](../../adr/ADR-006-external-payment-transaction-boundary.md),
[ADR-007](../../adr/ADR-007-payment-idempotency-reconciliation.md),
[ADR-121](../../adr/ADR-121-performance-observability-and-trace-profile-correlation.md)다.
이관의 원본 보존·대상 구분은 본 문서와 [이관 Runbook](../../operations/perf-selective-db-cutover-runbook.md)을 따른다.
ADR-130의 결정과 현재 실행 대상이 충돌하면 실행 전에 차이를 기록하고 결정 문서를 먼저 갱신한다.

1. BR-03: 미결제 예약은 `min(주문 생성+5분, 슬롯 시작)`까지만 유효하다. 만료 뒤 늦은 승인이 주문을 되살리지 않는다.
2. BR-05: 주문 생성 시 예약, 승인 시 확정. 슬롯의 reserved/confirmed 합은 정원을 초과하지 않는다.
   **픽업 완료는 정상 판매 실적이다. 완료 주문의 확정 슬롯을 임의 해제하거나 정원을 재사용하지 않는다.**
3. BR-06: 승인 후 2분 warning, 3분 수락 deadline. 정상 흐름은 deadline 전에 ACCEPT한다.
4. 상태 전이: `PAID → ACCEPTED → PREPARING → READY → COMPLETED`.
   거절은 PAID에서, 고객 취소는 PENDING_PAYMENT/수락 전 PAID에서만 실행한다.
5. BR-25: actor+operation+key 범위. 동일 논리 요청의 동일 payload만 같은 key로 재전송한다.
   다른 주문·승인·취소·각 매장 action에는 서로 다른 key를 사용한다.
6. 성공처럼 보이는 202를 승인/환불 완료로 세지 않는다. UNKNOWN/RECONCILING/MANUAL_REVIEW를 보존한다.
7. 금액은 정수 KRW. quote fingerprint와 가격·혜택 snapshot을 사용하고 stale를 자동 재견적으로 숨기지 않는다.
8. 슬롯 복구, 쿠폰·포인트 복구/적립, 정산 item과 publication은 실제 owner 결과와 원장으로 확인한다.
9. 고객 session 동시 한도 5개, 점주 3개를 넘기는 반복 로그인으로 기존 session을 폐기하지 않는다.
   `INITIAL_PASSWORD` 점주를 자동 비밀번호 변경으로 우회하지 않는다.
10. 합성 데이터에도 실제 제약과 감사 규칙을 유지한다. 현행 Ordering에는 제거된 재고 owner를 새로 가정하지 않는다.

## Architecture and Transaction Boundaries

부하 도구는 HTTP client와 read-only 검증기로 구성한다. 앱의 Aggregate/transaction은 변경하지 않는다.

| 작업 | 기존 경계 | 검증 |
| --- | --- | --- |
| 주문 생성 | Ordering이 슬롯·쿠폰·포인트 예약, snapshot, Audit를 같은 local transaction으로 조정 | 201 order 수와 DB/멱등 레코드·예약 수 대조 |
| 결제 | Tx1 승인 준비 → transaction 밖 Provider → Tx2 Payment·Order·예약 확정 | UNKNOWN의 확정 실패 오분류, 중복 승인, 장시간 connection 점유 확인 |
| 점주 action | order lock·expectedStatus·멱등 응답·Audit·event를 local transaction으로 저장 | 순서/동시 replay, 실패 rollback, final 상태 확인 |
| 고객 취소 | Order/command/Case/필요 Refund/Notification/publication commit gate | 202 접수와 실제 refund/owner 성공 분리 |
| 후속 처리 | 각 listener/worker의 후속 transaction | 등록 listener별 지연·attempt·영속 상태와 business 원장 대조 |

새 실행기를 재시도해도 이미 생성한 주문을 다시 만들지 않는다.
VUs 사이에 공유 mutable 주문 queue가 있다고 가정하지 않는다. 한 lifecycle iteration이
자기 customer와 그 매장의 merchant로 자기 주문을 끝까지 처리한다. 보드 polling은 별도 scenario다.
이 방식은 합성 서비스 흐름이며 실제 매장 직원의 작업 시간·동시 생산 능력을 재현했다는 주장이 아니다.

## Alternatives Considered

| 대안 | 선택/배제 이유 |
| --- | --- |
| 기존 스크립트만 오래 실행 | 승인 후 자동 거절과 예약 만료가 섞여 정상 거래 기준선을 설명하기 어려움 |
| 기존 entrypoint 대규모 변경 | 과거 script hash와 계약 테스트의 비교 경계가 흐려짐; 새 entrypoint를 추가 |
| 모든 actor를 한 계정으로 실행 | session/account/혜택 경합이 사용자 규모를 왜곡함; 명시적인 공유 계정 실험에서만 사용 |
| 처음부터 모든 endpoint 혼합 | 첫 병목을 구분하기 어려움; 단일 흐름 → 분산/집중 → 혼합 순서 |
| DB를 초기화하고 매번 측정 | 기존 데이터 보존 요구 위반; 실행별 allocation과 상태 기록으로 구분 |
| 미구현 consumer를 성공 처리 | 실패 의미 위반; 소비 범위와 적체를 명시하고 전체 완료 주장을 제한 |

## Failure Semantics

### 판정 필드

runner의 기존 `status`는 k6 threshold 결과로 유지하고 다음 필드를 별도로 추가한다.

| 필드 | 값/의미 |
| --- | --- |
| `load_status` | PASSED/FAILED/ABORTED/NOT_RUN |
| `integrity_status` | PASSED/FAILED/NOT_VERIFIED |
| `telemetry_status` | VERIFIED/FAILED/NOT_VERIFIED/NOT_SENT |
| `active_recovery_status` | PASSED/PENDING/FAILED/NOT_VERIFIED |
| `downstream_scope_status` | COMPLETE/INCOMPLETE_IMPLEMENTATION/NOT_VERIFIED |
| `capacity_status` | PASSED_FOR_DECLARED_SCOPE/FAILED/INVALID/NOT_EVALUATED |
| `stop_reason` | bounded error code와 진단 파일 경로; secret/raw body 없음 |

`PASSED_FOR_DECLARED_SCOPE`는 사전에 선언한 구현 범위에서 모든 관련 gate를 충족한 경우만 사용한다.
미구현 Analytics가 남으면 `downstream_scope_status=INCOMPLETE_IMPLEMENTATION`을 반드시 병기한다.
capacity_status 하나로 거래 플랫폼 전체의 준비 완료를 표현하지 않는다.

### 즉시 중단 또는 증량 중단

- 주문/결제 중복, 초과 예약, 금액/원장 불일치: 즉시 새 부하 중단, `FAILED`, 원본 보존.
- target DB/image/host mismatch, 잘못된 profile/Provider, 발생기 오류, 수집 단절: `INVALID`.
- 401/403, 반복 quote stale, fixture 고갈, driver 한도: 부하 중단 후 원인 분류. 성공으로 재분류하지 않는다.
- 정상 시나리오 시작 30초 이후 누적 HTTP 또는 workflow 실패율이 1% 이상: 실행 중단.
- 정상 시나리오 시작 60초 이후 누적 HTTP p95 1초 이상, 또는 dropped 발생: 상위 증량 금지.
  원인 확인에 필요한 짧은 관측 후 해당 실행 종료. 처음 실패를 제거하고 재실행 결과만 남기지 않는다.
- 컨테이너 restart/OOM, DB connection 오류 지속, host 가용 디스크 10% 미만: 즉시 새 부하 중단.
- k6 process 중단만으로 완료가 아니다. 현재 진행 요청·잠금 해제·owner recovery를 확인한다.

위 숫자는 시험 중단용 초기 guardrail이다. 운영 SLO를 새로 확정하는 정책이 아니다.
HTTP 집계는 전체 외에 route/scenario별로 평가해 빠른 조회가 느린 결제를 가리지 않게 한다.
새 suite는 k6 threshold의 `abortOnFail:true`와 `delayAbortEval:'30s'/'60s'`로 위 누적 기준을 구현한다.
이를 최근 30초/60초 rolling window로 설명하지 않는다. 종료 시에는 짧은 실행도 전체 threshold를 평가한다.
M2의 변경 전 legacy suite에는 자동 abort가 없으므로 실행자가 같은 기준을 관찰하며 필요 시 SIGINT로 중단한다.

## Data and Migration

### 데이터 소유와 준비 순서

M0에서 현재 DB와 보존 원본을 분리한다. 이 계획은 Flyway 변경이나 원본 재이관을 요구하지 않는다.
최근 이관 기록의 220 고객/161 점주/161 매장/2,721 메뉴/미래 슬롯 수는 과거 snapshot이다.
현재 유효 계정·소속·메뉴·슬롯 수를 다시 읽고 **합성 데이터임이 확인된 allowlist**만 사용한다.
UUID 모양이나 이름의 접두사만으로 합성 계정이라고 판단하지 않는다.

최초 실험은 points=0, coupon 없음, 수량 1로 한다. 메뉴 필수 옵션이 있으면
메뉴 configurations 응답으로 유효한 option 조합을 선택한다. 모든 메뉴에 `optionIds=[]`를 강제하지 않는다.
사용 가능한 메뉴/슬롯/계약/적립 정책이 부족하면 필요한 수량과 owner API를 보고한다.
DB 직접 UPDATE, 권한 임의 부여, 원본 정책 bootstrap 재실행으로 부족분을 덮지 않는다.

### fixture v2 계약 — 신규 예정

보호된 JSON 파일이며 실제 session/password/actor 식별자는 저장소에 넣지 않는다.
다음은 구조 설명이다. `<...>`가 남은 파일은 validation 실패여야 한다.

```json
{
  "schemaVersion": 2,
  "datasetId": "bf-capacity-v1",
  "baseUrl": "https://beanflow.dhkim.cloud",
  "capturedAt": "<ISO8601 UTC>",
  "customers": [{"actorId":"<uuid>","session":"<private>","xsrf":"<private>"}],
  "merchants": [{"actorId":"<uuid>","session":"<private>","xsrf":"<private>","storeIds":["<uuid>"]}],
  "stores": [{
    "storeId":"<uuid>", "merchantActorId":"<uuid>",
    "lines":[{"menuId":"<uuid>","optionIds":[],"quantity":1}],
    "slots":[{"slotId":"<uuid>","startsAt":"<ISO8601>","endsAt":"<ISO8601>","available":1000}]
  }],
  "allocation": {
    "mode":"distributed", "storeIds":["<uuid>"],
    "customerSharing":"exclusive-per-vu", "pointsToUseKrw":0
  }
}
```

- `customers`는 중복 actor/session이 없어야 한다. 사용할 최대 customer VU 수 이상을 준비한다.
- 한 VU의 customer를 실행 중 바꾸지 않는다. 전역 `exec.vu.idInTest`의 배정표를 초기화 때 검증한다.
  서로 다른 k6 process를 동시에 쓰면 명시적으로 계정과 allocation을 분할한다.
- merchant는 store membership이 확인된 계정만 쓴다. 하나의 merchant session 공유 수는 manifest에 기록한다.
- distributed는 최초 10개 매장/매장별 2개 이상 슬롯을 후보로 하되 아래 수량 계산을 우선한다.
  `iterationInTest % storeCount`로 deterministic 배정하고 매장 안의 슬롯도 계획된 allocation으로 배정한다.
- concentrated는 동일 메뉴와 소수 슬롯을 쓰는 1개 매장이다. 두 실험의 총 주문 수·고객·rate·대기는 같게 한다.
- 슬롯은 마지막 예상 주문·승인이 끝나는 시각보다 5분 이상 미래에 시작하도록 선택한다.
  장시간 실행에는 `run_end + graceful_stop + 5분`까지 남은 슬롯을 준비한다.
- capacity 합은 `ceil(rate × 투입시간 × 생성비율) + 10% 여유` 이상이어야 한다.
  완료 주문의 confirmed_count가 남으므로 이 수량은 동시 진행 주문 수가 아니라 누적 판매 수다.
- 최종 부하 전 public slot API와 DB의 예약/확정 count를 대조한다. 다른 사용자의 점유는 숨기지 않는다.
- 쿠폰/포인트 실험은 별도 데이터셋이다. 고객별 coupon 소유권·사용 횟수·PointLot expiry/allocation 예산을 준비한다.
  단일 couponIssuanceId를 모든 고객에게 공유하거나 잔액을 임의 충전하지 않는다.
- fixture와 credentials는 0600, 상위 디렉터리 0700. session 전체를 manifest에 복사하지 않는다.

### 인증 준비 계약

`prepare-fixture.py`를 신규 작성한다. 입력은 0600 credentials JSON의 명시한 합성 계정 목록,
매장/menu/slot allowlist와 target origin이다. 비밀번호를 CLI 인자나 로그로 전달하지 않는다.

1. actor별 `GET /api/v1/auth/{customer|merchant}/csrf`로 cookie jar를 준비한다(204).
2. 같은 jar로 `POST /api/v1/auth/{customer|merchant}/sessions`, JSON `{loginId,password}`(200).
3. 로그인 후 다시 해당 actor CSRF endpoint(204)를 호출해 새 XSRF cookie를 받는다.
4. 고객 `/api/v1/me`, 점주 `/api/v1/merchant/me`와 `/api/v1/merchant/me/stores`로 actor/소속 확인.
5. 고객 `BEANFLOW_CUSTOMER_SESSION`, `BEANFLOW_CUSTOMER_XSRF`, 점주 대응 두 cookie를 fixture에 저장.
6. 쓰기 요청의 `X-BEANFLOW-CSRF`는 해당 actor XSRF cookie 값과 일치시킨다.
   고객·점주 cookie jar를 섞지 않으며 테스트 서버로 실제 송신 header를 검사한다.
7. 준비 로그인은 기본 초당 1개 이하, 병렬 1개로 한다. 429의 Retry-After를 기록하고 무제한 재시도하지 않는다.
8. 측정 중 자동 로그인/CSRF 재발급으로 401/403을 숨기지 않는다. 준비 실패는 부하 시작 실패다.
9. 각 stage 직전에 session과 소속을 확인한다. 마지막 사용 뒤 준비된 session만 정상 logout한다.
   다른 사용자의 session 전체를 폐기하지 않는다.

이 작업은 실사용자 credentials 수집이나 계정 생성/비밀번호 변경을 포함하지 않는다.
사용 가능한 합성 계정 정보가 없으면 M1의 로컬 fixture-server 테스트는 계속하고 실환경 준비만 중단한다.

### Toss 결제 보관 예산

현재 driver에는 보관 건수 조회 endpoint와 영속 snapshot API가 없다. `/healthz=UP`은 빈 driver라는 뜻이 아니다.
특히 DB 이관 때 driver가 유지됐으므로 새 DB의 Payment 수만 세어 현재 보관량으로 사용하면 안 된다.

- `driver_generation`(컨테이너 identity/StartedAt), `retained_upper_bound`, 산출 근거와 조사 시각을 기록한다.
- 알려진 초기 보관량에 그 세대의 모든 소비자/실행에서 보관됐을 수 있는 고유 결제 수를 더한
  보수적 상한을 사용할 수 있다. 누락된 실행·삭제 DB·알 수 없는 소비자가 있으면 `UNKNOWN`이다.
- 계획량은 success/timeout/unknown에서 만들어질 수 있는 고유 결제를 모두 포함한다.
  `known_upper_bound + 다음 실행 worst_case + 예비 500 < 50000`인 경우만 긴 실행을 예약한다.
- 상한 미확정 시 1/s·60초 smoke까지만 허용하고, 실제 한도 관련 오류/로그도 수집한다.
  장시간 시험은 `DRIVER_HEADROOM_UNVERIFIED`로 남기며 0으로 간주하지 않는다.
- 예: 20/s·30분은 36,000개다. 이전 baseline·예열·회복 검증 생성분도 남아 있으므로 단독 계산은 부족하다.
- driver 재시작/교체는 사실 보존이 필요한 별도 배포 작업이다. 앱 DB를 유지한 채 memory를 비우지 않는다.
  보관 상한을 늘리거나 새 status endpoint 배포로 기존 사실을 잃는 변경도 자동 수행하지 않는다.

## API and Event Contracts

소스 기준은 [runtime OpenAPI](../../../openapi/beanflow-v1-runtime.yaml)다.
이 문서가 참조하는 [공통 계약](../../../openapi/beanflow-v1.yaml) fragment와 controller/DTO를 함께 읽는다.
공통 계약에만 있는 endpoint를 구현된 API로 추정하지 않는다.

### 정상 흐름의 정확한 요청 순서

모든 경로는 `https://beanflow.dhkim.cloud`에 붙인다. 쓰기는 actor cookie와 CSRF,
JSON body가 있으면 `Content-Type: application/json`, 논리 명령별 Idempotency-Key를 보낸다.
진단 header는 유효한 test ID와 서버에 등록한 scenario 이름 두 개를 함께 보낸다.

| 순서 | actor / method / path | 입력 | 필수 성공 검사 |
| --- | --- | --- | --- |
| 1 | 고객 POST `/api/v1/me/order-quotes` | `{storeId,pickupSlotId,lines,pointsToUseKrw:0}` | 200, 64자리 소문자 hex `quoteFingerprint` |
| 2 | 고객 POST `/api/v1/orders` | 같은 입력 + `expectedQuoteFingerprint` | 201, `order.orderId`, `order.publicReference`, `order.state=PENDING_PAYMENT` |
| 3 | 고객 GET `/api/v1/me/orders/{orderReference}/checkout` | body 없음 | 200, `canPay=true`, order reference/금액 일치 |
| 4 | 고객 POST `/api/v1/me/orders/{orderReference}/payment-attempts` | body 없음, attempt key | 200, `paymentId`, `providerOrderId`, `amount.value`, 같은 orderReference |
| 5 | 고객 POST `/api/v1/payments/{paymentId}/confirmations` | `{paymentKey,orderId:providerOrderId,amount:amount.value}` | 200, `approvalState=APPROVED` |
| 6 | 고객 GET `/api/v1/me/orders/{orderReference}` | body 없음 | 200, flat `status=PAID`, orderReference 일치 |
| 7 | 점주 GET `/api/v1/stores/{storeId}/orders/{orderReference}` | body 없음 | 200, flat `status=PAID`, `allowedActions`에 ACCEPT 포함 |
| 8 | 점주 POST `/api/v1/stores/{storeId}/orders/{orderReference}/transitions` | `{action:"ACCEPT",expectedStatus:"PAID",reason:null}` | 200, flat `status=ACCEPTED` |
| 9 | 같은 transitions | `{action:"START_PREPARING",expectedStatus:"ACCEPTED",reason:null}` | 200, `status=PREPARING` |
| 10 | 같은 transitions | `{action:"MARK_READY",expectedStatus:"PREPARING",reason:null}` | 200, `status=READY` |
| 11 | 같은 transitions | `{action:"COMPLETE",expectedStatus:"READY",reason:null}` | 200, `status=COMPLETED` |
| 12 | 고객 GET `/api/v1/me/orders/{orderReference}` | body 없음 | 200, `status=COMPLETED`, lifecycle.completedAt, 금액/주문 일치 |

신규 흐름은 위 public reference API를 쓴다. 기존 UUID 기반 API와 응답 모양을 혼동하지 않는다.
매장 public transitions는 `{action,expectedStatus,reason}`이고 응답은 flat `status`다.
legacy PATCH `/api/v1/store-orders/{orderId}/status`의 `{targetState,reason}`/`order.state`와 다르다.
근거: [PublicOrderReferenceController](../../../src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/PublicOrderReferenceController.kt),
[StoreOrderBoardContracts](../../../src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/StoreOrderBoardContracts.kt),
[PublicCheckoutService](../../../src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/PublicCheckoutService.kt).

기본 합성 대기는 ACCEPT 직전 100ms, 나머지 action 직전 각각 200ms다.
상품의 실제 제조 시간이 아니며 manifest에 각 값을 저장한다. k6 workflow 시간에는 포함하고
`active_http_time`과 `think_time`을 별도로 집계한다. 이 대기를 worker 처리량으로 해석하지 않는다.
현재 `OrderingPersistence.complete()`는 READY 상태를 요구하며 슬롯 시작 시각까지 기다리지는 않는다.
따라서 미래 슬롯으로 위 흐름을 검증할 수 있지만 실제 픽업 행동 재현이라고 표현하지 않는다.

### 신규 시나리오 이름과 의미

| 이름 | 입력 흐름 | 기대 결과 |
| --- | --- | --- |
| `lifecycle` | 위 12단계 | 주문 COMPLETED, 승인 1건, 활성 후속 owner 검증 |
| `customer-cancel` | 위 1~6 후 public customer cancellation | PAID 취소 접수 202, 이후 refund와 복구 확인 |
| `store-reject` | 위 1~7 후 REJECT/expectedStatus PAID | 202/flat REJECTED, refund와 각 복구 결과 별도 확인 |
| `mixed` | 아래 70/20/10 분기 + 별도 보드 | 실제 분기별 부하·지연·성공 수 분리 |
| `idempotency-concurrent` | 같은 quote·key·payload로 동시 주문 2개 | logical order 1개, replay 동일 결과 |
| `acceptance-timeout` | 승인 뒤 수락하지 않음 | deadline 뒤 REJECTED, refund/복구 확인 |

새 entrypoint도 기존 `board-polling`, `toss-decline`, `toss-timeout`, `toss-unknown`을 지원한다.
서버 allowlist에 기존 10개 이름을 보존하고 위 6개만 추가한다. regex wildcard로 열지 않는다.
`BEANFLOW_TEST_ID`는 최대 48자의 `[A-Za-z0-9._-]` 내부 규칙을 사용해 key 길이에 여유를 둔다.
멱등 키 예시는 `<testId>-<operation>-<vu>-<iteration>`이며 전체 8~128자,
paymentKey는 `perf-success-<testId>-<vu>-<iteration>`이며 driver의 suffix 80자 제한을 사전 검사한다.
driver의 `perf-unknown`은 계속 IN_PROGRESS다. 환불 실패/지연을 선택하는 API가 있다고 가정하지 않는다.

### 취소와 거절

- 고객: `POST /api/v1/me/orders/{orderReference}/cancellations`,
  `{reasonCode:"CHANGED_MIND",detail:null}`. 결제 전 취소는 200, PAID 취소는 202.
  public 상세의 `status=CANCELLED`를 확인한다. `paymentRecovery.state=SUCCEEDED`는 환불 성공이며
  쿠폰·포인트·알림·정산까지 모두 완료됐다는 뜻은 아니다.
- 점주: transitions에 `{action:"REJECT",expectedStatus:"PAID",reason:"PERF_SCENARIO"}`.
  flat `status=REJECTED`와 202를 확인한다. 이후 점주 상세의 compensationRecovery 및 DB owner 상태를 대조한다.
- 취소/거절 쓰기는 매장 수락 deadline 전에 실행한다. stale 409를 재시도해 정상화하지 않는다.
- 복구 polling은 2초 간격, 최대 120초를 첫 시험 관찰 예산으로 둔다.
  시간 초과는 `PENDING/FAILED`로 기록하며 0원 성공이나 NOT_REQUIRED로 바꾸지 않는다.
  API polling 지연과 cohort 전체의 DB recovery 검증 시간을 분리한다.

### 동시 멱등성

`http.batch`로 같은 고객·같은 order payload·같은 key 요청 2개를 동시에 보낸다.
정상 가능한 조합은 201/201 또는 201과 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`다.
201 응답끼리 orderId/reference가 같아야 한다. 409 body code와 Retry-After를 검사한다.
명시한 Retry-After 뒤 같은 key/payload로 최대 3회 확인해 최초 201/orderId와 같음을 확인한다.
동시 요청 전 같은 coupon/slot을 별도 주문으로 소비하지 않는다.
나머지 409/5xx를 기대 응답으로 포괄 허용하지 않는다.
추가 변형은 같은 key/다른 payload → `409 IDEMPOTENCY_KEY_REUSED`다.
DB의 `ordering_idempotency_record`와 order/reservation이 한 logical result인지 확인한다.
이 시나리오가 만든 미결제 주문은 본 측정 종료 후 같은 고객의 취소 API로 정리하고 증거에 남긴다.

## Milestones

### M0 — 대상, 도구, 계측과 실행 범위 고정

**입력:** 현재 Git 상태, 위 cutover Runbook, 공개 origin, 서버/모니터링 read-only 접근 방법.

**순서:**

1. 아래 로컬 명령으로 소스와 도구 버전을 확인한다. 출력은 실행 디렉터리에 보존한다.
2. 앱 서버에서 `docker ps`의 compose project/service label로 선택한 서비스를 식별한다.
   container env 전체나 배포 Compose 원문을 출력하지 않는다.
3. `capture-load-target.py`를 보완한다. 기존 JSON 키를 유지하고 `--project`, `--expected-db`,
   `--expected-revision`을 추가한다. 불일치는 exit 2와 원인 코드만 출력한다.
   다음 비밀 아닌 값만 추가한다: DB 이름, bucket 이름, active profiles, effective Hikari max/min,
   SQL/connection timeout, JVM memory options, 주요 worker 주기/claim/batch,
   notification provider mode, driver generation, image revision/digest, host 자원과 다른 workload 유무.
   명시 env와 실제 적용값을 구분하고, 확인할 수 없는 값은 null과 이유를 기록한다.
   파일 내용을 못 읽는다고 임의 root mount나 권한 변경으로 우회하지 않는다.
4. DB helper 두 개에 명시한 container/DB/fixture 입력을 추가한다. 기본 DB fallback을 제거한다.
   `current_database()` 확인, 보존 원본 거부, parameter binding, 35초 statement_timeout과 rollback을 유지한다.
   lock 대상은 fixture에 존재하고 같은 store의 선택 slot인지를 검증한다.
5. 앱과 PostgreSQL/exporter의 연결 대상 일치, readiness, Prometheus target/실제 표본을 확인한다.
   관측 준비용 요청으로 trace→log→profile 표본을 확인하고 존재/부재를 각각 기록한다.
   짧은 단일 요청에 profile sample이 없으면 기존 허용 범위의 제어된 smoke로 연결을 검증한다.
   최근 이관의 Pyroscope 429 기록 때문에 수집 제한을 다시 확인한다.
6. [Grafana 진단 준비 계약](../../operations/performance-grafana-diagnosis-readiness.md)의
   O1~O3(P0)를 수행한다. SQL 통계 누락 복원, transactionid 잠금 포함 집계,
   blocker 관측, 대상 필터와 telemetry 전송 상태를 Grafana에서 검증한다.
   제어된 잠금 smoke는 먼저 격리 DB에서 테스트하고, 실환경은 위 fixture 소유·자동 해제 조건을 적용한다.
   `observability-readiness.json`과 `investigation.md`를 private 실행 디렉터리에 둔다.
7. 성능 설정을 먼저 변경하지 않는다. metrics-only/RCA A/B는 다른 실험 ID로 실행하며
   두 설정을 섞어 같은 baseline이라고 비교하지 않는다.

**변경 파일:** `scripts/perf/capture-load-target.py`, `scripts/perf/hold-pickup-slot-lock.sh`,
`scripts/perf/postgres-wait-snapshot.sh`, 신규 `scripts/perf/test-load-target.py`.
관측성 추가 파일·계측 계약·테스트는 연결 문서의 O1~O3에 명시한다.

**검증:** 정상 이름, 다른 DB, 잘못된 revision, 복수 project/service, 누락 container,
secret 미출력, slot 소속 불일치, SQL injection 입력, 잠금 자동 해제의 테스트.

**종료 조건:** `preflight.json`에 origin/revision/DB/provider/관측성 값과 확인 시각이 있고,
실제 부하 발생기·최대 부하·데이터 범위·시험 시간 창이 명시됐다.
O1~O3가 실제 Grafana 확인까지 PASSED여야 M2에 진입한다. 관련 O4/O5(P1)는 M3/M4 단건 검증 전에
완료하고, O6의 앱 서버 host/container 자원 관측은 M5 대상 범위에 맞춰 완료한다.
발생기 자원·네트워크 관측, k6 전송 단계 시간·dropped/VU의 Grafana 연결,
WAF/proxy 로그 수집·연결과 ingress 전용 패널은 이번 구현 및 해당 gate에서 제외한다.
k6 원본 결과와 실행 유효성 검증은 유지하며, 발생기 자원 계측을 완료 조건으로 요구하지 않는다.
실환경 접근이 없으면 로컬 도구 검증은 완료할 수 있으나 `live_preflight=NOT_VERIFIED`로 남긴다.

### M1 — fixture, 예산, 정합성 관찰 도구

**신규 예정 파일과 단일 책임:**

| 파일 | 책임 / 출력 |
| --- | --- |
| `scripts/load/prepare-fixture.py` | credentials/allowlist로 정상 로그인·CSRF·소속·catalog 검증, fixture v2와 secret 없는 준비 보고서 |
| `scripts/load/lib/fixture.js` | v2 schema/중복·expiry·예산 검사, VU/iteration의 deterministic actor/store/slot 배정 |
| `scripts/load/verify-run.py` | 실행 cohort와 원본 summary 대조, DB snapshot/중앙 수집/회복 상태를 별도 JSON 출력 |
| `scripts/load/sql/cohort-integrity.sql` | explicit test ID+actor/store allowlist로 current DB의 read-only 집계 |
| `scripts/load/fixture-contract.test.mjs` | fixture parser와 allocation 경계 테스트 |
| `scripts/load/test-prepare-fixture.py` | CSRF rotation, actor 분리, membership, 실패/로그 마스킹 검증 |
| `scripts/load/test-verify-run.py` | 집계 오류·NULL·미수집·미구현 target·중단 판정 검증 |

도구 간 파일 계약은 다음으로 고정한다.

- `credentials.json`: `{schemaVersion:1,baseUrl,customers:[{actorId,loginId,password}],merchants:[{actorId,loginId,password}]}`.
  로그인 후 고객 응답의 `customerId`, 점주 응답의 `merchantId`를 입력 actorId와 비교한다.
  응답에 `actorId` 필드가 있다고 가정하지 않는다. 비밀번호/쿠키는 이 파일과 fixture 외 출력에 포함하지 않는다.
- `allowlist.json`: `{schemaVersion:1,datasetId,baseUrl,expectedDatabase,customerActorIds,merchantActorIds,stores}`.
  각 store는 `{storeId,merchantActorId,menuIds,slotIds}`다. credentials/fixture의 각 항목은 그 allowlist에 포함돼야 한다.
- `preflight.json`: 대상 identity, 검사별 `status/evidence/capturedAt`, 미확정 값과 이유.
- `target-config.json`: 기존 `captured_ms/deployment_id/runtime` 보존, 신규 `target`에
  `{baseUrl,sshHost,remoteToolsRoot,project,postgresContainer,postgresUser,database,expectedRevision}`.
  endpoint/컨테이너 이름/DB 이름만 저장하고 password/token/전체 env는 넣지 않는다.
- `verification.json`: Failure Semantics의 판정 필드, 검사별 `observed/expected/status/evidence`,
  `cohort_counts`, `active_backlog`, `unimplemented_targets`, 실제 검사 시각.

verifier는 target에 검증한 SSH host와 도구 경로만 사용한다. 서버에서 argv 배열로
`docker exec -i <postgresContainer> psql -X --username <postgresUser> --dbname <database> --set=ON_ERROR_STOP=1`
를 호출하고 검토한 SQL을 stdin으로 전달한다. 실제 접속 방식이 local socket 인증을 허용하는지 M0에서 확인한다.
불가능하면 기존 credential runner 경로를 사용하고 새 password 파일이나 PG 공개 port를 만들지 않는다.
target/allowlist의 문자열을 shell/SQL에 그대로 삽입하지 않는다. SSH 원격 명령은 고정된 verifier 진입점만
실행하고 그 입력 JSON을 stdin으로 보낸다. DB 이름/UUID/test ID를 검증한 뒤 SQL parameter로 바인딩한다.
SQL query 부하는 측정 중 15초당 집계 1회 이하이며 무거운 정합성 대조는 before/after에 실행한다.

fixture 준비/검증 도구는 password/session 없이도 로컬 HTTP fixture server로 테스트 가능해야 한다.
신규 SQL은 Application DB를 변경하지 않는다. current schema와 실제 owner 코드를 기준으로 작성하고
원본 V1 DDL만 보고 뒤 migration에 추가된 상태/컬럼을 누락하지 않는다.

cohort의 기본 선택은 `ordering_idempotency_record.operation='CREATE_ORDER'`와
`idempotency_key`의 정확한 `<testId>-order-` 접두사, actor/store allowlist 교집합이다.
SQL LIKE의 `_` wildcard를 피하고 `starts_with` 또는 동일 길이 문자열 비교를 사용한다.
`order_id`와 `intended_order_id`를 모두 조사해 응답 유실/진행 중 요청을 찾되,
존재하지 않는 intended order를 생성 성공으로 세지 않는다.

**정합성 검증의 필수 항목:**

| 항목 | 데이터/판정 |
| --- | --- |
| 생성 수 | k6 orders_created와 실제 distinct order 대조; response loss이면 불일치를 설명하고 정상 capacity pass 금지 |
| 멱등성 | actor/operation/key별 한 결과, order/reservation/payment 한 logical effect |
| 주문 금액 | subtotal = coupon discount + points applied + payable, line 합과 order 일치 |
| 승인/환불 | APPROVED fact와 k6 관측을 대조; succeeded Refund 합 = Payment succeeded_refund_amount, 승인액 초과 0 |
| 슬롯 | 선택 slot 전체의 RESERVED/CONFIRMED reservation 수와 두 counter 일치, 음수/정원 초과 0 |
| 종료 의미 | COMPLETED는 CONFIRMED 유지, 취소/거절은 owner 정책에 맞는 RELEASED/RELEASED_AFTER_TERMINATION |
| 픽업 번호 | 동일 store/pickupBusinessDate/pickupSequence 중복 0 |
| 혜택 | fixture 계정의 lot/reservation/transaction과 source 대조; 적립 snapshot 기반 정확한 금액, 임의 비율 계산 금지 |
| 보상 | 해당 case의 PAYMENT/PICKUP/COUPON/POINTS/CUSTOMER_NOTIFICATION 상태를 각각 확인 |
| 정산 | 완료 source의 settlement_item 생성/금액, 고객 취소 제외 또는 매장 거절의 명시적 reconciliation 결과 |
| 이벤트 | 실제 listener별 미완료/attempt/oldest age; 미구현 target은 이름·수·증가량을 별도 기록 |
| 알림 | notification_delivery/inbox 원본 결과; scripted ACK는 실제 외부 발송 성공과 구분 |

SQL은 `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY`, 짧은 statement timeout,
항목별 집계와 마지막 ROLLBACK을 사용한다. 전체 row/payload/계정 정보를 출력하지 않는다.
집계 쿼리의 조인 증폭으로 order/refund를 중복 합산하지 않도록 owner별 CTE에서 먼저 집계한다.
SQL 결과 누락·timeout·parse failure는 `NOT_VERIFIED/FAILED`이며 빈 dict나 0으로 치환하지 않는다.
조회 snapshot 사이의 변화는 시각을 기록하고 전체 실행의 atomic snapshot이라고 표현하지 않는다.

**listener 목록:** 소스의 `@ApplicationModuleListener(id=...)`, producer target constant,
실제 실행 등록/로그 증거를 대조해 `active`, `unimplemented`, `unverified`를 지정한다.
이벤트와 cohort의 연결은 event별 `orderId`/원본 owner 관계를 사용한다.
`envelope.aggregateId`가 Refund ID인 이벤트도 있으므로 항상 Order ID라고 가정하지 않는다.
SQL serialized payload는 내부 조사에만 사용하고 보고서에는 유형/수/상태만 보존한다.

**종료 조건:** 유효 fixture, stage별 capacity/driver 예산, 보호된 before snapshot,
위 항목을 실패 예제에서 실제로 잡아내는 로컬 검증기 테스트.

### M2 — 기존 짧은 기준선

**입력:** M0/M1 완료, 유효한 v1 fixture(기존 runner용), target snapshot, 검증된 driver 예산.
v2→v1 변환은 선택한 한 store/slot allocation을 명시적으로 좁히고 별도 dataset ID로 기록한다.

1. 도구 계약 테스트를 먼저 실행한다. 실제 k6 runtime 테스트가 skip이면 k6를 준비하고 재실행한다.
2. `quote-order` 1/s·60초, `idempotency` 1/s·60초, `board-polling` 1 VU·60초를 각각 실행한다.
3. `toss-success`는 1→2→5→10→20/s, 각 60초로 실행하되 통과/예산 조건을 확인하고 한 단계씩 진행한다.
4. 최초 VU 후보는 rate의 4배 또는 최소 4개, pre=max로 시작한다. 고정 정답이 아니므로
   짧은 smoke에서 workflow 시간/분산을 보고 사전 할당한다. 인증 계정 수보다 많은 customer VU를 묵시적으로 재사용하지 않는다.
5. 각 실행은 새 test ID/디렉터리를 사용한다. 예열은 1/s·30초 별도 실행으로 남긴다.
6. 미결제 주문의 정상 취소 또는 만료, PAID의 자동 거절·환불을 관찰한다.
   측정 후 취소를 추가했다면 측정 구간 밖 cleanup으로 표시하고 원본 시나리오를 바꾸지 않는다.
7. `active_recovery_status=PASSED` 전에는 다음 capacity run을 시작하지 않는다.
   미구현 target은 별도 누적값으로 남고 다음 실행의 배경 조건에 반영한다.

**종료 조건:** 첫 실패 또는 상한 20/s까지 모든 시도 원본, k6 gate, 중앙 수집, DB 대조와 회복 기록.
첫 실패가 발생하면 더 올리지 않는다. 기존 2026-09-08 보고서의 통과/실패 수치를 현재 기준선으로 대신 쓰지 않는다.

### M3 — 전체 거래 entrypoint와 실행기 확장

**신규 예정:** `scripts/load/beanflow-journeys.js`, `scripts/load/lib/http.js`,
`scripts/load/lib/journeys.js`, `scripts/load/journeys-runtime.test.mjs`.

**구현 순서:**

1. `lib/http.js`: actor별 cookie/CSRF, bounded route tags, 기대 status+body 검사,
   JSON 오류·중간 실패 기록, test header와 논리 명령 key 생성.
   모든 HTTP 실패는 1회 해당 workflow 실패로 수렴한다. catch 후 성공 반환 금지.
2. `lib/journeys.js`: 위 12단계를 그대로 구현한다. request별 operation key와 응답 field가 다름을 테스트한다.
   `performLifecycle(context)`는 `orderId/reference/paymentId/finalState`를 내부 결과로 돌려준다.
   ID를 metric label이나 일반 stdout에 남기지 않는다.
3. entrypoint: `lifecycle`은 constant-arrival-rate, 보드는 constant-vus.
   기본 request timeout 20초, gracefulStop 60초. iteration 중단은 실패/미확인으로 남긴다.
4. 성공 counter는 실제 검사 뒤 증가한다. started, finished, successes, failures,
   orders_created, payments_approved, orders_completed를 분리한다.
   workflow_duration, active_http_time, think_time과 request/transition별 p95/p99를 기록한다.
5. `run.py`에 `--suite legacy|journeys`를 추가하고 기본값은 legacy로 유지한다.
   선택 entrypoint와 import한 local module 전부의 경로·SHA256을 manifest에 기록한다.
   rate/stages/비율/think-time/actor 수/분산 모드/cleanup/복구 설정도 comparison 조건에 포함한다.
   session rotation 때문에 전체 fixture hash가 달라도 논리 데이터조건이 같을 수 있으므로
   secret을 제외한 canonical dataset digest와 session 포함 fixture digest를 각각 저장한다.
   dataset ID 문자열만 같은 것으로 비교 가능하다고 판정하지 않는다.
6. 새 scenario 6개를 서버 PerformanceTelemetryContextFilter와 테스트에 추가한다.
   rollout 시 새 image의 source SHA를 기록하고 단건 요청으로 400이 아닌지 확인한다.
   배포 전에는 로컬 fixture server 검증만 완료로 보고한다.
   배포 후 target snapshot의 expectedRevision도 실제 새 SHA로 갱신하고 새 기준선을 남긴다.
   M2 legacy 결과와 M3 public API 결과는 서로 다른 workflow이므로 단순 지연 개선율로 비교하지 않는다.
7. 기존 runtime/driver 테스트와 M3 테스트를 함께 통과시킨다.

**종료 조건:** 정상 12단계와 아래 실패 테스트가 실제 k6에서 통과하고, 대상 배포에 신규 진단 계약이 적용됐다면
실환경 lifecycle 단건 1회도 확인한다. 로컬 테스트와 실제 단건은 서로 다른 상태로 기록한다.

### M4 — 취소, 거절, 혼합, 동시 중복

M3와 같은 파일에 API and Event Contracts의 시나리오를 추가하고 독립 테스트를 작성한다.

**혼합 분기 초기값:** 전체 `mixed` arrival의 70% browse, 20% lifecycle, 10% customer-history.
이는 실제 이용 통계가 아니라 고정 실험 가정이다. `iterationInTest % 10`의 0~6은 browse,
7~8은 lifecycle, 9는 history로 결정하고
각 분기 실제 횟수를 집계한다. 짧은 실행에서 목표 비율과 실제 비율 차이도 표시한다.

- browse: GET store 상세 → menus → 선택 menu configurations → pickup-slots.
  빈 응답이 API 계약상 유효하더라도 fixture의 메뉴/슬롯이 사라졌으면 준비 실패로 분류한다.
- lifecycle: M3 12단계를 사용한다.
- history: GET `/api/v1/me/orders?limit=20` → fixture가 소유한 terminal order의 public 상세.
  history용 주문은 사전 준비한 별도 cohort이며 측정 중 없는 주문을 만들거나 다른 고객의 주문을 조회하지 않는다.
- 보드: 선택 10개 매장에 매장당 1 VU, request 뒤 기본 3초 sleep.
  이 주기는 응답 시간+3초이므로 정확히 1/3 RPS라고 단정하지 않는다.
  ETag cache key는 `(merchantActorId,storeId,lane)`이며 200에서 갱신, 304에서는 JSON parse 금지.
  `PENDING_ACCEPTANCE`는 보드 lane이고 action expectedStatus는 `PAID`다.
  overflow는 반환 cursor 그대로 한 page씩 별도 bounded 읽기 테스트로 확인한다.

70/20/10 mixed rate 10/s는 주문 10/s가 아니다. 목표 분기별 시작률과 실제 완료 주문률을 함께 표시한다.
board 요청을 mixed arrival 분모에 합치지 않는다. 여러 process의 percentile을 평균하지 않는다.

취소/거절은 처음에는 각 1건, 이후 1/s·60초를 별도 실행한다.
fault/recovery 검증은 자원 소유 결과를 확인할 수 있는 낮은 부하에서 먼저 통과해야 한다.
쿠폰/포인트는 현금-only가 안정된 다음 전용 고객/benefit allocation의 작은 dataset으로 검증한다.

**종료 조건:** 모든 신규 시나리오의 local negative test와 단건 HTTP/DB 결과,
고객/점주 actor 분리, 취소/환불·혜택 의미, 보드 ETag 범위, 분기별 측정값 확인.

### M5 — 단계 부하, 분산/집중, 지속, 급증

신규 `scripts/load/run-campaign.py`는 한 번에 한 stage만 시작한다.
입력 plan JSON의 `suite,scenario,rate,duration,preVUs,maxVUs,fixture,expected_scope,limits`를 검증한다.
각 stage 전 preflight/예산/session/quiet를 재검사하고 끝나면 verify-run 결과를 기다린다.
실패 후 다음 stage 자동 실행은 금지한다. 같은 실행 ID를 재사용하지 않는다.

campaign JSON은 `{schemaVersion:1,campaignId,targetConfig,outputDir,stages:[...]}`다.
각 stage는 `{id,suite,scenario,rate,duration,preVUs,maxVUs,fixture,expected_scope,limits}`를 갖고,
`expected_scope`는 검증할 `orderFinalState,activeListenerIds,unimplementedTargetIds`를 명시한다.
`limits`의 `maxNewOrders,maxNewPayments`는 0 이상의 정수이며 조회-only stage에서는 0이다.
`maxHttpRequests,maxWallSeconds,recoverySeconds`는 양수다. 해당 stage의 예상 생성량과 모순되면 거부한다.
board stage는 rate 대신 `boardVUs,boardPollSeconds`, spike stage는 rate 대신
`arrivalStages:[{duration,target}]`를 사용한다. 서로 모순된 executor 필드는 거부한다.
서로 다른 stage의 test ID는 campaignId+stageId+UTC 실행시각으로 생성하되 48자 규칙을 지킨다.
실제 기간/부하가 plan의 예산을 넘으면 다음 iteration/stage를 만들지 않고 ABORTED로 종료한다.
동시 VU의 생성량 제한은 공유 JS counter에 의존하지 않는다. stage의 executor/rate/duration에서
경계 iteration 여유를 포함한 최대 생성량을 사전에 계산해 예산 안인 설정만 허용한다.
오류·조기 종료는 k6 abort threshold와 runner의 process 제어로 처리한다.
quiet 미충족·파일 누락·이전 ABORTED 실행이 있으면 새 stage를 시작하지 않고 원인을 출력한다.
재개는 `--resume-after <stageId>`를 명시하고 이전 manifest/verification/dataset 상태를 검증한다.
이미 완료한 stage를 자동 재실행하거나 이전 output 디렉터리에 덮어쓰지 않는다.

| 순서 | 실험 | 초기 설정 | 다음 단계 조건 |
| --- | --- | --- | --- |
| 1 | 단건 + 예열 | lifecycle 단건, 이어 1/s·30초 | correctness/fixture/ingest 확인 |
| 2 | 분산 ladder | lifecycle 1→2→5→10→20/s, 각 5분 | 매 단계 모든 scope gate 통과/회복 |
| 3 | 집중 비교 | 같은 rate/기간·고객·대기, 1매장/소수 슬롯 | 충분한 누적 정원, 같은 코드/설정 |
| 4 | 경계 재측정 | 첫 실패 아래 마지막 통과 rate, 5~10분씩 최대 3회 | 각각의 결과와 배경 상태 보존 |
| 5 | 혼합 | M4 비율, 1→2→5→10→20 mixed/s | 분기별 gate 및 board 결과 확인 |
| 6 | 지속 | 안정적으로 반복 통과한 rate의 최대 70%, 30분 | driver/slot/계정 예산, 활성 적체 통제 |
| 7 | 급증 | 60초 안정 → 30초 2배 → 120초 안정 | 2배값도 이미 통과한 상한 이내, 회복 확인 |

처음에는 20/s를 넘기지 않는다. 20/s가 모두 통과해도 최대 용량을 찾았다고 하지 않는다.
그 이상 필요하면 실행 자원/데이터/시험 시간 예산을 새 stage로 구체화한다.
급증은 `ramping-arrival-rate`를 사용하고 stage 배열을 manifest에 저장한다.
실험값이 미확정인 CLI 입력에는 무제한 duration/rate 기본값을 두지 않는다.

VU 산정은 smoke의 workflow 시간 × rate에 변동 여유를 더하고 pre=max로 실행한다.
초기 `4×rate`가 부족할 수 있다. CPU/메모리와 실제 VU 사용량을 함께 보고, 변경 시 새 test ID로 재실행한다.
고객 계정 수가 부족하면 VU를 늘려 같은 고객을 몰래 공유하지 않는다.
동시 사용자 수 목표가 추가되면 별도 closed-model 시나리오로 명시하고 arrival 결과와 분리한다.

DB 누적 크기/정산/Analytics target이 계속 증가하므로 연속 실행은 동일 snapshot A/B가 아니다.
`comparison.json`의 조건 일치와 실제 dataset 상태 일치는 별도 검사한다.
비교할 수 없으면 설정 차이/행 수/적체 차이를 나열하고 개선율을 계산하지 않는다.

### M6 — 제한된 장애와 회복

M5 정상 측정과 별도 test ID·시간 구간으로 실행한다. 한 번에 한 장애만 사용한다.

| 시나리오 | 실행 예산 | 기대 관측 / 종료 |
| --- | --- | --- |
| decline | 1/s·30초 | confirmation 422와 PAYMENT_DECLINED, Payment FAILED/Order CANCELLED, 예약 해제 |
| timeout | 최대 3건 | 최초 202/UNKNOWN 또는 RECONCILING, 같은 Provider key lookup, 승인/late approval 정책 확인 |
| unknown | 최대 3건 | 계속 IN_PROGRESS인 driver에 대해 명시 상태 유지, 정책의 마지막 자동 조회 후 MANUAL_REVIEW |
| acceptance-timeout | 3건 승인 후 새 부하 없음 | 2분 경고·3분 자동 거절, 후속 refund/각 owner 결과 |
| db-lock | fixture slot 1개, hold 15초, 1/s·20초 | PostgreSQL wait/Hikari/trace 상관, 35초 이내 rollback과 이후 복구 |

unknown의 관찰 시간은 코드의 실제 nextAttemptAt/claim schedule을 읽고 정한다.
기본 전체 관찰 예산은 30분이며 도중 상태를 기록한다. 예산을 넘으면 PENDING이지 성공이 아니다.
정책 시간을 시험 편의를 위해 축소하거나 운영 DB 시각을 수정하지 않는다.
fault run은 정상 HTTP p95 1초를 적용해 "제품 실패"라고 오판하지 않도록 별도 threshold를 선언한다.
대신 기대 오류의 정확한 code/state, 예상하지 못한 오류 0, 중복 부수효과 0과 복구 판정을 강제한다.

DB lock은 M0에서 수정·검증한 helper만 사용한다. 테스트 소유 slot에 대해
짧은 FOR UPDATE 후 자동 ROLLBACK하며 DB/container 중지나 강제 kill은 범위에 포함하지 않는다.
복구 이후 같은 종류의 정상 요청이 다시 성공하는지 확인하고 활성 lock waiter/새 error 증가를 확인한다.

### M7 — 보고서와 인계

신규 결과 문서 경로는 `docs/quality/performance-capacity-YYYY-MM-DD.md`로 한다.
실제 결과가 없을 때 빈 성능 보고서나 가상 수치를 만들지 않는다.

각 실행에서 아래 한 행을 작성하고 증거 파일의 digest와 위치를 연결한다.

| 필드 | 내용 |
| --- | --- |
| 정체성 | test ID, source SHA, 실제 image/revision, DB/dataset digest, driver generation |
| 부하 | scenario/mix, rate/stages, 시간, VU, 고유 고객·점주·매장, HTTP 총수/실측 RPS |
| 결과 | started/finished/success/failure, orders_created/completed, payments_approved, refund 성공 |
| 지연 | route별 HTTP p50/p95/p99, workflow p95/p99, 명시한 think time |
| 포화 | 같은 시간 범위의 앱 서버 Hikari/DB waits/CPU/메모리/GC/I/O/throttling/OOM |
| 조사 과정 | 실제 관측 시각, Dashboard/Explore 절대 시간 링크, 후보·반대 증거, 이후 확인한 코드와 재측정 |
| 정합성 | 각 항목 검증 수·불일치 수, 미검증/미구현 범위 |
| 회복 | 종료 후 활성 적체·oldest age, 처리 완료 시각/계속 남은 상태 |
| 판정 | load/integrity/telemetry/active recovery/downstream scope/capacity 각각 |

설명 순서는 `도착 부하 → 사용자 영향 → 최초 포화 자원 → 회복`이다.
"테스트에서 20/s를 잠시 통과"와 "30분 동안 정합성을 유지하며 반복 통과"를 구분한다.
첫 실패가 발생기/fixture/driver 한도라면 BeanFlow 최대 처리량으로 보고하지 않는다.
앱 서버 관측만으로 발생기 한계를 배제하지 않으며, 원인을 좁히지 못한 실패는 미확정으로 기록한다.
Grafana에서 후보를 좁히지 못한 항목은 `OBSERVABILITY_GAP`으로 남기고, 코드를 통한 유력 추정과 구분한다.

## Required Tests

로컬 HTTP fixture server는 요청 순서/actor/key/body/응답 계약을 검증하고 실제 k6를 실행한다.
문자열 regex 검사만으로 신규 흐름이 동작한다고 판단하지 않는다.

| 영역 | 반드시 재현할 실패/경계 |
| --- | --- |
| fixture | 빈 계정, 같은 actor 중복, 잘못된 membership, 지난 slot, 정원 부족, 미지 schema, secret 출력 |
| 인증 | 로그인 후 CSRF rotation, customer/merchant cookie 혼입, 401/403/429, 초기 비밀번호 계정 |
| 정상 흐름 | 정확한 12단계, publicReference/UUID 혼동, 잘못된 amount, 다른 주문 응답, flat status 혼동 |
| 실패 집계 | 중간 500, invalid JSON, 200+잘못된 body, abort/timeout; success/completed 과대 집계 부재 |
| actor/key | 고객 key가 매장 action에 재사용되지 않음, action마다 새 key, 논리 재시도에는 동일 key |
| 취소/거절 | 202 후 계속 pending, SUCCEEDED 환불이지만 실패한 benefit owner, 재조회 없이 성공 판정 부재 |
| 동시성 | 동시 201/201 동일 ID, 201/409 in-progress, 다른 ID replay, changed payload conflict |
| 보드 | 매장/actor/lane별 ETag, 304 empty body, stale cursor, 타 매장 데이터 유출 부재 |
| 혼합 | deterministic 분기 비율, scenario별 rate, 주문률과 전체 workflow rate 구분 |
| runner | import 파일 변경 시 hash 변화, 논리 fixture 변화 검출, snapshot 10분 초과, output/test ID 재사용 거부 |
| runner 실패 | summary 없음, remote write 실패, 중앙 series 없음, process SIGINT, 다음 stage 중단 |
| 검증기 | SQL NULL/timeout/누락, 조인 중복 합산, 주문 응답 유실, owner별 mismatch, absent listener |
| target helper | 다른 DB/project/revision, 원본 DB, 미허용 slot, malformed input, secret 미출력 |
| 서버 allowlist | 기존 이름 전부 보존, 새 6개 허용, 임의 이름/잘못된 test ID 거부, MDC scope 정리 |
| 관측성 | transactionid/다른 DB 잠금, worker 실패/stale/부분 실패, batch보다 큰 backlog, 필터 혼입, OTLP 전송 실패, 내부 span 종료 |

PostgreSQL 집계 검증기는 실제 migration을 적용한 격리 Testcontainers DB에서
슬롯 counter/환불 집계/멱등 row의 일치·불일치를 검증한다. 테스트 전용 DB에서만 실패 fixture를 만든다.
실제 앱 테스트는 아래 기존 test class를 근거로 사용하며 필요한 새 assertion만 추가한다.
실행 도구를 위해 제품 상태 전이나 공개 계약을 바꾸지 않는다.

## Validation Commands

### 현재 있는 로컬 명령

```bash
git status --short
git rev-parse HEAD
k6 version
node --version
python3 --version
node --test scripts/load/load-contract.test.mjs scripts/load/k6-runtime.test.mjs infra/perf/toss-driver.test.mjs
python3 scripts/load/run-contract.test.py
bash scripts/verify-docs.sh
git diff --check
```

실제 k6가 없으면 runtime test가 skip될 수 있다. skip을 Passed로 합치지 않는다.
현재 CI가 고정한 k6 버전을 확인해 발생기에서도 같은 버전을 사용한다.

서버 allowlist/계약 검증이 필요한 경우 Java 21과 Docker를 준비하고 아래를 실행한다.
로컬 대규모 build/test와 원격 부하 발생기를 같은 장비에서 동시에 실행하지 않는다.

```bash
./gradlew test \
  --tests '*PerformanceTelemetryContextFilterTest' \
  --tests '*RuntimeOpenApiParityTest' \
  --tests '*StoreOrderLifecycleIntegrationTest' \
  --tests '*StoreOrderBoardIntegrationTest' \
  --tests '*PublicOrderReferenceTest' \
  --tests '*CustomerCancellationCommandIntegrationTest'
```

구현 범위에 맞는 테스트를 먼저 실행한다. backend를 변경했으면 저장소 DoD에 따른 build/정적 분석/구조 검증과
실제 packaged perf 기동 검사도 수행한다. 문서만 변경한 단계에 거래 테스트 통과를 주장하지 않는다.

### 기존 baseline 실행 — M0/M1 후에만 사용

아래 경로/값을 실제 준비한 private 파일로 설정한다. 예시 `/secure` 파일이 존재한다고 가정하지 않는다.
현재 배포의 snapshot은 실행 전 10분 이내에 발생기에 안전하게 전달한다.
공개 origin만 지정했다고 snapshot/profile/DB 검사가 면제되지 않는다.

```bash
export BEANFLOW_BASE_URL=https://beanflow.dhkim.cloud
export BEANFLOW_TEST_ID="bf-q1-$(date -u +%Y%m%dT%H%M%SZ)"
export BEANFLOW_LOAD_FIXTURE=/secure/beanflow/legacy-fixture.json
export BEANFLOW_DATASET_ID=bf-legacy-v1
export BEANFLOW_TARGET_CONFIG_FILE=/secure/beanflow/target-config.json
export K6_PROMETHEUS_RW_SERVER_URL=http://172.16.16.18:9090/api/v1/write
export BEANFLOW_LOAD_SCENARIO=quote-order
export BEANFLOW_RATE=1
export BEANFLOW_DURATION=1m
export BEANFLOW_PREALLOCATED_VUS=4
export BEANFLOW_MAX_VUS=4
python3 scripts/load/run.py --output-dir "$HOME/beanflow-load-runs"
```

Prometheus 주소는 M0에서 실제 endpoint를 확인한 경우에만 쓴다.
`--local-only`는 송신 생략 옵션이지 localhost 대상 제한이 아니다. 공개 origin에 붙이면 실제 쓰기가 발생한다.
로컬 harness 검증에서는 반드시 `http://127.0.0.1:<fixture-server-port>`를 사용한다.

### 구현 후 추가할 CLI 계약 — 현재 미구현

아래 명령은 해당 milestone 구현과 `--help`/negative test가 통과한 다음 사용할 인터페이스다.
새 도구를 이미 존재한다고 보고 호출하거나 성공을 추정하지 않는다.

```bash
python3 scripts/load/prepare-fixture.py \
  --target /secure/beanflow/target-config.json \
  --credentials-file /secure/beanflow/credentials.json \
  --allowlist /secure/beanflow/allowlist.json \
  --output /secure/beanflow/fixture-v2.json

node --test scripts/load/fixture-contract.test.mjs scripts/load/journeys-runtime.test.mjs
python3 -m unittest discover -s scripts/load -p 'test-*.py'
python3 -m unittest discover -s scripts/perf -p 'test-load-target.py'

python3 scripts/load/run.py --suite journeys --output-dir "$HOME/beanflow-load-runs"
python3 scripts/load/verify-run.py \
  --manifest /secure/beanflow/runs/TEST_ID/manifest.json \
  --target /secure/beanflow/target-config.json \
  --allowlist /secure/beanflow/allowlist.json \
  --output /secure/beanflow/runs/TEST_ID/verification.json
python3 scripts/load/run-campaign.py \
  --plan /secure/beanflow/campaign.json \
  --output-dir "$HOME/beanflow-load-runs"
```

`--suite journeys`에는 v2 fixture와 신규 scenario 환경변수를 지정해야 한다.
verify-run의 target 파일은 비밀 없는 식별/설정만 포함한다. SQL 수집은 검증한 앱 서버 연결 경로에서 실행하며
DB password를 target JSON이나 CLI 인자에 추가하지 않는다. 서버에 verifier/SQL을 배치하는 작업도
파일 hash를 기록하고 해당 실행 범위에서 수행한다.

## Observability

기존 [Performance RCA Runbook](../../operations/performance-observability-runbook.md)과
[Live Operations 안내](../../operations/live-operations-dashboard.md),
[Grafana 진단 준비 계약](../../operations/performance-grafana-diagnosis-readiness.md)을 사용한다.
실행 중 현재 상태는 Live Operations, 같은 실행의 결과/원인 비교는 Performance RCA에서 확인한다.

1. 준비 계약의 필요한 O1~O6 gate를 확인하고 manifest의 시작/종료와 서버 UTC 시각을 맞춘다.
   Grafana에서 선택한 environment/host의 앱·DB metric과 trace/log 수집을 확인한다.
2. Grafana의 앱 서버 HTTP route p95/p99·실패율과 자원 지표에서 이상 시점을 먼저 기록한다.
3. 같은 구간의 Hikari active/pending/usage, PostgreSQL wait/lock/SQL 통계와 blocker snapshot,
   worker freshness/owner별 backlog, 앱 서버 CPU·memory·GC·I/O·throttling·OOM을 Dashboard/Explore에서 비교한다.
4. 후보와 반대 증거를 `investigation.md`에 기록하고, 느린 route exemplar → Tempo의
   JDBC/Provider/업무 단계 → Loki log → Pyroscope sample로 좁힌 뒤 해당 코드·transaction을 확인한다.
   repository span 전체를 SQL lock wait라고 부르거나 중첩 span 시간을 더하지 않는다.
5. 직접 SQL은 좁힌 후보와 정합성의 추가 검증에 사용한다. Grafana에 신호가 없는데 SSH SQL 결과만으로
   원인 조사 gate를 통과하지 않는다. 쓰기 EXPLAIN ANALYZE를 live DB에 실행하지 않는다.
6. 관측→후보→코드 확인 순서의 실제 시각과 절대 시간 링크를 보존한다. 뒤의 결과를 앞의 관측처럼 꾸미지 않는다.
7. 서버 histogram의 단위와 집계 범위를 확인한다. k6 원본 결과는 부하 입력·결과 기록에 사용하며,
   서버 지연과 클라이언트 지연, rolling p95와 whole-run p95, 서로 다른 실행 p95를 혼합/평균하지 않는다.
8. `up`, `pg_up`, scrape error, worker freshness와 OTLP 전송 상태가 확인되지 않으면 No data를 0/정상으로 바꾸지 않는다.
9. 공유 host에 privileged cAdvisor를 새로 켜지 않는다. 현재 허용된 수집 경로를 사용한다.

### 부하 중 대시보드 수정·추가 허용

원인을 더 잘 드러내거나 후보를 검증하는 데 필요하면 실행자는 기존 BeanFlow Grafana 대시보드를
수정하거나 새 진단 대시보드를 추가해도 된다. 패널, PromQL/LogQL/TraceQL 쿼리, 변수, 범례·단위,
시간 비교와 Dashboard/Explore/trace/log/profile 연결을 수정하고 실제 BeanFlow Grafana에 반영할 수 있다.
이 범위는 사전 승인된 작업으로 취급하며 부하 중 필요할 때마다 별도 승인을 요청하지 않는다.

1. 변경 전 해결하려는 질문과 현재 부족한 화면/신호를 `investigation.md`에 기록한다.
2. 기존 dashboard JSON을 private 실행 디렉터리에 보존하고, 기존 Live Operations/RCA의 목적과
   주요 진단 기능을 유지한다. 실험 전용 화면이 더 명확하면 별도 UID로 새 dashboard를 만든다.
3. 관리 소스는 `infra/observability/central/grafana/dashboards/`에 반영한다.
   Grafana UI에서 먼저 변경한 경우에도 최종 JSON을 동기화하고 파일 hash·반영 시각을 기록한다.
4. 대상 환경/host/DB/test ID, 시간 범위·단위·표본 수·결측 처리를 확인하고 관련 query contract를 검증한다.
   실제 Grafana 표시와 Explore 연결도 확인한다. 보기 좋은 결과를 위해 오류나 느린 구간을 숨기지 않는다.
5. 변경 시점과 dashboard UID/panel ID/query·전후 증거를 남겨 이전 결과도 재구성할 수 있게 한다.
   관측 쿼리가 부하에 영향을 주면 새 증량을 멈추고 조회 빈도·범위를 줄인 뒤 영향 구간을 보고서에 표시한다.
6. 표시 변경과 앱/collector 계측 변경을 구분한다. sampling·수집 주기·계측 코드가 바뀌면 manifest에 남기고
   영향받은 성능 비교는 별도 실행으로 재측정한다. 대시보드 반영 승인을 앱 재배포나 상품 정책 변경으로 확대하지 않는다.

### 회복 판정

새 부하 종료 뒤 15초 간격으로 최대 10분 관찰한다. 정상 실행의 초기 기준은 다음과 같다.

- 해당 cohort의 진행 중 Order/Payment/Refund/보상 활성 work가 기대 terminal 상태에 도달.
- 활성 listener/Notification의 미완료 due work가 남지 않고, Hikari pending/DB lock waiter가 정상 기준선으로 회복.
- 60초 동안 연속 4개 관측에서 새 관련 오류/retry 증가가 없고 CPU/GC/connection 사용이 baseline 범위로 회복.
- 미구현 target은 수/oldest age/증가량을 따로 보존한다. 전체 event count=0을 완료 조건으로 강제하지 않는다.
- 위 조건을 확인할 수 없거나 10분을 넘으면 PENDING/FAILED와 실제 owner를 기록하고 다음 증량 금지.

fault의 UNKNOWN 관찰은 M6의 별도 30분 예산을 사용한다.
scheduled 업무가 아직 미래라면 "지금 due work 0"만으로 전체 recovery 완료를 주장하지 않는다.

## Documentation Updates

- 본 ExecPlan의 진행·결정·발견·결과를 단계마다 갱신한다.
- Grafana 준비 계약의 O1~O6 상태와 실제 조사 기록을 유지한다. 계측 변경 전에 ADR-121에
  bounded metric/span/log field와 수집 비용·실패 의미를 기록한다.
- M0/M3에서 실행기와 진단 허용 이름이 바뀌면 performance-observability-runbook과 ADR-121에 bounded amendment를 추가한다.
- 신규 scenario/body/응답은 현재 제품 계약을 소비한다. 제품 API 변경이 없으면 runtime OpenAPI를 바꾸지 않는다.
- 상품 정책/숫자/transaction/Provider 실패 의미를 바꿔야 하면 먼저 해당 Business Policy/ADR을 별도 갱신한다.
- 과거 이관 ADR/도구/heap dump 등 기존 변경은 이 계획의 변경에 섞지 않는다.
- raw credentials, session, 개인식별자, 원본 request/response와 배포 secret을 Git/PR/보고서에 넣지 않는다.

## Progress

- [x] 2026-09-13: 현재 소스·runtime API·부하 도구·정책·기존 증거와 새 이관 기록을 대조해 계획 작성.
- [x] 2026-09-13: 공개 사이트 `/`의 고객/매장/운영/지원 진입 링크 확인.
- [x] 2026-09-13: Grafana 우선 조사 계약 추가. Live/Explore와 중앙 metric을 read-only 확인해 SQL 통계 부재 및 관측성 보완 목록 기록.
- [x] 2026-09-13: ADR-130 누락 진행 예외와 부하 중 Grafana 대시보드 수정·추가·반영 범위 명시.
- [x] 2026-09-13: ADR-130과 연결된 이관 계획·Runbook 복원, 문서 검증 재통과로 누락 예외 종료.
- [x] 2026-09-13: O1~O6 저장소 구현. DB identity/stat-statements/blocker, bounded snapshot,
  environment/host 범위, worker/phase freshness, Alloy delivery와 앱 host 자원 guardrail 반영.
- [x] 2026-09-13: application/test compile, collector/dashboard unit·contract와 격리 PostgreSQL runtime 검증.
- [x] 2026-09-13: 전체 `./gradlew check`의 실행계획 단언 1건 실패를 격리 재실행해 통과 확인하고,
  전체 suite Failed와 격리 Passed를 별도 기록.
- [ ] 2026-09-13: 새 revision 배포 및 실제 Grafana의 O1~O6 수집·표시, trace-log-profile 연결과 overhead smoke.
- [ ] M0: 실제 배포/DB/Provider/관측성 확인과 target/DB helper 보완.
- [ ] M1: 합성 fixture·예산·cohort 정합성/회복 검증기 구현.
- [ ] M2: 기존 짧은 기준선 및 후속 처리 확인.
- [ ] M3: 정상 전체 거래 k6/진단 계약 구현·배포·단건 검증.
- [ ] M4: 취소/거절/혼합/동시 중복 구현·검증.
- [ ] M5: 분산/집중/지속/급증 실행.
- [ ] M6: 제한된 장애와 복구 실행.
- [ ] M7: 원본 결과 대조·보고서·인계.

## Surprises & Discoveries

- 기존 단일 매장 fixture와 승인 종료 흐름으로 장시간 정상 거래를 주장할 수 없다.
- 점주 public transition은 legacy status API와 body/응답 계약이 다르다.
- 새 k6 scenario 이름만 추가하면 perf filter가 400을 반환하므로 앱 allowlist 변경·배포가 필요하다.
- 오늘 이관 기록은 기존 Runbook의 DB/Compose 경로와 다르다. 기존 lock helper는 실행 전 보완 대상이다.
- 새 DB가 비어 있어도 유지된 Toss driver memory가 비어 있다는 뜻은 아니다.
- 완료 주문의 slot confirmed count는 정상 누적 판매량이므로 30분 시험의 데이터 예산이 필요하다.
- Analytics target 등 미구현 소비 범위가 남아 있어 전체 event backlog=0을 무조건 요구하면 종료할 수 없다.
- Grafana SQL 통계가 없는 동안에도 DB exporter는 healthy일 수 있다. 현행 잠금 집계의 transactionid 누락,
  worker gauge 신선도 부재와 RCA 필터 경계를 먼저 보완해야 관측으로 후보를 좁힐 수 있다.
- 격리 PostgreSQL에서는 `pg_stat_statements`와 transactionid wait를 실제 metric으로 재현할 수 있었다.
  저장소 계약 통과는 현재 perf 배포의 collector, central ingest 또는 Grafana 표시 증거가 아니다.

## Decision Log

| 날짜 | 결정 | 이유 / 재검토 조건 |
| --- | --- | --- |
| 2026-09-13 | 현재 자원에서 안정 처리량·첫 병목 탐색으로 목적 확정 | 별도 목표 사용자/주문량이 제시되면 workload와 보고서 acceptance를 갱신 |
| 2026-09-13 | Dashboard/Explore에서 후보를 좁힌 후 코드 확인; 관측 준비를 부하 gate에 포함 | 재현 가능한 실제 조사 순서 필요. 신호 부족은 OBSERVABILITY_GAP으로 기록하고 계측 후 재측정 |
| 2026-09-13 | O6는 앱 서버 자원 관측으로 한정; 발생기·WAF 관측은 구현 및 완료 조건에서 제외 | 앱 서버 병목 조사에 집중. k6 원본 결과·실행 유효성 검증은 유지하고 관측 범위 밖 원인은 미확정으로 기록 |
| 2026-09-13 | ADR-130 누락과 해당 broken link만 진행 예외로 인정, Implementation-Ready=true | 실행 범위에서 명시적으로 제외. 실제 대상·원본 보존·정합성·관측성 검증은 유지 |
| 2026-09-13 | ADR-130과 연결 문서 복원 후 누락 예외 종료 | 문서 링크 검증이 다시 통과하므로 더 이상 실패를 예외로 취급하지 않음 |
| 2026-09-13 | 조사에 필요한 Grafana 대시보드 수정·추가·반영 허용 | 부하 중 질문에 맞는 화면을 만들고 변경 전후 증거와 관리 JSON을 보존 |
| 2026-09-13 | 기존 legacy suite 유지, 새 journeys entrypoint 추가 | 과거 측정 hash/계약 의미 보존; 공통 추출은 필요한 최소 범위 |
| 2026-09-13 | public orderReference/API 기반 전체 흐름 | 고객/점주의 현재 actor·상태 전이 계약 검증 |
| 2026-09-13 | 기본 현금-only, 70/20/10 혼합은 실험 가정 | 운영 통계가 아님; 실제 traffic 자료가 생기면 별도 dataset/experiment로 변경 |
| 2026-09-13 | 1초/1%/dropped=0 초기 guardrail 유지 | 서비스 SLO 신규 확정 아님; 근거 없이 실패 통과를 위해 완화하지 않음 |
| 2026-09-13 | 정상 완료/활성 recovery/미구현 downstream을 분리 | 현재 구현 범위와 성능 주장 한계 일치 |
| 2026-09-13 | O1~O6는 닫힌 label과 bounded collector로 구현하고 live gate를 별도 유지 | cardinality·민감정보·collector 부하를 제한하면서 정적/실환경 증거를 혼동하지 않음 |

## Outcomes & Retrospective

초기 문서 작성 시점의 결과는 구체적인 실행/구현 계획이었다. 2026-09-13에 O1~O6의 저장소 구현과
격리 검증을 추가했다. 제품 API·retry/deadline·transaction 의미는 변경하지 않았다.
실제 서버 revision/DB/자원·중앙 ingest 전체 재확인, 합성 계정 준비, 신규 거래·부하·장애 주입은 **Not run**이다.
공개 첫 화면을 읽은 결과는 API/DB/계측 준비 완료의 증거가 아니다.
후속 관측성 감사에서는 Live/Explore와 일부 중앙 metric 존재를 read-only 확인했다.
세부 시각·쿼리·결과는 연결 준비 문서에 기록했다. 전체 ingest/trace-log-profile 검증과 새 패널의 실제
Grafana 표시는 미완료이며, 따라서 M0의 O1~O3 live gate는 통과로 바꾸지 않는다.

문서 검증 결과는 도구·거래 테스트 결과와 구분한다.
- 최초 작성 당시 `bash scripts/verify-docs.sh`: Passed.
- Grafana 계약 후속 개정 시 `bash scripts/verify-docs.sh`: Failed. 검증기 18개 테스트는 통과했지만
  당시 checkout에 ADR-130 파일이 없어 이관 완료 계획·이관 Runbook의 상대 링크 2개가 깨졌다.
- 후속 개정의 신규 관측성 문서 자체 링크/whitespace 검증: Passed.
- 진행 예외·대시보드 허용 범위 개정: 변경 문서 두 개의 상대 링크/whitespace 검사 Passed.
  전체 검증은 검증기 테스트 18개 통과 후 이관 문서 두 개의 ADR-130 링크 오류만 보고했다.
  이는 복원 전 역사적 결과이며 현재 gate로 사용하지 않는다.
- ADR-130과 연결 문서 복원 후 `bash scripts/verify-docs.sh`: Passed. 검증기 18개와 전체 문서 링크 검사를
  예외 없이 통과했다.
- 전체 `./gradlew check`: Failed (`1,755` tests, `1` failed, `2` skipped). 실패한
  `StoreCatalogQueryMigrationTest`는 PostgreSQL 실행계획 index 선택 단언이며 `--rerun-tasks` 격리
  재실행은 Passed다. 관측성 대상 테스트 통과와 전체 suite 실패를 혼동하지 않는다.

실행을 마치면 각 milestone의 local validation/live validation과 남은 owner/consumer를 따로 기록한다.
요구된 도구와 검증이 완료되고 실패/미구현 범위가 보고서에 명시된 뒤에만 이 계획을 completed로 이동한다.
상한에서 실패가 재현됐다는 사실 자체는 계획 실패가 아니다. 그 원인·부하·정합성·회복 증거를 설명할 수 있어야 한다.

### 다음 실행자에게 전달할 작업 문장

> 이 ExecPlan과 repository AGENTS.md를 읽고 첫 미완료 milestone부터 수행한다.
> 신규 예정 파일을 먼저 구현·테스트하고 기존 파일·원본 DB·결제 이력을 보존한다.
> M0에서 실제 origin/revision/DB/Provider/관측성 경계를 확인하고, M1에서 계정·슬롯·driver 예산과
> 정합성 검증기를 준비한 뒤에만 실제 부하를 시작한다. 정상 흐름은 public orderReference API의
> 정확한 12단계를 따른다. 실패·미수집·UNKNOWN·미구현 target을 성공이나 0으로 바꾸지 않는다.
> Grafana 진단 준비 계약의 선행 항목을 통과하고, Dashboard → Explore → 후보 기록 → 코드 확인 순서와
> 실제 증거 링크를 남긴다. 관측이 부족하면 신호를 보완한 뒤 다시 측정한다.
> ADR-130과 연결된 이관 계획·Runbook을 함께 유지한다. 원인 조사에 필요하면
> Grafana 대시보드를 수정·추가·반영하고, 같은 범위의 재승인 없이 전후 JSON과 조사 증거를 남긴다.
> 매 단계 실제 명령·결과·증거 경로와 다음 시작점을 갱신하고, 승인된 범위 밖 원격 변경은 하지 않는다.

## Revision Notes

- 2026-09-13: 현재 코드와 이관 기록 기반 최초 상세 계획. 실제 측정값은 포함하지 않음.
- 2026-09-13: Grafana 기반 후보 선정과 조사 기록을 필수화하고 별도 관측성 준비 계약 O1~O6 연결.
- 2026-09-13: 후속 문서 검증에서 기존 ADR-130 참조 파일 누락 확인. 선행 결정 확인 전 Implementation-Ready=false로 정정.
- 2026-09-13: 실행 범위 결정으로 ADR-130 누락 차단을 해제하고 Implementation-Ready=true로 복구.
  부하 중 원인 조사를 위한 Grafana 대시보드 수정·추가·반영과 변경 증거 보존 절차 명시.
- 2026-09-13: ADR-130과 연결된 이관 계획·Runbook을 복원하고 문서 누락 진행 예외 종료.
- 2026-09-13: WAF/proxy 로그 수집·연결과 ingress 전용 패널을 관측성 구현 및 완료 조건에서 제외.
  O6의 발생기·자원 관측과 관측 범위 밖 원인의 미확정 기록은 유지.
- 2026-09-13: 후속 범위 축소로 발생기 자원·네트워크 관측과 k6 전송 단계 시간·dropped/VU의 Grafana 연결도 제외.
  O6·조사 절차·보고서 포화 항목을 앱 서버 관측으로 정리하고 k6 원본 결과·실행 유효성 검증은 유지.
