# ADR-041: 종료 원인·혜택별 만료 복원 정책

- **Status:** Accepted
- **Date:** 2026-07-31
- **Amended by:** ADR-059의 release gate, ADR-063의 부분 환불 POINTS head

## Context

ADR-028은 매장 거절 시 만료된 쿠폰·포인트에 적용할 append-only 정책 version과 단일
현재 head를 정의한다. `COMPENSATE_WITH_NEW_ISSUANCE`는 새 쿠폰·PointLot을 발급하고
`PRESERVE_ORIGINAL_EXPIRY`는 만료된 가치를 가용 상태로 되살리지 않는다.

고객 취소에도 정책 snapshot이 필요하지만 고객 책임과 매장 책임을 같은 설정으로
운영할 필요는 없다. 또한 쿠폰과 포인트의 비용·만료 특성이 달라 운영자가 각각 다른
mode와 유효기간을 선택할 수 있어야 한다.

현재 `operations_expired_benefit_policy_head`는 singleton이고 Case는 단일
`policy_version/mode/validity_days`를 저장한다. `OrderRejectedV1`도 같은 세 필드를
쿠폰과 포인트 consumer가 공유한다. 이 구조로는 trigger와 혜택별 네 정책을 표현할 수
없다.

## Decision

### Policy key and immutable versions

- 정책 key는 `(trigger, benefitType)`이다.
- 종료 trigger는 `STORE_REJECTION`, `CUSTOMER_CANCELLATION`이고 benefit type은
  `COUPON`, `POINTS`다. 두 종료 trigger의 head는 네 개다.
- ADR-063은 부분 환불 포인트 복원을 위해 `(PARTIAL_REFUND, POINTS)` head 하나를
  추가한다. 현재 허용 key는 종료용 네 개와 부분 환불용 하나를 합친 다섯 개다.
  `(PARTIAL_REFUND, COUPON)`은 허용하지 않는다.
- 각 policy version row는 전역 고유 `policyVersionId`와 trigger, benefit type,
  mode, compensation validity days, effective time, updated actor, reason과 정책
  변경 멱등 정보를 저장한다.
- version row는 append-only다. 수정·삭제하지 않고 모든 변경은 새 version insert와
  해당 key head의 compare-and-set 갱신으로 수행한다.
- 기존 단일 policy version/head는 두 `STORE_REJECTION` key로 이관한다. 현재 head의
  mode와 validity days를 COUPON과 POINTS 초기 head가 각각 이어받는다.
- 두 `CUSTOMER_CANCELLATION` 초기 head의 mode는
  `PRESERVE_ORIGINAL_EXPIRY`다. 이 mode에서 validity days는 결과에 사용되지 않지만
  schema 일관성을 위해 30을 저장한다.
- `PARTIAL_REFUND × POINTS` 초기 head의 mode는
  `COMPENSATE_WITH_NEW_ISSUANCE`, validity days는 30이다. 이 version은 Plan 10이
  composite policy 저장소/API와 함께 seed한다.
- 전역 고유 Long ID는 DB sequence를 사용하고 기존 최대 policy version보다 큰 값부터
  발급한다. head별 `currentVersionId + 1` 계산으로 ID를 만들지 않는다.

### Operator API

- `PLATFORM_OPERATOR`는
  `GET /api/v1/operations/policies/expired-benefit-restoration`으로 다섯 현재 head를
  모두 조회한다.
- 한 head 변경은
  `PATCH /api/v1/operations/policies/expired-benefit-restoration/{trigger}/{benefitType}`
  로 수행한다.
- PATCH는 `Idempotency-Key`, `expectedPolicyVersionId`, mode,
  compensation validity days와 변경 reason을 요구한다.
- path key와 기존 idempotency record의 key가 다르면
  `409 IDEMPOTENCY_KEY_REUSED`, expected head가 다르면 `409 ORDER_STATE_CONFLICT`와
  같은 정책 version conflict 계약을 사용한다.
- `PARTIAL_REFUND/COUPON` path 조합은 존재하지 않는 policy key로 404이며 version과
  AuditRecord를 만들지 않는다.
- 운영자 페이지는 다섯 head를 독립적으로 표시·변경하고 변경 이력은 append-only
  version과 AuditRecord로 조회한다.

### Case snapshot references

- `operations_order_compensation_case`의 단일
  `policy_version/policy_mode/policy_validity_days`를 신규 Case의 source로 사용하지
  않는다.
