# ADR-042: 혜택 복원 원장의 원인·정책 metadata

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

쿠폰 복원은 CouponReservation을 `RESTORED`로 만들고 source reference만 저장한다.
원 쿠폰을 되살렸는지, 새 보상 CouponIssuance를 만들었는지, 만료로 가용 복원을
생략했는지 reservation row만으로 구분할 수 없다.

포인트는 allocation마다 `RESTORE`, `COMPENSATION`,
`RESTORE_SKIPPED_EXPIRED` PointTransaction으로 결과를 구분하지만 매장 거절과 고객
취소 trigger, 적용한 policy version을 저장하지 않는다. source 문자열만으로 원인을
추론하면 ADR-041의 trigger×benefit 정책과 source-aware 충돌을 구조적으로 검증할 수
없다.

## Decision

### Common metadata

- 혜택 복원의 결과와 원인을 서로 다른 축으로 저장한다. trigger별 Coupon state나
  PointTransaction type을 만들지 않는다.
- 초기 `restoration_trigger` 값은 `STORE_REJECTION`,
  `CUSTOMER_CANCELLATION`이다.
- CouponReservation과 PointReservation의 종료 복원에는
  `restoration_source_reference`, `restoration_trigger`,
  `restoration_policy_version_id`가 모두 필수다.
- 같은 source, trigger, policy version과 동일 terminal 결과의 중복만 멱등
  `ALREADY_APPLIED`다. 하나라도 다르면 덮어쓰지 않고
  `COMPENSATION_SOURCE_CONFLICT`다.
- owner row의 policy version ID는 event 전체 snapshot의 ID를 보존한다.
  consumer는 최신 policy head를 조회하지 않는다.
- Case의 policy snapshot child row가 FK 무결성 원천이며 Promotion·Loyalty owner
  row는 Context 간 객체 연관관계 없이 policy version ID 값만 참조한다.

### Coupon disposition

- CouponReservation에 `restoration_disposition`을 추가한다.
- 초기 값은 다음 세 가지다.

  | Disposition | 의미 |
  |---|---|
  | `ORIGINAL_RESTORED` | 종료 시점에 원 issuance가 유효해 다시 사용 가능 |
  | `COMPENSATION_ISSUED` | 만료됐고 policy에 따라 새 issuance 발급 |
  | `SKIPPED_EXPIRED` | 만료됐고 원 만료일 유지 policy로 가용 가치 미복원 |

- CouponReservation `state = RESTORED`이면 source, trigger, policy version,
  disposition이 모두 필수다. 다른 state에서는 모두 null이어야 한다.
- 새 보상 CouponIssuance는 `original_issuance_id`, source, trigger와 policy version
  ID를 저장한다.
- `COMPENSATION_ISSUED`이면 같은 source의 새 issuance가 정확히 하나 있어야 하고,
  다른 두 disposition에서는 새 issuance가 없어야 한다. source unique와 transaction
  write 순서로 보호한다.
- `SKIPPED_EXPIRED`도 성공적으로 적용된 정책 결과이므로 COUPON step은
  `SUCCEEDED`다. 사용 가능한 쿠폰을 만들었다는 뜻은 아니다.

### Point disposition

- 기존 PointTransaction type `RESTORE`, `COMPENSATION`,
  `RESTORE_SKIPPED_EXPIRED`를 결과 disposition으로 유지한다.
- 위 세 type의 PointTransaction은 source, trigger와 policy version ID를 필수로
  저장한다.
- PointReservation `state = RESTORED`에도 같은 source, trigger와 policy version ID를
  저장해 reservation-level source conflict를 판정한다.
- 각 original allocation은 정확히 하나의 restoration transaction을 가진다.
  transaction source는 owner source와 allocation ID를 결합하고 UNIQUE로 보호한다.
- `RESTORE_SKIPPED_EXPIRED`는 계정 가용 잔액을 늘리지 않지만 정책 적용 성공이므로
  POINTS step은 `SUCCEEDED`다.
- `COMPENSATION`으로 생성한 PointLot은 original lot ID, source, trigger와 policy
  version ID를 보존한다.

### Migration and naming

- 기존 `STORE_REJECTION` 복원 row는 Order/Case와 기존 source를 연결해 trigger와
  policy version을 backfill한다.
- 연결할 Case 또는 policy version이 없는 기존 row는 값을 추측하지 않고 migration
  precheck를 실패시킨다.
- 거절 전용 owner API·command 이름은
  `restoreUsedAfterTermination` 계열로 일반화하고 trigger와 policy snapshot을
  명시적으로 받는다.
- PointTransaction type 이름은 결과 의미가 이미 일반적이므로 유지한다.

