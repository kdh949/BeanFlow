# Test Strategy

| Layer | Purpose | Primary risks |
|---|---|---|
| Domain Unit | 상태 전이, 금액·정책 | 잘못된 상태, 금액 불일치 |
| Application | 유스케이스·Port 순서 | 트랜잭션 경계, 보상 누락 |
| Repository | JPA, SQL, constraint, lock | H2 차이, Lost Update, mapping |
| API Contract | HTTP·오류·인가 | 계약 drift, 잘못된 status |
| Module / Architecture | 경계와 순환 | 내부 패키지 침범 |
| Concurrency | 경합·중복 | oversell, 초과 예약, 이중 사용 |
| Idempotency | 같은 명령·이벤트 반복 | 중복 결제·적립·정산 |
| Resilience | timeout·재시작·ACK 유실 | terminal state 누락, 숨은 실패 |
| Load | 지연·처리량·resource | pool 고갈, lock wait, GC |
| Time | 만료·영업시간·batch | 경계 시각 오류 |

## Environment

- Repository와 통합 테스트는 PostgreSQL Testcontainers를 기본으로 한다.
- 위치 기능은 PostGIS 이미지 또는 확장을 실제로 사용한다.
- 외부 PG·알림은 성공, 명시 거절, timeout, malformed response, ACK 유실을 재현 가능한 Adapter로 테스트한다.
- 운영 profile에서 fake Adapter가 활성화되지 않는 startup test를 둔다.

## Risk-first examples

- 같은 Idempotency-Key 100개 동시 요청 → 승인 부작용 한 번
- 남은 재고 1개에 동시 예약 → 성공 1개, oversell 0
- PG 성공 후 DB write 실패 → UNKNOWN/복구 case, 재승인 없음
- Payment UNKNOWN과 5분 만료 동시 실행 → 한 guarded transition만 승리
- 만료 후 Provider 승인 확인 → Order 비복구, void/refund 한 번, 실패 시 명시적 recovery
- refund timeout → 성공 환불액 미반영, UNKNOWN/reconciliation
- OrderCompleted 중복 전달 → 포인트·SettlementItem 한 번
- 알림 timeout 후 Provider는 성공 → ACK 유실 중복 발송 제어
- 정산 batch 중단 후 재실행 → 중복 Item 0
- DB 장애 → 빈 목록 또는 local repository fallback 없음
- 같은 Platform Operator point adjustment 동시 debit → 하나만 가용 Lot을 차감, 원장·Audit·응답 일치
- PointAccount support read → `POINT_ACCOUNT_READ` row lock/active grant, normalized access reason,
  projection과 exactly-one target Audit이 같은 transaction에 commit; audit/projection failure에는 body 없음

## Customer cancellation release suite

고객 취소 구현은 다음 묶음을 release-blocking suite로 실행한다.

- Domain: 허용/비허용 Order 상태, 정확한 두 deadline 경계, cancellation field CHECK,
  reason code/detail 정규화
- Transaction: Tx C0/C1의 Order·멱등 응답·Case·snapshot·Refund·Delivery·target별
  Audit·event publication 전부 commit 또는 전부 rollback
- Concurrency: 고객 취소 대 수락·timeout·부분 Refund, 같은/different
  Idempotency-Key와 Order→Payment lock 순서
- Refund: 선행 line allocation, request 3회·lookup 5회 독립 상한과 전체 8회,
  Unknown 뒤 REQUEST 0회, missing Refund 복구 뒤 LOOKUP 우선
- Owner compensation: Pickup·Stock·Coupon·Points의 source/trigger/policy 일치,
  duplicate와 conflict, 한 publication 소진 시 한 step만 manual review
- Benefit policy: 종료용 trigger×benefit 네 head와 PARTIAL_REFUND×POINTS 한 head,
  종료 Case의 두 immutable FK/event snapshot, 부분 환불 Refund의 POINTS policy FK,
  expired coupon terms·cost snapshot과 future settlement tie-out
- Notification: 두 상태의 접수 Delivery commit gate, 환불 성공·지연 terminal event,
  기본 step 단조성, 보상 전체 완료 알림 부재
- Settlement: 미완료 고객 취소 Refund의 Item/Adjustment 0건, source당 exclusion
  Audit 한 건, 실제 Order/Refund source·version·amount·time 검증, 기존 Item conflict와 Audit
  rollback, persistent publication이 Audit commit 뒤에만 완료됨
- Operations: setup immediate detector+batch 100 scanner, unique case/Audit, 제한 복구
  guard와 서로 다른 operator 2인 승인·30분 만료, terminal Refund 단일 operator
  LOOKUP-only grant/replay/concurrency/terminal result
- Retention: cancellation/store/repair/reconciliation idempotency table별 90일 chunk,
  repair proposal 자동 만료와 timeout work nonterminal 보존
- API/Privacy: 200/202/409/503, customer setup 지연 projection과 조건부 금액,
  `replayed` 부재, event/log/Audit의 detail·customer/store/reason/provider key 금지
- Refund contention: 선행 Refund 여섯 미확정 상태
  (`REQUESTED`/`PROCESSING`/`RETRY_SCHEDULED`/`UNKNOWN`/`RECONCILING`/`MANUAL_REVIEW`)
  각각의 `409 PAYMENT_REFUND_UNRESOLVED`와 `SUCCEEDED`/`FAILED`의 허용
- Recovery summary: 선행 전액 환불로 요청액이 0이 된 취소의 `NOT_REQUIRED`와 양수
  `approvedAmountKrw`, `BENEFIT_ONLY`의 네 금액 0, `PENDING_PAYMENT`의 네 금액
  생략, 네 금액 all-or-nothing
