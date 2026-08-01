# 부분 환불 allocation과 적립 포인트 회수 foundation을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-00-contract-baseline.md`, `docs/exec-plans/active/signed-cursor-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

선행 부분 환불이 있는 미수락 `PAID` 주문을 고객이 전체 취소해도 승인액을 초과해
현금을 환불하거나 이미 복원한 포인트를 다시 복원하지 않도록 line-level allocation
원장을 먼저 완성한다. 부분 환불의 coupon allocation은 복원이 아니라 귀속·감사
원장으로 명시해 이후 전체 종료의 단일 쿠폰 복원을 방해하지 않는다. 성공 Refund가
취소되는 적립 포인트를 실제 `RECOVERY` 원장으로 회수하고, 회수하지 못한 잔액은
PointRecoveryPending으로 보존해 이후 적립에서 먼저 상계한다.

## Current State

- ADR-014/036과 BR-14/15는 부분 환불 뒤 고객 취소를 허용한다.
- OrderLine에는 최초 coupon/points/cash 배분 snapshot이 있다.
- `payment_refund`에는 Refund 총액만 있고 line allocation이 없다.
- `RejectionRefundService`는 `succeededRefundAmountKrw != 0`이면 거절한다.
- 공개 부분 환불 OpenAPI는 존재하지만 Controller/Application 구현은 없다.
- OpenAPI에는 `RECOVERY`와 `recoveryPendingKrw`가 있으나 Kotlin PointTransaction
  enum/type CHECK에는 `RECOVERY`가 없고 PointRecoveryPending persistence도 없다.
- 현재 `loyalty_point_lot`에는 BR-20 issuer type/reference snapshot이 없다. 만료
  부분 환불 compensation은 original issuer/cost lineage를 보존해야 하므로, legacy Lot의
  확인 가능한 issuer source를 먼저 precheck해야 한다.
- OpenAPI에는 customer point-account summary/ledger GET가 있지만 Loyalty controller/query owner와
  cursor tuple·operator support-read contract는 없다.
- 만료 혜택 정책 API는 현재 role만 검사하며 explicit grant, GET reason/access Audit과
  grant revoke 경계가 없다. `PaymentRefunded`·`PointsAccrued`·`PointsRestored`도 exact
  immutable integration event contract가 없다.

## Definitions

- **Cash allocation:** Refund가 각 OrderLine의 현금 몫 중 얼마를 환불했는지 나타내는 원장.
- **Point restoration allocation:** Refund가 각 line의 포인트 몫 중 얼마를 이미
  복원했는지 나타내는 원장.
- **Coupon attribution allocation:** 환불 line에 귀속된 쿠폰 할인액을 tie-out과 감사에
  사용하는 원장. CouponIssuance 복원 사실이 아니며 이후 전체 종료의 쿠폰 복원
  대상에서 차감하지 않는다.
- **Remaining allocation:** 최초 cash/points snapshot에서 모든 성공 cash/point
  allocation을 뺀 값.
- **Partial-refund points policy:** Operations가 소유하는
  `(PARTIAL_REFUND, POINTS)` immutable policy head/version. 초기 mode는
  `COMPENSATE_WITH_NEW_ISSUANCE`, validity days는 30이다.
- **RECOVERY transaction:** 환불에 대응해 실제 가용 PointLot과 PointAccount에서
  차감한 append-only Loyalty debit 원장. 미회수 부족액 자체가 아니다.
- **PointRecoveryPending:** 환불 시점에 회수하지 못해 이후 적립을 먼저 상계해야 하는
  Loyalty Aggregate. `PENDING`은 양수 remaining, `SETTLED`는 0 remaining이다.
- **Issuer snapshot:** PointLot에 저장하는 `PLATFORM|BRAND|STORE` type과 non-blank
  immutable reference. 보상 Lot은 original Lot의 snapshot을 그대로 승계한다.
- **OperatorPermissionGrant:** Operations가 actor별 explicit permission과 active/revoked
  상태를 소유하는 grant. role이나 JWT claim의 fallback이 아니다.

