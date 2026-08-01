# ADR-063: 부분 환불의 만료 포인트 30일 보상 복원

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owners:** [Plan 10 issuer provenance](../exec-plans/completed/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md), [Plan 11 policy/grants](../exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md), [Plan 12 allocation/restoration](../exec-plans/active/customer-order-cancellation-12-partial-refund-allocation-and-restoration.md)

## Context

BR-12와 ADR-014는 품목 부분 환불이 해당 OrderLine에 배분된 사용 포인트를 복원한다고
정한다. ADR-036과 Plan 10은 성공한 line-level 포인트 복원 원장으로 이후 고객 전체
취소의 이중 복원을 막는다.

그러나 원 PointLot이 부분 환불 성공 전에 만료됐을 때 가용 포인트를 되살릴지, 새
PointLot으로 보상할지, 복원을 생략할지 결정되지 않았다. ADR-041/042의 기존 정책과
`restoration_trigger`는 주문 종료 원인인 `STORE_REJECTION`과
`CUSTOMER_CANCELLATION`만 표현한다. 부분 환불을 둘 중 하나로 기록하면 책임 원인과
정책 lineage가 거짓이 되고, policy version 없이 복원하면 재시도 결과가 현재 설정에
따라 달라진다.

Plan 11은 Plan 30보다 먼저 실행되므로 부분 환불이 필요로 하는 composite policy
head/version 저장소와 운영 API의 구현 계획 소유권도 명확히 해야 한다.

## Decision

### Trigger and policy head

- `PARTIAL_REFUND`를 포인트 allocation 복원 전용 trigger로 추가한다.
- 허용 policy key는 기존 종료용 네 key와 새
  `(PARTIAL_REFUND, POINTS)`를 합친 다섯 개다.
- `(PARTIAL_REFUND, COUPON)` head는 만들지 않는다. BR-12와 ADR-014에 따라 부분
  환불은 쿠폰을 복원하지 않는다.
- 새 POINTS head의 초기 immutable version은
  `COMPENSATE_WITH_NEW_ISSUANCE`, `compensationValidityDays = 30`이다.
- Operations가 다섯 head/version과 기존 CAS·idempotency·Audit 정책을 소유한다.
  운영자는 부분 환불 POINTS head도 독립적으로 변경할 수 있다.
- 정책 목록 API는 정확히 다섯 head를 반환한다. keyed PATCH에서
  `PARTIAL_REFUND/COUPON` 조합은 존재하지 않는 policy key로 404를 반환하고 version을
  만들지 않는다.

### Snapshot and restoration

- 부분 환불 요청 transaction은 Payment와 기존 Refund/allocation을 잠근 뒤 활성
  `(PARTIAL_REFUND, POINTS)` head/version을 읽고 Refund의 immutable
  `pointRestorationPolicyVersionId`로 snapshot한다.
- snapshot은 Provider 호출 전에 저장하며 retry, lookup, result 처리와 정책 변경은
  이를 바꾸지 않는다. head/version이 없거나 FK 저장이 실패하면 Refund 요청 전체를
  rollback하고 Provider를 호출하지 않는다.
- 현금 Refund가 `SUCCEEDED`로 확정된 뒤 `PaymentRefunded`의 line allocation source와
  snapshot policy version으로 Loyalty owner 작업을 시작한다.
- 원 PointLot이 `refundSucceededAt`에 유효하면 원 lot available balance와 `RESTORE`
  PointTransaction을 늘린다.
- 원 PointLot이 `refundSucceededAt`에 만료됐고 snapshot mode가
  `COMPENSATE_WITH_NEW_ISSUANCE`면 같은 가치의 새 PointLot과 `COMPENSATION`
  PointTransaction을 만든다. 새 lot은 `refundSucceededAt`부터 snapshot validity days
  동안 유효하다.
- 운영자가 이후 mode를 `PRESERVE_ORIGINAL_EXPIRY`로 변경한 Refund는 만료 allocation의
  available balance를 늘리지 않고 `RESTORE_SKIPPED_EXPIRED`를 기록한다.
- 보상 PointLot은 original lot ID, issuer/cost owner, Refund source,
  `PARTIAL_REFUND` trigger와 policy version ID를 보존한다.
- `issuer_type`과 `issuer_reference`는 PointLot에 저장하는 immutable issuer snapshot이다.
  Plan 10은 만료 lot 보상을 시작하기 전에 기존 Lot의 확인 가능한 issuer source를
  precheck하고, 검증된 mapping만 backfill한다. source가 없는 기존 Lot을 PLATFORM으로
  추정하거나 issuer/cost lineage가 없는 보상 Lot을 만들지 않는다.
- `(refundId, orderLineId, pointAllocationId)`와 owner source를 Unique Constraint로
  보호해 같은 부분 환불·event·retry가 가치를 두 번 만들지 못하게 한다.

