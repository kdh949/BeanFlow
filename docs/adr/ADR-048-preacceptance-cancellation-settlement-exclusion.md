# ADR-048: 매장 수락 전 고객 취소 환불의 정산 제외 증적

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

고객 직접 취소는 `PENDING_PAYMENT` 또는 매장 수락 전 `PAID`에서만 허용된다.
BR-16과 ADR-017은 `COMPLETED` Order만 SettlementItem의 원천으로 삼는다. 따라서
고객 취소 Order에는 정상적으로 SettlementItem이 없다.

Settlement가 일반 `PaymentRefunded`를 소비할 때 원 SettlementItem이 없다는 이유로
계속 실패하면 정상적인 미완료 주문 환불이 publication backlog에 고착된다. 반대로
0원 Adjustment나 음수 Adjustment를 만들면 실제 인도되지 않아 정산된 적 없는 거래를
매장 정산 원장에 넣게 된다. 별도 제외 원장은 추적에는 유리하지만 새 Aggregate와
schema를 추가한다.

## Decision

- 매장 수락 전 고객 취소 Order와 그 성공 Refund에는 SettlementItem을 만들지 않는다.
- 해당 Refund 때문에 SettlementAdjustment도 만들지 않는다. 이는 확정 후 환불
  조정이 아니라 애초에 정산 대상이 아니었던 거래다.
- Settlement의 `PaymentRefunded` consumer는 다음 조건이 모두 맞으면
  `NOT_APPLICABLE`로 멱등 완료한다.
  - Order가 `CANCELLED`
  - `cancellationCause = CUSTOMER_REQUEST`
  - Order가 `ACCEPTED`, `PREPARING`, `READY`, `COMPLETED`를 거치지 않은 고객 취소
    source임
  - Refund가 `SUCCEEDED`
  - Refund reason이 `CUSTOMER_ORDER_CANCELLED`
  - Refund source가
    `order:{orderId}:customer-cancellation:{aggregateVersion}:payment`
  - 해당 Order의 SettlementItem이 없음
- `NOT_APPLICABLE` 판정 transaction은 source reference당 하나의 append-only
  AuditRecord를 저장한 뒤에만 event publication을 완료 처리한다.
- AuditRecord 계약은 다음과 같다.

  | 필드 | 값 |
  |---|---|
  | `actorType` | `SYSTEM` |
  | `action` | `SETTLEMENT_REFUND_EXCLUDED` |
  | `targetType` | `REFUND` |
  | `targetId` | 내부 Refund ID |
  | `reason` | `ORDER_NOT_COMPLETED_CUSTOMER_CANCELLATION` |
  | `sourceReference` | 고객 취소 Refund source reference |
  | `beforeSummary` | `settlementItemExists=false` |
  | `afterSummary` | `settlementDisposition=NOT_APPLICABLE` |

- `(action, target_type, target_id, source_reference)` 또는 동등한 Unique Constraint로
  consumer replay에서 AuditRecord가 하나만 존재하게 한다.
- Audit insert가 실패하면 publication을 완료하지 않고 rollback해 기존 event
  bounded retry와 `MANUAL_REVIEW`를 따른다. Audit 없이 `NOT_APPLICABLE` 성공으로
  처리하지 않는다.
- 조건 일부가 맞지 않거나 SettlementItem이 이미 존재하면 정상 제외로 추측하지
  않는다. `SETTLEMENT_SOURCE_CONFLICT`로 publication을 실패시키고 운영 검토로 보낸다.
- 운영 조회는 별도 제외 Entity나 materialized status를 만들지 않고 다음 원천을
  조합해 “주문 미완료로 정산 제외”를 표시한다.
  - Order의 `CANCELLED`와 `CUSTOMER_REQUEST`
  - 같은 고객 취소 source Refund의 `SUCCEEDED`
  - `SETTLEMENT_REFUND_EXCLUDED` AuditRecord
- 세 원천 중 하나가 없거나 서로 다른 terminal version이면 제외 완료로 표시하지 않고
  `INCONSISTENT` 운영 상태와 원천별 누락을 보여준다.
- 0원 SettlementAdjustment와 별도 SettlementExclusion 원장은 만들지 않는다.
- `PENDING_PAYMENT`와 `BENEFIT_ONLY` 취소는 현금 Refund가 없으므로 이 consumer
  판정과 AuditRecord를 만들지 않는다.

## Alternatives Considered

### 0원 SettlementAdjustment

- 기존 Settlement 원장에서 처리 흔적을 찾기 쉽다.
- 금액 변화가 없는 row가 Adjustment 수와 성공률을 오염시키고 “확정 후 보정”이라는
  원장 의미를 훼손한다.