## Scope

### In Scope

- Refund line cash allocation, point restoration allocation과 coupon attribution 모델
- 최종 다섯 expired-benefit policy head/version 저장소, seed와 운영 목록/PATCH API
- `OperatorPermissionGrant` schema, Operations authorization API와 policy GET의
  `X-Access-Reason` access Audit
- audited offline `operator-permission-bootstrap` lifecycle command와 first-grant runbook evidence
- customer point-account summary/ledger read vertical slice, operator `POINT_ACCOUNT_READ` grant/reason
  audit과 ADR-070 signed `(occurredAt DESC, transactionId DESC)` cursor
- source/type별 unique, non-negative와 OrderLine 상한 DB 제약
- 부분 환불 command의 결정적 allocation과 성공 시점 원장 반영
- 성공 Refund의 적립 포인트 회수, PointRecoveryPending과 이후 적립 상계 foundation
- 만료 부분 환불 compensation에 필요한 PointLot issuer snapshot schema와 legacy
  issuer precheck/migration gate
- 승인액·Payment 성공 누계·line 합계 tie-out
- 고객 전체 취소가 소비할 read/lock API
- `PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1` immutable payload와 같은
  producer result transaction의 persistent publication

### Non-goals

- 고객 취소 endpoint와 Order `CANCELLED` 전이
- Settlement 처리
- 실제 Provider onboarding
- OrderLine 원본 수정

## Business Rules and Invariants

- OrderLine snapshot은 환불 뒤에도 불변이다.
- `SUCCEEDED` allocation만 성공 누계와 remaining 계산에서 차감한다.
- line별 누적 현금/쿠폰/포인트 allocation은 최초 각 몫을 넘지 않는다.
- 모든 성공 현금 allocation 합은 Payment 승인액을 넘지 않는다.
- 같은 Refund/source allocation은 한 번만 반영된다.
- 진행·불명·실패 Refund를 성공 allocation으로 위장하지 않는다.
- 부분 환불 성공은 CouponIssuance와 CouponReservation의 owner 상태를 변경하지 않고
  Promotion 복원 작업을 시작하지 않는다.
- coupon attribution은 point restoration이나 coupon restoration 성공액으로 집계하지
  않는다.
- 부분 환불 POINTS policy head/version 누락 또는 Refund snapshot FK 저장 실패는 요청
  transaction 전체 rollback과 503이며 Provider를 호출하지 않는다.
- 만료 point allocation은 Refund snapshot이 COMPENSATE면 `refundSucceededAt`부터
  validity days 동안 original issuer snapshot을 승계한 새 PointLot을 발급하고 PRESERVE면
  가용 복원을 생략한다.
- 새 PointLot은 issuer type/reference를 반드시 저장한다. legacy Lot은 verified source
  mapping이 있는 경우에만 backfill하며, PLATFORM default 또는 issuer/cost lineage 없는
  compensation Lot으로 대체하지 않는다.
- 부분 환불은 PointReservation `USED`를 유지하고 allocation 원장만 변경한다.
- 실제 회수 금액은 Lot별 `RECOVERY` transaction으로만 차감하며, 아직 회수하지 못한
  금액은 PointRecoveryPending에만 남긴다. 두 표현을 서로 대체하지 않는다.
- PointRecoveryPending은 account+refund source당 하나다. `PENDING`은 양수 remaining,
  `SETTLED`는 0 remaining이고 PointAccount `recoveryPendingKrw` summary와 tie-out한다.
- 이후 적립은 gross `ACCRUAL`을 먼저 기록하고 오래된 PENDING부터 `RECOVERY`로
  상계한다. Account와 Lot available 잔액은 언제나 음수가 아니다.
- 만료 혜택 정책 조회·변경은 Platform Operator role과 해당 active explicit grant를 모두
  요구한다. GET은 non-blank `X-Access-Reason`과 access Audit을 하나의 Operations
  transaction으로 저장하지 못하면 policy body를 반환하지 않는다.
