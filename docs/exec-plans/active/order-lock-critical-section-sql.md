# 주문 잠금 구간의 이력 집계와 새 감사 기록 존재 조회 제거

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/order-quote-shared-resource-stability.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

견적의 공유 사용량 무효화를 해결한 뒤 남은 재고·픽업 슬롯 잠금 대기를 줄인다.
잠금 보유 중 실행하는 불필요한 SQL을 없애고, 동일 조건의 실제 부하로 효과와 한계를 확인한다.

## Current State

- 배포 revision `5aaec5281dbebf88b7465dbc294152fb3003019c`는 quote v3이며 사용량 변화만으로
  견적을 무효화하지 않는다. owner lock 아래의 현재 잔여량 검증은 유지한다.
- `PickupSequenceAllocator.next`는 정상 주문마다 과거 주문과 슬롯을 count/max 집계한다.
  초기 조사 누적 4,602회 평균 1.459ms, 후속 5/s 구간 301회 평균 2.35ms였다.
- `AuditRecordService.appendAll`의 새 UUID entity는 `saveAllAndFlush`의 merge 경로에서
  존재 SELECT 후 INSERT된다. Toss 성공 workflow는 주문 생성 3개, 승인 4개 감사 기록을 남긴다.
- 재측정 `bf-0908-locks-before-r20`은 20/s, 90s, 80 VU에서 승인 1,659건, 실패율 0,
  dropped 142, HTTP p95 1,744.4ms, workflow p95 6,362.9ms로 기준에 실패했다.
  앞선 실행의 3분 점주 접수 기한 만료가 겹쳐 자동 거절·환불·복구와 event backlog가 발생했다.
  이 혼합 결과를 고립된 주문 부하와 직접 비교하지 않는다.
- DB blocker 표본에는 stock/slot 대기와 audit/결제 후속 SQL을 실행하는 잠금 보유자가 함께 보인다.
  repository span에는 connection 획득도 포함될 수 있다. 실제 JDBC span 없는 시간을 행 잠금으로
  단정하지 않는다. 2.17s 주문 trace의 첫 DB span은 0.85s 뒤 시작해 pool 대기가 함께 존재한다.

## Definitions

- 잠금 구간: owner 행의 배타 잠금을 얻은 뒤 같은 transaction을 commit/rollback할 때까지.
- 정상 발급: 매장·영업일 카운터가 존재하는 경우의 원자 증가.
- 초기 발급: 카운터가 없어 이력 기준선을 복구한 뒤 삽입/동시 증가하는 경로.
- 고립 비교: 앞선 테스트의 PAID 주문 및 미완료 event/복구 작업이 배경 부하를 만들지 않는 조건.

## Scope

### In Scope

- Ordering 픽업번호 카운터의 UPDATE 정상 경로 및 기존 초기화 UPSERT 보존.
- Operations 새 감사 기록 전용 persist/flush repository와 예외 변환 보존.
- 명시적 perf Toss driver의 환불 멱등키·중복 취소 계약 수정과 새 주문의 timeout/refund 회복 검증.
- 회귀·동시성·원자성·SQL 비용 검증, API 이미지 build/deploy, Grafana 실제 관측.

### Non-goals

- owner 잠금 제거/순서 변경, 예약·감사 비동기화, Hikari 확대, 새 dependency·스키마·API 변경.
- 점주 접수 기한이나 자동 거절 정책 변경, 관련 없는 live dashboard 작업의 수정/커밋.

## Business Rules and Invariants

BR-05/BR-25/BR-30/BR-49와 ADR-005/006/022/097/123을 유지한다. 현재 재고·정원을 owner lock
아래 검사하고 상태·예약·정산 입력·감사·멱등 응답을 함께 commit한다. 같은 매장·영업일의 순번은
유일하고 커밋 후 재사용되지 않는다. rollback은 카운터도 복구한다. 감사 기록은 수정하지 않으며
중복 source key 또는 저장 실패는 owner write와 함께 rollback한다. 보존 정책과 개인정보 검증은 유지한다.

## Architecture and Transaction Boundaries