- child table `operations_order_compensation_benefit_policy_snapshot`을 만들고
  `case_id`, `benefit_type`, `policy_version_id`를 저장한다.
- `(case_id, benefit_type)` UNIQUE와 policy version FK로 보호한다.
- 모든 OrderCompensationCase는 쿠폰·포인트 사용 여부와 관계없이 COUPON과 POINTS
  두 snapshot row를 정확히 하나씩 가진다.
- Case 생성 transaction은 trigger에 맞는 두 head를 잠그고 version row를 읽어
  Case·두 snapshot reference와 함께 commit한다. 하나라도 없거나 FK 저장이 실패하면
  원 Order 종료 transaction 전체를 rollback한다.
- Case 처리·재시도는 최신 head를 다시 읽지 않고 저장된 두 version ID를 사용한다.

### Event snapshots

- `OrderCancelledV1`과 `OrderRejectedV1`은 coupon·points required flag와 별개로 두
  혜택의 전체 immutable snapshot을 항상 담는다.
- 공통 snapshot shape는 `policyVersionId`, `mode`,
  `compensationValidityDays`다. event field는 `couponPolicy`와 `pointsPolicy`다.
- consumer는 event snapshot만으로 disposition을 결정하고 처리 시점의 head나 Case를
  조회하지 않는다.
- Case의 FK version과 event의 같은 benefit snapshot이 다르면 producer transaction을
  실패시키며 event를 발행하지 않는다.
- `couponRequired=false` 또는 `pointsRequired=false`는 owner 작업 불필요를 뜻할 뿐
  해당 policy snapshot 부재를 허용하지 않는다.

### Pre-release `OrderRejectedV1` contract update

- ADR-059 release gate가 `OrderRejectedV1`이 production에 배포·외부 소비되지 않았고
  완료·미완료 publication과 rollback 대상도 없음을 증거로 확인한 경우에만 V1
  payload를 제자리 변경한다.
- 단일 `policyVersion/policyMode/policyValidityDays`를 제거하고
  `couponPolicy/pointsPolicy`로 교체한다.
- producer, 모든 V1 consumer, serialization fixture, contract·통합 테스트와 문서를
  같은 변경에서 함께 갱신한다.
- gate가 통과한 경로에서는 구 payload runtime compatibility layer, V1/V2 이중 발행과
  `OrderRejectedV2`를 만들지 않는다. gate 실패 시에는 제자리 변경을 중단하고 별도
  forward migration/version ADR을 먼저 만든다.
- 이 변경은 ADR-034의 “첫 production publication부터 V1 동결” 원칙을 위반하지
  않는다. 동결 기준점 전에 계약을 확정하는 pre-release 변경이며, 이 구현이 production
  publication을 만들면 이후 V1은 동결한다.

## Alternatives Considered

### trigger별 통합 정책

- 두 head만 관리하고 Case·event snapshot이 하나라 단순하다.
- 같은 trigger 안에서 쿠폰과 포인트를 독립 운영할 수 없다.

### 고객 취소에 항상 원 만료일 유지

- 고객 책임 차등이 고정돼 단순하다.
- 운영자가 측정된 고객 경험·비용에 따라 미래 취소 정책을 바꿀 수 없다.

### Case에 coupon·points snapshot 값을 직접 저장

- 조회 join이 없다.
- Case 컬럼이 늘고 immutable policy version 원천과 값이 중복되며 참조 무결성을 FK로
  증명하기 어렵다.

### Event에 version ID만 저장

- payload가 작다.
- Promotion·Loyalty consumer가 Operations 조회 실패에 의존하고 event 하나로 결과를
  재현할 수 없다.

### `OrderRejectedV2` 도입

- 배포된 V1과의 호환 전략으로 안전하다.
- 현재는 배포·영속 V1이 없어 불필요한 listener·mapping 유지 비용만 만든다.

## Rationale

책임 원인과 혜택 종류를 정책 key로 분리하면 운영자가 서로 다른 비용과 고객 영향을
명시적으로 제어할 수 있다. append-only version과 Case FK는 선택 근거를 보존하고,
event 전체 snapshot은 owner 처리의 시간 독립성과 재현성을 유지한다.

## Consequences

- 기존 singleton head와 단일 Case policy 컬럼을 forward migration으로 이관해야 한다.
- policy head repository는 composite key row lock을 사용하며 Case 생성은 두 key를
  `COUPON → POINTS` 고정 순서로 잠근다.