- PointAccount read는 customer ownership 또는 Platform Operator의 active `POINT_ACCOUNT_READ`
  grant를 요구한다. operator branch는 non-blank `X-Access-Reason`과 target Audit이 없으면 body를
  반환하지 않으며, customer branch에는 그 header를 요구하지 않는다.

## Architecture and Transaction Boundaries

- 부분 환불 생성: Payment → 정렬된 기존 Refund/allocation 잠금 후 새 Refund와 요청
  allocation, 활성 PARTIAL_REFUND×POINTS policy version snapshot 저장.
- Provider 호출은 transaction 밖이다.
- 성공 결과: Payment → Refund → 정렬된 allocation 잠금 아래 성공 누계와 원장을 함께
  commit한다.
- 성공 Refund의 Loyalty consumer는 PointAccount → `(expiresAt, pointLotId)` PointLot
  lock 순서로 실제 회수, `RECOVERY` transaction과 필요하면 PointRecoveryPending을
  하나의 local transaction에 저장한다.
- 이후 OrderCompleted 적립은 PointAccount → `(createdAt, id)` PENDING lock 순서로
  gross `ACCRUAL`, deferred `RECOVERY`, pending 상태와 Account/Lot summary를 함께
  저장한다. Provider 호출은 어느 Loyalty transaction에도 넣지 않는다.
- compensation은 original PointLot의 immutable issuer snapshot value를 복사할 뿐
  Merchant Aggregate association을 로드하거나 추측하지 않는다.
- Order가 필요한 경로는 Order → Payment 순서를 지키고 Payment 뒤 Order를 잠그지 않는다.
- Refund API Query Service는 Payment의 현금 Refund와 Loyalty의 point restoration
  allocation을 DTO projection으로 조합한다. 쓰기 Aggregate 연관관계나 callback으로
  두 owner 상태를 합치지 않는다.
- Operations authorization API는 policy read/PATCH transaction에 참여해 active grant를
  잠근다. grant/Audit lookup failure는 role-only success로 대체하지 않고 503이다.
- `operator-permission-bootstrap`은 verified deployment release principal, actor/permission/reason/
  evidence를 받고 grant state/version과 Audit을 하나의 local transaction에 저장한다. Controller나
  direct SQL seed는 이 boundary를 우회하지 않는다.
- Loyalty point read Controller는 Loyalty Query Application Service만 호출한다. account ownership/
  operator grant/audit을 먼저 검증하고 `(occurredAt DESC, transactionId DESC)` projection에
  signed cursor를 적용한다. cursor codec/configuration은 signed-cursor foundation을 소비한다.
- Payment와 Loyalty result transaction은 ADR-068의 source, immutable amount/date/snapshot과
  persistent publication을 함께 저장한다. Analytics/Settlement consumer는 live owner state를
  다시 읽어 payload를 보완하지 않는다.

## Alternatives Considered

- Payment의 총 성공 환불액만 사용: 어느 line 혜택이 복원됐는지 알 수 없어 제외한다.
- 현재 Order 금액에서 역산: 과거 부분 환불 순서와 반올림을 재현하지 못해 제외한다.
- 고객 취소에서만 임시 allocation: 공개 부분 환불과 원천이 갈라져 제외한다.

## Failure Semantics

- allocation 합계 불일치는 409 또는 transaction failure이며 Refund 성공을 기록하지 않는다.
- DB 저장 실패는 Provider 결과를 잃은 성공으로 단정하지 않고 같은 key lookup recovery로 보낸다.
- 중복 source와 다른 allocation은 명시적 conflict다.
- Loyalty projection 조회 실패는 0·PROCESSING·stale 결과로 대체하지 않고 503이다.
- 성공 Refund 뒤 Loyalty 회수 write가 실패하면 포인트 회수를 성공·0으로 표시하지 않고
  원본 event를 재시도한다. retry 범위를 넘은 경우 owner 상태와 ReprocessingCase로
  관측하며 Refund 자체를 되돌리지 않는다.