### PointReservation composition

- 부분 환불은 주문 종료가 아니므로 PointReservation의 `USED` 상태와 reservation-level
  종료 metadata를 변경하지 않는다. 부분 복원 사실은 allocation별 PointTransaction과
  Refund line allocation이 소유한다.
- 이후 고객 취소나 매장 거절은 Payment/Loyalty의 같은 allocation 원장을 잠가 이미
  부분 환불로 복원된 allocation을 대상에서 제외하고 아직 복원되지 않은 allocation만
  종료 trigger로 처리한다.
- Order→Payment→정렬된 Refund/allocation lock 순서가 부분 환불과 주문 종료의 대상
  겹침을 직렬화한다. 이미 다른 source로 복원된 allocation을 뒤늦게 덮어쓰거나 같은
  금액을 다시 복원하지 않는다.

### ExecPlan ownership

- Operations가 정책 Aggregate를 계속 소유하지만, 구현 순서상 Plan 11이 최종 다섯
  head/version 저장소, seed와 운영 목록/PATCH API migration을 단독 구현한다.
- Plan 10은 만료 부분 환불 보상이 먼저 필요로 하는 PointLot issuer snapshot schema와
  legacy issuer precheck/migration gate도 단독 소유한다. 후속 감사형 point adjustment
  계획은 이 schema와 gate evidence를 전제하고 같은 migration을 다시 만들지 않는다.
- Plan 30은 정책 저장소/API migration을 다시 만들지 않고 기존 종료용 네 head를
  OrderCompensationCase snapshot과 event에 연결한다.
- **Execution amendment (2026-08-01):** Plan 30은 Plan 11의 종료용 네 policy head를 직접
  소비하므로 Plan 11 outcome을 direct dependency로 유지한다. Plan 12는 Plan 10 issuer와 Plan 11
  policy outcome을 직접 소비한다. ADR-072 migration lane에서 Plan 20은 별도 phase predecessor이며,
  Plan 30은 Plan 11·20이 모두 completed인
  latest-main baseline에서만 시작한다. Plan 20은 이 정책 기반을 소유하거나 대체하지 않는다.

## Alternatives Considered

### 부분 환불 POINTS head의 기본 원 만료일 유지

- 보상 비용이 작고 기존 만료 의도를 보존한다.
- 매장·운영자 부분 환불에서 고객이 이미 사용한 포인트 가치를 돌려받지 못할 수 있다.

### 운영 head 없이 항상 만료 복원 생략

- 구현과 운영 설정이 단순하다.
- 실제 문의·비용 데이터가 달라져도 배포와 새 결정 없이 조정할 수 없고 기존 버전형
  정책 체계에 예외가 생긴다.

### 현금과 포인트를 Refund 성공 transaction에서 함께 복원

- 성공 원장은 한 transaction에 모인다.
- Payment가 Loyalty Aggregate를 직접 변경해 Context 소유권과 비동기 실패 복구 경계를
  침범한다.

### 정책 기반을 Plan 30에서 먼저 구현

- 기존 compensation 계획의 범위를 유지한다.
- `00 → 11 → 30` 직접 정책 의존을 뒤집어 Plan 12가 자신의 필수 정책보다 먼저 실행될 수
  없다. 과거 Plan 20 병렬 실행 대안은 ADR-072 migration-writer lane으로 supersede됐지만,
  Plan 20은 이 정책 기반을 대신 소유하지 않는다.

## Rationale

부분 환불은 고객 셀프 기능이 아니라 매장 또는 운영자가 수행하는 거래 조정이다. 환불
시점에 원 lot이 만료됐다는 이유로 이미 결제에 사용한 포인트 가치까지 잃게 하는 것보다
30일 보상 lot으로 복원하는 편이 BR-12의 복원 의미와 매장 책임 보상 원칙에 맞는다.
별도 trigger와 immutable policy snapshot은 고객 취소·매장 거절과 책임을 섞지 않고
지연·중복 처리 결과를 재현한다.

## Consequences

- 정책 head가 네 개에서 다섯 개로 늘고 OpenAPI 목록 cardinality가 바뀐다.
- Plan 11이 Operations 정책 저장소/API 기반을, Plan 12가 partial-refund Payment/Loyalty flow를 구현한다.
- Refund에 POINTS policy version FK가 추가되고 line allocation·PointTransaction이 같은
  lineage를 보존한다.
- 만료 부분 환불은 보상 포인트 비용을 만들며 원 issuer/cost owner가 이를 승계한다.
- 부분 환불은 PointReservation terminal state를 바꾸지 않으므로 net restored amount는
  allocation 원장에서 계산한다.

## Failure Scenarios