### 별도 SettlementExclusion 원장

- 제외 결과를 정규화해 조회하기 쉽다.
- MVP에 새 Aggregate, migration, API와 retention 정책이 필요하며 Order·Refund·Audit로
  이미 사실을 재현할 수 있다.

### 아무 기록 없이 publication 완료

- 쓰기가 가장 적다.
- consumer가 실제로 판정했는지 단순 listener 누락인지 운영자가 구분할 수 없다.

## Rationale

정산 원장은 실제 정산 금액만 보존하고, 정상 제외 판정의 처리 증거는 이미
append-only·중복 방지·보존 정책을 가진 AuditRecord에 남기는 것이 각 원장의 의미를
가장 잘 유지한다. 운영 projection은 원천 사실을 조합하므로 별도 상태 drift도
피한다.

## Consequences

- Settlement consumer는 고객 취소 Refund를 명시적으로 분류하고 Audit API를 같은
  transaction에서 호출한다.
- 운영 화면은 저장된 단일 제외 상태가 아니라 세 원천의 정합성을 표시한다.
- 정상 고객 취소 환불은 SettlementItem/Adjustment 건수와 금액에 포함되지 않는다.

## Failure Scenarios

- SettlementItem 부재를 모든 Refund에 정상으로 처리하면 완료 주문 정산 조정 누락을
  숨길 수 있다.
- Audit 저장 실패를 삼키면 publication은 완료됐지만 제외 판정 증적이 없다.
- 0원 Adjustment를 만들면 실제 조정이 없는 거래가 정산 원장과 지표를 오염시킨다.
- Order와 Refund의 terminal version이 다르면 다른 취소 사실을 결합해 잘못
  제외할 수 있다.
- 운영 조회가 Audit만 보면 Refund 성공 전에도 제외 완료로 오인할 수 있다.

## Verification

- 고객 취소 성공 Refund의 SettlementItem·Adjustment 0건
- source당 `SETTLEMENT_REFUND_EXCLUDED` AuditRecord 한 건
- Audit commit 뒤에만 publication 완료
- replay의 Audit·원장 row 수 불변
- 불일치 source와 예상 밖 SettlementItem의 명시적 conflict
- 운영 파생 조회의 정상 제외와 원천 누락 `INCONSISTENT`

## Required Tests

- 미수락 `PAID` 고객 취소 환불 성공의 NOT_APPLICABLE 처리
- direct/REQUEST retry/LOOKUP 성공 경로의 동일 결과
- Audit insert 실패의 consumer rollback과 publication retry
- 같은 event ID와 새 event ID replay의 Audit 한 건
- Refund reason/source/Order cause/version 각 불일치 conflict
- 예상 밖 SettlementItem 존재 conflict와 Adjustment 미생성
- `PENDING_PAYMENT`, `BENEFIT_ONLY`의 Audit 미생성
- 매장 거절·부분 환불·완료 후 환불이 이 분기를 사용하지 않음
- 운영 projection의 세 원천 조합과 누락별 상태

## Metrics

- `beanflow.settlement.refund.disposition.count{disposition,reason}`
- `beanflow.settlement.refund.exclusion_conflict.count{reason}`

Order, Payment, Refund, Customer와 Store 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 고객 취소 제외 처리의 정산 금액 효과 — 항상 0원

## Revisit Conditions

회계 규정이 정산 제외 거래의 별도 보조원장을 요구하거나 실제 PG 환불 수수료를
매장에 귀속할 때

## Related Decisions

- BR-14, BR-16, BR-21, BR-30
- [ADR-008](ADR-008-settlement-adjustment-ledger.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-036](ADR-036-cancellation-after-partial-refund.md)

## Implementation Evidence

- 2026-08-03 Plan 20은 V21에 최소 Order `cancelled_at`/`cancellation_cause` evidence와
  fail-closed legacy precheck를 추가하고 기존 결제 거절을 `PAYMENT_DECLINED`로 기록한다.
- `beanflow.settlement.payment-refunded-v1` listener는 public Ordering/Payment evidence API로
  실제 Order cause/lifecycle과 Refund state/reason/source/version/amount/time을 읽고 Item 부재까지
  맞는 경우에만 exact `SETTLEMENT_REFUND_EXCLUDED` Audit을 append한다.
- Testcontainers 통합 테스트가 같은/새 event replay Audit 1건, Item 0건, cause/reason/source/version
  불일치 conflict, 기존 Item 비덮어쓰기, Audit failure rollback과 Audit commit 뒤 persistent
  publication completion을 검증한다.