- issuer precheck가 legacy Lot의 source를 재구성하지 못하면 forward migration과 만료
  compensation endpoint 활성화를 중단한다. PLATFORM 추정 backfill이나 issuer 없는 Lot으로
  degraded success를 만들지 않는다.

## Data and Migration

forward migration을 작성·배포하기 전에 read-only release precheck로
`loyalty_point_lot`의 existing row count와 각 row의 reconstructible issuer source를
evidence로 남긴다. 하나라도 unresolvable이면 schema를 부분 적용하지 않고 배포 gate를
실패시키며, refund compensation·후속 point adjustment endpoint를 활성화하지 않는다.
추정 backfill은 금지한다.

precheck가 통과한 뒤 forward-only migration으로 Refund line allocation, point
restoration 원장과 coupon attribution 원장을 추가한다. 같은 migration에
`loyalty_point_lot.issuer_type` (`PLATFORM|BRAND|STORE`)와 non-blank
`issuer_reference`를 넣는다. row가 0이면 final NOT NULL/CHECK를 즉시 만든다. row가
있으면 each-row verified mapping만 backfill하고, 모든 row를 검증한 뒤 final
NOT NULL/CHECK를 만든다. 같은 migration 범위에서
`loyalty_point_account.recovery_pending_krw`, `loyalty_point_recovery_pending`,
PointTransaction의 `ACCRUAL`/`RECOVERY` type CHECK와 deferred recovery pending ID를
추가한다. 내부 transaction amount는 양수 절대값으로 유지하고 공개 API만 signed effect로
변환한다.
Operations의 최종 composite policy version/head 저장소와 다섯 초기 head도 이 계획이
단독 migration한다. Plan 30은 같은 table/API migration을 다시 만들지 않는다.
같은 Operations migration scope에서 `operator_permission_grant`의 actor/permission unique,
active/revoked state, audit source와 grant lookup index를 만든다. Platform Operator role에서
default grant를 seed하지 않는다. permission CHECK에는 `POINT_ACCOUNT_READ`도 포함한다.
`operator-permission-bootstrap`은 migration 뒤 audited first grant를 만들며 direct DB DML을
대체하지 않는다. Point adjustment plan은 이 table을 다시 만들지 않고
Operations public authorization API를 소비한다.
구 Refund backfill은 실제 row 존재와 reconstructible source를 00 evidence로 확인한 뒤
별도 전략을 확정한다. 추정 backfill은 금지한다.

## API and Event Contracts

ADR-061이 정한 `/payments/{paymentId}/refunds` 계약을 구현 입력으로 사용한다. 모든
상태는 immutable 요청 금액 snapshot을 반환한다. `Refund.state`는 현금 Provider 상태,
`pointsRestorationState`는 비동기 Loyalty 상태다. 각 실제 성공 금액은 해당 owner
상태가 `SUCCEEDED`일 때만 반환한다. 고객 취소 계약은 이 foundation의 remaining
allocation 조회만 소비하고 여기서 활성화하지 않는다. `PointAccount.recoveryPendingKrw`는
PENDING 잔액 합이며, `PointTransaction.RECOVERY`는 음수 signed amount로 반환한다.
`PointRecoveryPendingRecorded` event는 PointRecoveryPending 생성 사실이며 실제 debit
transaction을 대신하지 않는다.

`PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1`의 exact payload, version,
logical source와 producer transaction은 ADR-068을 따른다. 기존 event 이름만으로 analytics
또는 Settlement consumer를 활성화하지 않는다. 정책 GET은 `X-Access-Reason` header를
OpenAPI에 추가하고 400/403/503의 grant·Audit failure contract를 가진다.

이 plan은 `GET /point-accounts/{accountId}`와
`GET /point-accounts/{accountId}/transactions`의 단독 implementation owner다. customer는 own
account만 reason 없이 읽고, Platform Operator support read는 `POINT_ACCOUNT_READ` grant와 optional
OpenAPI/header branch의 required `X-Access-Reason`을 쓴다. ledger projection은
`(occurredAt DESC, transactionId DESC)` order, account-ID-bound ADR-070 cursor와 `limit=20/100`을
쓴다. issuer reference, evidence, raw idempotency key, internal recovery case와 grant state는 response에
넣지 않는다.