- 부분 환불을 `CUSTOMER_CANCELLATION`으로 기록하면 고객 책임 정책 변경이 부분 환불
  결과를 바꾸고 감사 원인이 거짓이 된다.
- Provider 호출 뒤 policy snapshot이 없으면 재시도 시 최신 head를 추측하게 된다.
- 보상 lot의 시작 시각을 listener 처리 시각으로 잡으면 retry 지연에 따라 만료일이
  달라진다.
- 부분 환불이 PointReservation 전체를 RESTORED로 바꾸면 아직 환불되지 않은 line의
  포인트를 종료 처리할 수 없다.
- 종료 listener가 부분 환불 allocation을 다시 대상으로 잡으면 포인트가 이중 복원된다.
- Plan 11과 Plan 30이 같은 policy migration을 만들면 checksum·schema 소유권이 충돌한다.
- legacy Lot issuer source가 확인되지 않았는데 PLATFORM backfill이나 issuer 없는
  compensation Lot으로 계속하면 BR-20 비용 귀속과 환불 lineage를 거짓으로 만든다.

## Verification

- **Plan 11 implementation evidence (2026-08-01):** Flyway `V13`이 종료용 네 key와
  `PARTIAL_REFUND×POINTS`만 허용하는 composite head/version을 만들고 정확히 다섯 head를 seed한다.
  PostgreSQL integration test가 cardinality/order, immutable UPDATE/DELETE 거부,
  `PARTIAL_REFUND/COUPON` DB constraint/API 404, CAS/idempotency replay와 Audit rollback을 검증한다.
- **Plan 10 implementation evidence (2026-08-01):** Flyway `V14` is empty-database final
  schema or exact verified-legacy mapping only. PostgreSQL Testcontainers proves empty,
  verified, missing, and invalid source-evidenced fixtures; no default/guessed backfill;
  final issuer `NOT NULL`/CHECK/immutability; DTO projection; and migration-caused Spring
  activation failure.
- 다섯 policy head와 허용 key 조합이 정확하다.
- PARTIAL_REFUND/COUPON key는 생성·조회·변경되지 않는다.
- 유효 lot은 원 lot, 만료 lot은 30일 보상 lot으로 복원된다.
- 정책 변경 전후 Refund가 각자 저장한 version으로 재현된다.
- 부분 환불 뒤 PointReservation은 USED이고 allocation 원장만 증가한다.
- 후속 고객 취소/매장 거절은 잔여 point allocation만 한 번 복원한다.
- Plan 10의 issuer precheck는 empty/verified/unresolvable legacy fixture를 구분하고,
  unresolvable이면 만료 보상 기능을 활성화하지 않는다.

## Required Tests

- 초기 다섯 head seed와 PARTIAL_REFUND×POINTS 기본 COMPENSATE/30
- 정책 목록 정확히 다섯 건과 정렬
- PARTIAL_REFUND/COUPON PATCH 404와 version/Audit 부재
- head CAS·idempotency replay와 정책 변경 경쟁
- Refund 요청의 policy snapshot FK와 Provider 호출 전 누락 rollback
- PointLot 만료 -1ns/at/+1ns의 RESTORE/COMPENSATION 분기
- 보상 lot의 refundSucceededAt+30일 만료와 original issuer/cost lineage
- PointLot issuer snapshot migration의 empty/verified/unresolvable legacy fixture와
  PLATFORM 추정 backfill 부재
- PRESERVE 정책 변경 뒤 새 Refund만 RESTORE_SKIPPED_EXPIRED
- 같은 Refund/event 재생의 PointTransaction·PointLot 한 건
- 부분 환불 후 PointReservation USED와 allocation별 복원 합계
- 부분 환불과 고객 취소 경쟁의 잔여 allocation 단일 처리
- Plan 30 migration에 policy head/version/API 중복 생성 부재

## Metrics

- `beanflow.loyalty.partial_refund_restoration.count{disposition,policy_mode,outcome}`
- `beanflow.loyalty.partial_refund_restoration.amount{disposition}`
- `beanflow.operations.benefit_policy.change.count{trigger,benefit_type,mode,outcome}`

Refund, Payment, Order, PointLot, policy version과 customer ID는 metric tag로 사용하지
않는다.

- **Not measured:** 만료 부분 환불 포인트 보상액과 후속 문의율

## Revisit Conditions

부분 환불을 고객이 직접 실행하게 되거나, 보상 PointLot 비용이 측정된 고객 영향 대비
과도하거나, 환불 reason별로 다른 만료 복원 정책이 필요할 때

## Related Decisions

- BR-12, BR-15, BR-20
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-011](ADR-011-point-lot-ledger.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-036](ADR-036-cancellation-after-partial-refund.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
- [ADR-042](ADR-042-benefit-restoration-ledger-metadata.md)
- [ADR-061](ADR-061-refund-requested-and-confirmed-amounts.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