`OrderDisplayIdentityAllocator.kt`는 MANDATORY 경계를 유지한다. 정상 UPDATE가 0행일 때만
기존 baseline UPSERT를 실행하고 DB 오류는 전파한다. `AuditRecordService`는 모든 command를
검증·snapshot한 뒤 같은 transaction의 전용 `@Repository` writer에 새 entity를 전달한다.
writer는 persist 후 flush하여 기존 Spring DataAccessException 변환과 호출 시점의 실패를 유지한다.
기존 조회 repository는 조회에 사용한다. 외부 Provider 호출은 기존 Tx1/Tx2 밖에 남는다.

## Alternatives Considered

- 기존 전체 집계 유지: 복구 목적의 비용을 모든 주문과 그 잠금 보유 시간에 부과한다.
- 정상 원자 UPDATE + missing UPSERT: 기존 row 경합/rollback 보장과 초기화 복구를 유지해 선택한다.
- 카운터 별도 transaction/cache: rollback과 비재사용 계약을 바꾸므로 채택하지 않는다.
- 새 audit에 merge 유지: 존재할 수 없는 새 ID 조회를 반복한다. 전용 persist는 저장 의도를 명확히 한다.
- 일반 entity의 isNew 규약 전체 변경 또는 JDBC batch 전환: 더 넓은 영향에 비해 이번 근거가 부족하다.

## Failure Semantics

DB 장애·중복 감사·counter overflow는 예외로 전체 transaction을 실패시킨다. fallback·silenced error는
추가하지 않는다. 새 writer는 `@Repository` 예외 변환을 유지한다. 배포는 immutable image와 기존
override를 백업하고 API만 재생성한다. health/config 검증 실패 시 직전 image로 복구한다.

## Data and Migration

Flyway 변경 없음. V50/V51/backfill의 authority와 카운터 보존 조건을 ADR-097에 명확히 한다.
실제 부하 fixture는 기존 합성 store/menu/customer를 유지하고 새 미래 슬롯 4개를 한 번 생성해
전후 같은 ID와 입력을 사용한다. 기존 주문·슬롯·DB volume을 삭제/재작성하지 않는다.

## API and Event Contracts

공개 payload/status/error 및 event schema 변경 없음. 감사 granularity·retention 그대로 유지한다.

## Milestones

1. ADR/계획 기록 및 고립 기준선, 혼합 부하 원인 증거 확보.
2. 성능 경로 regression을 먼저 실패시킨 뒤 최소 구현과 관련 회귀 검증.
3. 범위 제한 commit/push 및 exact SHA build workflow 성공 확인.
4. Doppler를 유지하는 API-only 배포, runtime revision/health/dependency identity 검증.
5. 같은 fixture/rate/VU/기간으로 재측정하고 Grafana·DB 정합성과 회복 확인.

## Required Tests

- 정상 counter 발급이 ordering_order/slot의 table lock에 막히지 않음 (이력 조회 제거 regression).
- 최초 동시 20건, 기존 카운터 증가·rollback, missing counter 기존 이력 복구, 독립 날짜·매장.
- 새 audit N건에 존재 조회가 추가되지 않는 SQL 수 회귀, 중복/혼합 batch rollback, 기존 PII·retention.
- Ordering 생성/결제/견적 마지막 재고·슬롯 동시성, 멱등성, 실패·rollback 및 구조 테스트.

## Validation Commands

Java 21 및 PostgreSQL Testcontainers로 targeted Gradle test, spotlessCheck, bootJar,
`bash scripts/verify-docs.sh`, 관련 deployment/observability contract를 실행한다.
부하 발생기와 local compile/test를 동시에 실행하지 않는다. remote image workflow의 정확한 SHA와
terminal 결론을 확인한다. 실행 command와 결과는 Progress/결과 보고서에 추가한다.

## Observability

native k6 summary, manifest 조건/hash, pg_stat_statements query ID의 구간 delta,
Hikari active/pending, DB waits/locks, CPU/I/O/GC, event backlog, trace를 보존한다.
SQL 원문/PII/secret/fixture session은 증적에 포함하지 않는다. counter timer와 실제 SQL 비용을
구분하고 latency 개선은 동일 조건 비교로만 주장한다. 20/s 고립 비교는 90s로 3분 접수 만료 이전에
끝내며 앞선 warmup 주문의 보상 완료를 확인한다. 혼합 부하의 별도 한계도 기록한다.