## Milestones

1. PointLot issuer precheck와 snapshot schema gate를 완료한다.
2. 최종 다섯 policy head/version 저장소, `OperatorPermissionGrant`, seed와 audited 운영 API를 구현한다.
3. offline bootstrap command와 first-grant/revoke/regrant Audit lifecycle을 구현한다.
4. customer/operator point-account query, ownership/reason Audit과 signed ledger cursor를 구현한다.
5. allocation schema와 불변식을 domain/DB test로 고정한다.
6. 부분 환불 요청의 결정적 line allocation과 policy snapshot을 구현한다.
7. Refund 성공 transaction과 Payment 누계를 원자화한다.
8. points owner 복원 allocation을 source-aware하게 연결하고 coupon attribution이
   Promotion owner를 호출하지 않음을 고정한다.
9. Refund 적립 포인트 회수, PointRecoveryPending과 이후 적립 상계를 구현한다.
10. 고객 취소용 remaining allocation 조회·잠금 API를 제공한다.
11. ADR-068 Payment/Loyalty immutable event producer와 contract tests를 완료한다.

## Required Tests

- line별 전액/부분/반복 환불 tie-out
- 반올림과 여러 line 결정성
- 동시 Refund의 승인액·line 상한
- UNKNOWN/FAILED/MANUAL_REVIEW의 성공 누계 제외
- 같은 source replay와 다른 payload conflict
- 부분 환불 후 remaining cash/points와 coupon attribution 계산
- 부분 환불 성공 시 CouponIssuance 상태 불변과 Promotion 복원 호출 부재
- 후속 고객 취소·매장 거절에서 원 쿠폰의 단일 복원
- 다섯 policy head seed와 PARTIAL_REFUND/COUPON key 부재
- policy GET/PATCH의 role+grant, GET access reason/Audit atomicity, revoke 경쟁과 grant
  repository/Audit failure 503
- bootstrap release-principal/argument validation, absent/active/revoked/regrant lifecycle, Audit
  source uniqueness and direct SQL/default-grant fallback absence
- point account customer own/other, operator grant/reason/audit failure, `(occurredAt, transactionId)`
  keyset cursor, account-scope mismatch and issuer/evidence non-exposure
- Refund POINTS policy snapshot과 변경 전후 version 재현
- 만료 PointLot의 refundSucceededAt 기준 30일 보상 lot과 issuer/cost lineage
- PointLot issuer snapshot migration의 empty/verified/unresolvable legacy fixture,
  PLATFORM 추정 backfill 부재와 unresolvable gate의 endpoint 비활성화
- 부분 환불 뒤 PointReservation USED와 후속 종료의 잔여 points만 복원
- Refund 모든 상태의 요청 금액과 pointsRestorationState 존재
- 현금 성공·포인트 처리 중 조합과 owner별 SUCCEEDED 성공 금액 존재
- owner별 요청·성공 금액 mismatch의 성공 projection 거부
- Loyalty projection 장애의 503과 0·PROCESSING·stale fallback 부재
- 전액/부분 적립 포인트 회수의 `RECOVERY` debit, PENDING residual과 Account summary tie-out
- 같은 refund/Lot source replay, 다른 recovery payload conflict와 동시 Refund non-negative
- 이후 적립의 gross `ACCRUAL`·oldest PENDING 우선 `RECOVERY` 상계·`PENDING -> SETTLED`
- Loyalty recovery write 실패 시 Refund 성공을 포인트 회수 성공으로 위장하지 않는 retry/manual review
- PointTransaction storage magnitude와 OpenAPI signed amount, PointRecoveryPendingRecorded source contract
- `PaymentRefundedV1` completed/excluded disposition, immutable settlement effect와
  `PointsAccruedV1`/`PointsRestoredV1` source/version conflict