## Alternatives Considered

### trigger별 state와 transaction type

- 한 enum 값만으로 원인과 결과를 함께 볼 수 있다.
- `CUSTOMER_CANCELLATION_COMPENSATION`,
  `STORE_REJECTION_COMPENSATION`처럼 조합이 늘고 새 trigger·mode마다 migration이
  필요하다.

### source reference 문자열만 사용

- 새 metadata 컬럼이 적다.
- 원인·정책을 문자열 파싱에 의존하고 형식 변경과 legacy source에서 안전하지 않다.

### Case에서만 policy와 trigger 보존

- owner 데이터 중복이 줄어든다.
- owner row만으로 source conflict를 판정할 수 없고 운영 조사마다 Operations
  조회가 필요하다.

## Rationale

결과 disposition, 책임 trigger와 적용 정책은 서로 독립적인 감사 질문이다. 각 축을
구조화하면 enum 조합 폭발 없이 source-aware 멱등성과 정책 재현성을 owner 데이터에서
직접 검증할 수 있다.

## Consequences

- Promotion·Loyalty migration에 nullable metadata 컬럼과 상태/type별 CHECK가
  추가된다.
- compensation issuance/lot도 trigger·policy lineage를 보존해야 한다.
- `SKIPPED_EXPIRED`는 금액을 복원하지 않아도 owner 작업 성공으로 집계한다.
- 운영 조회와 metric은 disposition, trigger, policy mode를 별도 차원으로 사용한다.
- 향후 부분 환불 등 새 복원 trigger가 같은 필드를 사용하려면 책임 의미와 기존
  line-allocation 원장 관계를 별도 ADR로 확장해야 한다.

## Failure Scenarios

- CouponReservation `RESTORED`인데 disposition이 없으면 가용 가치 복원 여부를 알 수
  없다.
- owner row policy ID와 event snapshot이 다르면 지연 재시도 결과를 재현할 수 없다.
- 같은 source지만 다른 trigger를 멱등 성공으로 처리하면 잘못 라우팅한 event가
  publication 완료된다.
- `SKIPPED_EXPIRED`에서 잔액을 늘리면 원 만료일 유지 정책을 위반한다.
- compensation issuance/lot이 중복되면 쿠폰 또는 포인트 가치가 이중 발급된다.
- migration이 source 문자열만으로 trigger를 추측하면 손상 row를 정상으로
  backfill할 수 있다.

## Verification

- 두 trigger가 같은 결과 type을 사용하면서 metadata로 구분된다.
- 각 mode·만료 여부가 정확한 disposition과 잔액 결과를 만든다.
- 같은 source/trigger/policy duplicate는 가치와 원장을 한 번만 변경한다.
- 다른 metadata 충돌은 기존 결과를 보존하고 owner step 복구로 이동한다.

## Required Tests

- Coupon의 세 disposition별 issuance·가용성 결과
- Coupon RESTORED metadata nullability CHECK
- COMPENSATION_ISSUED source unique와 issuance 정확히 한 건
- Point allocation별 세 transaction type과 계정 tie-out
- Point restoration transaction metadata CHECK
- Point compensation lot의 original/source/trigger/policy lineage
- 같은 source·trigger·policy 중복의 원장·잔액 불변
- source/trigger/policy 각각의 mismatch conflict
- 기존 거절 row trigger·policy backfill
- backfill source Case 누락 시 migration 실패
- 고객 취소 초기 PRESERVE policy의 SKIPPED_EXPIRED 성공 step
- 운영자가 COMPENSATE로 변경한 뒤 새 Case만 보상 issuance/lot 생성

## Metrics

- `beanflow.benefit.restoration.count{benefit_type,trigger,disposition}`
- `beanflow.benefit.restoration.source_conflict.count{benefit_type,trigger}`
- `beanflow.benefit.restoration.amount{benefit_type,trigger,disposition}`

Order, customer, issuance, lot, Case와 policy version ID는 metric tag로 사용하지 않는다.

- **Not measured:** 고객 취소에서 만료 복원을 생략한 가치와 후속 문의율

## Revisit Conditions

부분 환불, 수락 후 취소 또는 운영자 보상이 같은 복원 metadata를 사용하게 되거나,
혜택 결과에 부분 복원 disposition이 필요해질 때

## Related Decisions

- BR-09, BR-10, BR-14
- [ADR-011](ADR-011-point-lot-ledger.md)
- [ADR-024](ADR-024-coupon-calculation-model.md)
- [ADR-028](ADR-028-expired-benefit-restoration-policy.md)
- [ADR-034](ADR-034-customer-cancellation-event-contract.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
