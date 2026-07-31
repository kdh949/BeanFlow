# 부분 환불 allocation foundation을 만든다

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

선행 부분 환불이 있는 미수락 `PAID` 주문을 고객이 전체 취소해도 승인액을 초과해
현금을 환불하거나 이미 복원한 포인트를 다시 복원하지 않도록 line-level allocation
원장을 먼저 완성한다. 부분 환불의 coupon allocation은 복원이 아니라 귀속·감사
원장으로 명시해 이후 전체 종료의 단일 쿠폰 복원을 방해하지 않는다.

## Current State

- ADR-014/036과 BR-14/15는 부분 환불 뒤 고객 취소를 허용한다.
- OrderLine에는 최초 coupon/points/cash 배분 snapshot이 있다.
- `payment_refund`에는 Refund 총액만 있고 line allocation이 없다.
- `RejectionRefundService`는 `succeededRefundAmountKrw != 0`이면 거절한다.
- 공개 부분 환불 OpenAPI는 존재하지만 Controller/Application 구현은 없다.

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

## Scope

### In Scope

- Refund line cash allocation, point restoration allocation과 coupon attribution 모델
- 최종 다섯 expired-benefit policy head/version 저장소, seed와 운영 목록/PATCH API
- source/type별 unique, non-negative와 OrderLine 상한 DB 제약
- 부분 환불 command의 결정적 allocation과 성공 시점 원장 반영
- 승인액·Payment 성공 누계·line 합계 tie-out
- 고객 전체 취소가 소비할 read/lock API

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
  validity days 동안 새 PointLot을 발급하고 PRESERVE면 가용 복원을 생략한다.
- 부분 환불은 PointReservation `USED`를 유지하고 allocation 원장만 변경한다.

## Architecture and Transaction Boundaries

- 부분 환불 생성: Payment → 정렬된 기존 Refund/allocation 잠금 후 새 Refund와 요청
  allocation, 활성 PARTIAL_REFUND×POINTS policy version snapshot 저장.
- Provider 호출은 transaction 밖이다.
- 성공 결과: Payment → Refund → 정렬된 allocation 잠금 아래 성공 누계와 원장을 함께
  commit한다.
- Order가 필요한 경로는 Order → Payment 순서를 지키고 Payment 뒤 Order를 잠그지 않는다.
- Refund API Query Service는 Payment의 현금 Refund와 Loyalty의 point restoration
  allocation을 DTO projection으로 조합한다. 쓰기 Aggregate 연관관계나 callback으로
  두 owner 상태를 합치지 않는다.

## Alternatives Considered

- Payment의 총 성공 환불액만 사용: 어느 line 혜택이 복원됐는지 알 수 없어 제외한다.
- 현재 Order 금액에서 역산: 과거 부분 환불 순서와 반올림을 재현하지 못해 제외한다.
- 고객 취소에서만 임시 allocation: 공개 부분 환불과 원천이 갈라져 제외한다.

## Failure Semantics

- allocation 합계 불일치는 409 또는 transaction failure이며 Refund 성공을 기록하지 않는다.
- DB 저장 실패는 Provider 결과를 잃은 성공으로 단정하지 않고 같은 key lookup recovery로 보낸다.
- 중복 source와 다른 allocation은 명시적 conflict다.
- Loyalty projection 조회 실패는 0·PROCESSING·stale 결과로 대체하지 않고 503이다.

## Data and Migration

forward-only migration으로 Refund line allocation, point restoration 원장과 coupon
attribution 원장을 추가한다.
Operations의 최종 composite policy version/head 저장소와 다섯 초기 head도 이 계획이
단독 migration한다. Plan 30은 같은 table/API migration을 다시 만들지 않는다.
구 Refund backfill은 실제 row 존재와 reconstructible source를 00 evidence로 확인한 뒤
별도 전략을 확정한다. 추정 backfill은 금지한다.

## API and Event Contracts

ADR-061이 정한 `/payments/{paymentId}/refunds` 계약을 구현 입력으로 사용한다. 모든
상태는 immutable 요청 금액 snapshot을 반환한다. `Refund.state`는 현금 Provider 상태,
`pointsRestorationState`는 비동기 Loyalty 상태다. 각 실제 성공 금액은 해당 owner
상태가 `SUCCEEDED`일 때만 반환한다. 고객 취소 계약은 이 foundation의 remaining
allocation 조회만 소비하고 여기서 활성화하지 않는다.

## Milestones

1. 최종 다섯 policy head/version 저장소, seed와 운영 API를 구현한다.
2. allocation schema와 불변식을 domain/DB test로 고정한다.
3. 부분 환불 요청의 결정적 line allocation과 policy snapshot을 구현한다.
4. Refund 성공 transaction과 Payment 누계를 원자화한다.
5. points owner 복원 allocation을 source-aware하게 연결하고 coupon attribution이
   Promotion owner를 호출하지 않음을 고정한다.
6. 고객 취소용 remaining allocation 조회·잠금 API를 제공한다.

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
- Refund POINTS policy snapshot과 변경 전후 version 재현
- 만료 PointLot의 refundSucceededAt 기준 30일 보상 lot과 issuer/cost lineage
- 부분 환불 뒤 PointReservation USED와 후속 종료의 잔여 points만 복원
- Refund 모든 상태의 요청 금액과 pointsRestorationState 존재
- 현금 성공·포인트 처리 중 조합과 owner별 SUCCEEDED 성공 금액 존재
- owner별 요청·성공 금액 mismatch의 성공 projection 거부
- Loyalty projection 장애의 503과 0·PROCESSING·stale fallback 부재
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

ADR-014/036의 구현 evidence, aggregate invariants, transaction boundaries, OpenAPI
contract test와 payment runbook을 갱신한다.

## Progress

- [ ] five-head policy foundation/API
- [ ] schema와 domain invariant
- [ ] partial refund application flow
- [ ] success ledger transaction
- [ ] point restoration과 coupon attribution allocation
- [ ] customer-cancellation read API
- [ ] 전체 검증

## Surprises & Discoveries

- 현재 rejection Refund는 선행 성공 환불을 명시적으로 차단한다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | 선행 부분 환불을 허용하고 잔여 allocation만 처리 | 이중 환불·이중 복원 방지 | BR-14/15, ADR-036 |
| 2026-08-01 | Accepted | 부분 환불은 쿠폰을 복원하지 않고 coupon allocation을 귀속 원장으로만 기록 | 잔여 할인과 재사용 쿠폰의 가치 중복을 막고 기존 CouponIssuance 모델 유지 | BR-12/15, ADR-014/036 |
| 2026-08-01 | Accepted | Refund 요청·확정 금액과 현금·포인트 owner 상태를 분리 | 202와 비동기 Loyalty 결과를 성공·0으로 위장하지 않음 | ADR-061 |
| 2026-08-01 | Accepted | PARTIAL_REFUND×POINTS 기본 30일 보상 head와 공통 정책 기반을 이 계획이 구현 | 만료 포인트 가치 보존과 10→20→30 실행 순서 유지 | ADR-063 |

## Outcomes & Retrospective

미구현 상태다. 완료 전 고객 취소 command 계획을 시작하지 않는다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-08-01: 부분 환불 coupon 비복원과 attribution 원장 의미를 확정.
- 2026-08-01: Refund 요청·성공 금액과 현금·포인트 owner별 API 상태를 확정.
- 2026-08-01: 부분 환불 만료 포인트 30일 보상과 five-head policy 기반 소유권을 확정.