- Order projection: 고객 `Order`의 `cancelledAt`·`cancellationCause`·
  `cancellationReasonCode` 노출, 매장 `StoreOrder`의 `cancellationReasonCode`·
  `paymentRecovery` 부재, 두 projection의 `detail` 부재
- Compensation projection: 매장 응답의 step 배열·`attemptCount`·`lastErrorCode`·
  `caseId`·policy version 부재와 `trigger`·case `state` 존재, 운영자 응답의 여섯
  step 존재
- Pre-release gate: compensation legacy row, V1 publication 또는 external consumer가
  하나라도 있으면 clean cutover 차단

## Settlement lifecycle release suite

- Domain/DB: Batch `OPEN → CALCULATED → CONFIRMED`, summary·carry tie-out, confirmed mutation
  금지, Adjustment append-only/source unique, Dispute active partial unique와 실제 새 evidence 1회
- Calculation: 500건 keyset chunk, 같은 Batch 동시·재실행, multi-store, 서울 자정, 이전 Batch
  confirmation 선행, Item/Adjustment overflow와 creation-time high-watermark
- Refund: confirmed Item Adjustment, unconfirmed publication retry, non-success와 pre-acceptance
  Adjustment 0건, source payload conflict ReprocessingCase와 기존 exclusion 회귀
- Dispute/API: D+1 inclusive/D+15 exclusive fixed Clock, OWNER active membership, staff/revoked 거부,
  exact 201 replay, changed payload 409, active duplicate, immediate previous/new evidence/second refile
- Decision recovery: accepted Adjustment 선커밋, decision Audit/publication rollback 뒤
  `UNDER_REVIEW`와 manual Case, exact Adjustment replay 뒤 terminal event와 Case resolve;
  rejected/withdrawn held 0과 Adjustment 0건
- Contract/architecture: Batch/Item signed cursor scope와 order, `Settlement*V1` exact payload 및
  민감 field 부재, 실제 target listener completion, Spring Modulith dependency direction
- Performance evidence: PostgreSQL 17.6 고정 1,000 Item fixture에서 500-row
  `EXPLAIN (ANALYZE, BUFFERS)`, calculation/confirmation duration과 제어된 row-lock wait를 기록한다.
  단일 로컬 값은 SLA나 개선율로 사용하지 않는다.

성능 수치는 구현 후 같은 PostgreSQL fixture, 동일 batch와 동시성 조건에서 기준선과
함께 측정한다. 측정 없이 scanner, lock 또는 payload 성능 개선을 주장하지 않는다.

## PointAccount read suite

- API/security: customer ownership 200/403/404, unauthenticated 401, operator role+active
  `POINT_ACCOUNT_READ` grant+reason만 200, invalid reason/cursor/limit 400
- Ledger projection: actual `recoveryPendingKrw`, public signed effect, internal Lot/effect field absence,
  `(occurredAt DESC, transactionId DESC)` tie order, account-bound signed cursor and `limit + 1`
- Failure/observability: audit persistence and inconsistent ledger fact are 503 with no success body; metrics use
  only `actor_type` and closed `outcome` tags
- Persistence/performance: PostgreSQL 17.6 V32 migration and [fixed 5,000-row query-plan evidence](../quality/point-account-read-performance-evidence.md)

## Nearby store Discovery suite

- Contract validation: latitude/longitude `[-90,90]`/`[-180,180]` 경계와 finite plain decimal,
  missing parameter, radius `1/10000/10001/0`, limit `omitted/1/100/101/0`, cursor `empty/2048/2049`.
  모든 검증은 spatial query 이전에 실행되고 `INVALID_REQUEST` 400을 반환한다.
- Canonicalization: `37.5`와 `37.5000`, `0`/`-0`/`0.0`/`0.000`이 같은 filter hash를 만들고
  radius 또는 좌표를 바꾸면 hash가 달라진다. sort adapter는 unsigned decimal micrometer와
  lowercase canonical UUID만 round-trip한다.
- Spatial query: radius 경계 안/밖, 같은 거리의 store-ID tie와 3-page 완주에서 gap/duplicate 0,
  disabled·pickup-disabled store 제외, floored integer meter 응답.
- Cursor: 다른 radius/좌표 filter, 다른 endpoint scope, unknown key ID, signature 변조, 만료 token,
  oversized/empty token이 모두 400이며 첫 page로 조용히 되돌아가지 않는다.
- Privacy: 응답 body, 400 error body, metric tag와 `operations_audit_record`에 원본 좌표가 없고
  Discovery read는 audit record와 domain event를 만들지 않는다.
- Failure: 주입한 spatial 실패가 `DEPENDENCY_UNAVAILABLE` 503과
  `beanflow.discovery.spatial.failure{reason}`로 관측되고 빈 200이나 local 계산으로 대체되지 않으며,
  원인 제거 뒤 같은 요청이 정상 결과를 돌려준다.
- Migration/startup gate: V33 schema와 GiST index, `merchant_store` 컬럼 불변, empty/exact coverage
  통과, unresolved row의 migration 중단, missing/orphan/blank-name/non-point profile과 extension
  제거의 startup 실패.
- Persistence/performance: PostgreSQL 17/PostGIS 3.5 Testcontainers와
  [고정 5,000-row query-plan evidence](../quality/nearby-store-discovery-performance-evidence.md).

## Query tests

N+1은 FetchType 이름만으로 판단하지 않는다.

1. 필요한 API 필드 정의
2. 발생 SQL과 쿼리 수 관찰
3. Projection, Fetch Join, EntityGraph, Batch Fetch 비교
4. pagination, row duplication, memory 영향 확인
5. 회귀 테스트와 실행계획 저장