- `CompensationSummary`는 단일 `policyVersion` 대신 coupon·points policy version
  reference 두 개를 반환한다.
- 기존 정책 OpenAPI의 단건 GET/PATCH 계약이 다섯 head 목록 GET과 keyed PATCH로
  바뀐다.
- ADR-033의 고객 취소 Case `policy_version NOT NULL` 미결정은 두 child FK row로
  해소된다.
- 정책 변경 중 종료 transaction은 잠금 순서에 따라 변경 전 또는 후의 완전한 두
  snapshot 중 하나를 선택하며 혼합 snapshot을 만들지 않는다.

## Failure Scenarios

- 두 head를 다른 순서로 잠그면 정책 변경과 종료가 deadlock할 수 있다.
- Case row만 commit되고 한 benefit snapshot이 누락되면 consumer 결과와 운영 조회를
  재현할 수 없다.
- 재시도 때 최신 head를 읽으면 같은 event가 정책 변경 전후 서로 다른 보상을 만든다.
- V1 producer와 consumer를 따로 배포하면 역직렬화 또는 필드 해석이 실패한다.
- pre-release 전제를 production publication 존재 확인 없이 적용하면 저장된 구 V1을
  읽지 못할 수 있다.
- `PRESERVE_ORIGINAL_EXPIRY`에서 validity days를 적용하면 의도하지 않은 새 혜택이
  발급된다.

## Verification

- 다섯 head가 독립적으로 compare-and-set 갱신되고 PARTIAL_REFUND/COUPON key는 없다.
- version row와 기존 Case snapshot은 정책 변경 후에도 변하지 않는다.
- 종료와 정책 변경 경쟁에서 두 snapshot이 모두 변경 전 또는 모두 변경 후다.
- 두 event가 전체 혜택 snapshot으로 외부 조회 없이 재현된다.
- pre-release V1 producer·모든 consumer·fixture가 한 계약으로 일치한다.

## Required Tests

- 기존 singleton policy의 STORE_REJECTION×COUPON/POINTS migration
- 고객 취소 COUPON/POINTS 초기 PRESERVE_ORIGINAL_EXPIRY seed
- 부분 환불 POINTS 초기 COMPENSATE_WITH_NEW_ISSUANCE/30 seed
- 전역 sequence ID 충돌 부재와 append-only update/delete 금지
- 다섯 head 목록 GET 인가와 응답 정렬
- PARTIAL_REFUND/COUPON keyed PATCH 404와 version/Audit 부재
- keyed PATCH의 path key·expected version·idempotency 충돌
- 두 head 동시 독립 변경과 같은 head CAS 경쟁
- Case당 COUPON/POINTS snapshot row 정확히 두 개
- `(case_id, benefit_type)` UNIQUE와 policy FK 위반 rollback
- Case 생성과 두 snapshot·event의 전체 commit 또는 rollback
- COUPON→POINTS head lock order와 deadlock 회귀
- 정책 변경 경쟁에서 혼합 snapshot 부재
- required false여도 두 event snapshot 존재
- OrderRejectedV1 구 단일 필드 compile·fixture 부재
- OrderRejectedV1 producer와 여섯 consumer 새 snapshot contract
- 구 payload compatibility layer·V2·이중 발행 부재
- 재시도 중 최신 head 조회 부재

## Metrics

- `beanflow.operations.benefit_policy.change.count{trigger,benefit_type,mode,outcome}`
- `beanflow.order.compensation.policy_snapshot.count{trigger,benefit_type}`
- `beanflow.order.compensation.policy_snapshot.failure.count{trigger,benefit_type,cause}`
- `beanflow.order.expired_benefit.count{trigger,benefit_type,mode,disposition}`

Order, Case, policy version, actor와 customer ID는 metric tag로 사용하지 않는다.

- **Not measured:** trigger×혜택별 정책 변경 빈도와 새 혜택 보상 비용

## Revisit Conditions

새 benefit type 또는 trigger가 추가되거나, 다섯 head 운영이 실제 비용 대비
과도하거나, 정책 조건에 금액·고객 segment 같은 차원이 필요해질 때

## Related Decisions

- BR-06, BR-09, BR-10, BR-14
- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-028](ADR-028-expired-benefit-restoration-policy.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-034](ADR-034-customer-cancellation-event-contract.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-042](ADR-042-benefit-restoration-ledger-metadata.md)
- [ADR-063](ADR-063-partial-refund-expired-point-restoration.md)