## Documentation Updates

ADR-097 정상/초기 발급 분리, ADR-022 append 전용 저장 구현 명시, 본 계획과 실제 결과 보고서.
완료 시 completed 이동과 inbound link 수정, 문서 검증을 같은 commit에 포함한다.

## Progress

- [x] 현재 revision/SSH/health 확인, dirty live dashboard 관련 6개 파일 제외.
- [x] 코드·trace·DB 대기 조사 및 혼합 background work 식별.
- [ ] 고립 기준선 및 regression 실패 증거.
- [x] 기존 코드에서 정상 counter 이력 접근 및 audit SQL 수 regression 2건 실패 확인.
- [x] 수정 후 14개 class/110개 test 통과 (failure/error/skipped 0), API bootJar 성공.
- [x] driver 9개 test, observability contract, 문서 검증 통과.
- [ ] CI 검증.
- [ ] 배포 및 Grafana 전후 검증.

## Surprises & Discoveries

3분 점주 접수 timeout이 앞선 테스트와 다음 테스트의 부하를 결합한다. Hikari pending 82는
80 VU 이외의 background consumer도 공유 pool을 사용한다는 증거와 함께 해석해야 한다.

추가 조사에서 perf driver가 `refund:rejection:<eventId>` 멱등키의 콜론을 거절해
`TOSS_CANCEL_INVALID_REQUEST`를 반환함을 확인했다. 이후 조회는 cancels가 없어
`TOSS_REFUND_LOOKUP_AMBIGUOUS`로 남는다. 조사 시 REQUESTED 101, UNKNOWN 4,329,
MANUAL_REVIEW 2,177건으로 앞선 실행의 환불이 미완료였다. `event_publication=0` 및 PAID=0만으로
고립 여부를 판단했던 `bf-0908-locks-isolated-before-r20`도 실제로는 환불 background가 남아 있다.
이 명칭을 격리 증거로 사용하지 않는다. driver 계약 회귀와 새 workload의 terminal recovery를 추가한다.
과거 불명 결과를 SQL로 SUCCEEDED로 바꾸거나 자동으로 재요청하지 않는다.

Toss 공식 [멱등키 헤더](https://docs.tosspayments.com/reference/using-api/authorization)는 최대 300자를
지원한다. driver는 HTTP에서 허용되는 비어 있지 않은 printable ASCII key를 300자까지 받고,
같은 payment/key/payload의 취소는 최초 응답을 재생하며 다른 payload는 명시적 충돌로 종료한다.
confirm 재시도 역시 저장된 취소 상태를 초기화하지 않는다. live driver 교체는 API와 별도 변경으로
기록하며, 메모리 상태의 소실과 오래된 UNKNOWN을 전후 성능 비교의 제약으로 남긴다.

## Decision Log

- 2026-09-08: 사용자 승인 범위에서 정합성 경계 대신 잠금 중 불필요한 SQL 두 경로를 줄인다.
  전체 latency의 유일한 원인이라고 주장하지 않고 직접 SQL 비용과 end-to-end를 각각 검증한다.

## Outcomes & Retrospective

로컬 구현 검증 완료. 초기 공유 build 산출물의 JPA entity 인식 오류와 동시 생성으로 clean 실패가
발생해 detached 검증 checkout에서 재실행했다. 이전 코드 19개 test 중 의미 있는 regression 2개만
실패했고 수정 후 선택한 110개 test가 모두 통과했다. missing counter의 legacy count/max 복구,
20건 최초 동시 발급, 정상 counter rollback과 audit 중복 batch rollback을 포함한다.
새 Grafana panel 151/152는 기존 패널 불변을 검증한 뒤 반영하고 브라우저에서 refund unknown의
계속되는 조회 부하를 확인했다. 서버 API/driver 배포와 실제 개선 판단은 Pending이다.

## Revision Notes

- 2026-09-08: 실제 runtime과 baseline 진단을 반영한 최초 계획.
- 2026-09-08: 환불 driver 계약 오류와 미완료 환불 background 발견을 반영해 검증 범위 확대.