- migration empty DB와 지원 가능한 backfill fixture

## Validation Commands

```bash
./gradlew test --tests '*Refund*' --tests '*Allocation*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

Refund reason/mode/outcome, allocation invariant violation과 remaining mismatch를 닫힌
tag로 측정한다. Order/Payment/Refund ID는 metric tag에 넣지 않는다.

## Documentation Updates

ADR-014/036/063/065/068/069/071/072의 구현 evidence, aggregate invariants, transaction boundaries,
authorization matrix, OpenAPI contract test, event catalog과 payment runbook을 갱신한다.

## Progress

- [ ] five-head policy/grant foundation과 audited policy API
- [ ] PointLot issuer precheck와 snapshot schema gate
- [ ] schema와 domain invariant
- [ ] partial refund application flow
- [ ] success ledger transaction
- [ ] point restoration과 coupon attribution allocation
- [ ] refund earned-point recovery와 later-accrual offset
- [ ] customer-cancellation read API
- [ ] immutable Payment/Loyalty event producer contract
- [ ] 전체 검증

## Surprises & Discoveries

- 현재 rejection Refund는 선행 성공 환불을 명시적으로 차단한다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | 선행 부분 환불을 허용하고 잔여 allocation만 처리 | 이중 환불·이중 복원 방지 | BR-14/15, ADR-036 |
| 2026-08-01 | Accepted | 부분 환불은 쿠폰을 복원하지 않고 coupon allocation을 귀속 원장으로만 기록 | 잔여 할인과 재사용 쿠폰의 가치 중복을 막고 기존 CouponIssuance 모델 유지 | BR-12/15, ADR-014/036 |
| 2026-08-01 | Accepted | Refund 요청·확정 금액과 현금·포인트 owner 상태를 분리 | 202와 비동기 Loyalty 결과를 성공·0으로 위장하지 않음 | ADR-061 |
| 2026-08-01 | Accepted | PARTIAL_REFUND×POINTS 기본 30일 보상 head와 공통 정책 기반을 이 계획이 구현 | 만료 포인트 가치 보존과 Plan 10→30 정책 선행조건 유지 | ADR-063 |
| 2026-08-01 | Accepted | 실제 `RECOVERY` debit과 별도 PointRecoveryPending을 Plan 10이 구현 | 음수 잔액 없이 부족액을 보존하고 이후 적립으로 한 번만 상계 | BR-13, ADR-065 |
| 2026-08-01 | Accepted existing | Plan 10이 PointLot issuer snapshot precheck/migration을 소유 | 만료 부분 환불 compensation이 original issuer/cost lineage를 먼저 보존해야 함 | BR-20, ADR-063 |
| 2026-08-01 | Accepted | Operations grant와 policy GET access Audit은 Plan 10, point-adjustment permission은 후속 계획 | role-only access와 permission revoke 지연 제거 | ADR-069 |
| 2026-08-01 | Accepted | Payment/Loyalty financial event는 immutable snapshot을 publish | Settlement/Analytics가 current state를 재조회하지 않음 | ADR-068 |

## Outcomes & Retrospective

미구현 상태다. 완료 전 고객 취소 command 계획을 시작하지 않는다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-08-01: 부분 환불 coupon 비복원과 attribution 원장 의미를 확정.
- 2026-08-01: Refund 요청·성공 금액과 현금·포인트 owner별 API 상태를 확정.
- 2026-08-01: 부분 환불 만료 포인트 30일 보상과 five-head policy 기반 소유권을 확정.
- 2026-08-01: 환불 적립 포인트 `RECOVERY` debit, pending residual과 이후 적립 상계
  foundation을 추가.
- 2026-08-01: 만료 부분 환불 compensation의 issuer/cost lineage를 위해 PointLot issuer
  snapshot precheck/migration 소유권을 Plan 10으로 명확화.
- 2026-08-01: explicit operator grant/audited policy read와 Payment/Loyalty immutable event
  producer checkpoint를 ADR-069/068에 맞춰 이 계획의 범위로 추가.
