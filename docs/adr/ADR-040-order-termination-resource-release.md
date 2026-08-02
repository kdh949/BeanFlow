# ADR-040: 주문 종료 후 확정 픽업·재고 복원 상태

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

미수락 `PAID` 고객 취소는 매장 거절과 동일하게 확정 픽업 슬롯과 재고를 복원한다.
현재 Fulfillment와 Inventory는 상태 `RELEASED_BY_REJECTION`, 메서드
`releaseConfirmedByRejection`·`restoreConfirmedByRejection`과
`restoration_source_reference`를 사용한다. 고객 취소 listener가 이 경로를 그대로
호출하면 취소 복원이 거절로 저장된다.

원인별 terminal 상태를 추가하면 사실은 보존되지만 같은 수량 복원과 멱등성 전이가
두 벌로 늘어난다. 반대로 상태만 일반화하고 원인을 저장하지 않으면 감사와 운영에서
매장 거절과 고객 취소를 구분할 수 없다.

## Decision

- PickupReservation과 StockReservation의
  `RELEASED_BY_REJECTION`을 `RELEASED_AFTER_TERMINATION`으로 일반화한다.
- 두 owner에 nullable `restoration_trigger`를 추가하고 닫힌 초기 값
  `STORE_REJECTION`, `CUSTOMER_CANCELLATION`을 CHECK로 강제한다.
- `RELEASED_AFTER_TERMINATION`일 때 `restoration_trigger`와
  `restoration_source_reference`는 모두 필수다. 다른 상태에서는 둘 다 null이어야
  한다.
- 기존 `RELEASED_BY_REJECTION` row는 forward migration에서 상태를
  `RELEASED_AFTER_TERMINATION`, trigger를 `STORE_REJECTION`으로 backfill한다.
- **Clean-cutover note (2026-08-01):** ADR-059 release gate가 `PASSED`인 동안 위
  backfill의 대상 row는 0이므로 V9 대체 migration이 최종 상태 enum과 CHECK를 직접
  만든다. backfill 규칙은 gate가 nonzero 또는 unknown이 될 때를 위해 그대로 남기고,
  migration은 두 경로 모두에서 후보 row 수를 확인한다.
- owner API는 거절 전용 이름을 다음 공통 이름으로 바꾸고 trigger를 명시적으로 받는다.

  | Owner | 변경 후 API |
  |---|---|
  | Fulfillment | `releaseConfirmedAfterTermination(orderId, trigger, now, sourceReference)` |
  | Inventory | `restoreConfirmedAfterTermination(orderId, trigger, now, sourceReference)` |

- trigger enum은 Operations 타입을 참조하지 않는 안정적인 shared owner API 계약으로
  둔다. listener가 `OrderRejectedV1 → STORE_REJECTION`,
  `OrderCancelledV1 → CUSTOMER_CANCELLATION`으로 명시적으로 매핑한다.
- 고객 취소 source reference는 ADR-034의
  `order:{orderId}:customer-cancellation:{aggregateVersion}:{step}`을 사용한다.
  매장 거절의 기존 event source 형식은 migration하지 않고 새 공통 API가 그대로
  보존한다.
- 동일 source reference, trigger와 동일 terminal 상태의 중복 호출은
  `ALREADY_APPLIED`이며 수량을 다시 변경하지 않는다.
- 다른 source reference 또는 trigger가 이미 terminal row를 점유했거나 현재 상태가
  `CONFIRMED`가 아니면 상태와 수량을 덮어쓰지 않고
  `COMPENSATION_SOURCE_CONFLICT`로 owner publication을 실패시킨다.
- 충돌은 bounded publication retry 뒤 해당 PICKUP 또는 STOCK step만
  `MANUAL_REVIEW`로 전환한다. Order terminal 상태와 다른 owner step은 되돌리거나
  중단하지 않는다.
- Pickup과 Stock 복원은 각각 owner listener transaction에서 row lock과 guarded
  transition으로 수행한다. 둘을 한 distributed transaction으로 묶지 않는다.

## Alternatives Considered

### 원인별 terminal 상태 추가

- 각 row의 상태만으로 원인을 읽을 수 있다.
- 같은 수량 복원 전이, API 분기와 DB CHECK가 원인 수만큼 늘어나고 새 종료 원인마다
  enum을 확장해야 한다.

### `RELEASED_BY_REJECTION` 재사용

- migration과 기존 listener 변경이 가장 적다.
- 고객 취소 사실을 거절로 기록해 감사·운영 의미가 틀리고 이름이 새 event 계약과
  충돌한다.

### 일반 상태와 source reference만 저장

- 새 trigger 컬럼이 필요 없다.
- 원인을 source 문자열 파싱에 의존하며 형식 변경이나 legacy source에서 안전하지
  않다.

## Rationale

확정 수량을 되돌리는 도메인 전이는 원인과 무관하게 하나지만, 책임과 운영 조사는
원인을 필요로 한다. 일반 terminal 상태와 닫힌 trigger를 분리하면 전이를 복제하지
않으면서 원인을 구조화해 보존할 수 있다.

## Consequences

- ADR-059 gate가 유효한 clean-cutover 경로에서는 Plan 30이 V9 source를 최종 상태와
  trigger/source CHECK로 직접 작성한다. legacy 후보가 하나라도 있으면 V9 precheck가
  backfill을 추측하지 않고 실패한다. gate가 무효화된 forward 경로에서만 상태 update와
  trigger backfill을 별도 migration으로 설계한다.
- 두 owner enum, entity, public API, 거절 listener와 테스트 이름이 바뀐다.
- 기존 거절 source reference는 그대로 유지되므로 진행 중 publication replay와
  migration 후 row 멱등성이 깨지지 않는다.
- 고객 취소 listener는 event ID가 아닌 Order terminal version 기반 source를
  사용한다.
- owner 운영 조회에는 state와 trigger, source reference를 함께 표시해야 한다.

## Failure Scenarios

- 상태만 rename하고 trigger backfill을 누락하면 기존 row가 CHECK를 위반하거나 원인을
  잃는다.
- source 문자열을 파싱해 trigger를 추론하면 legacy 형식이나 손상된 값이 잘못
  분류된다.
- 다른 trigger의 terminal row를 성공으로 간주하면 원인 충돌이 숨겨지고 잘못된
  publication이 완료된다.
- 동일 event의 재전달에서 수량을 다시 더하면 슬롯 capacity와 재고 수량이 부풀려진다.
- Inventory와 Fulfillment를 한 transaction으로 묶으면 owner 경계와 독립 복구를
  깨뜨린다.

## Verification

- 거절과 고객 취소가 같은 terminal state, 서로 다른 trigger로 저장된다.
- migration 전 거절 row가 source를 유지한 채 정확히 backfill된다.
- 동일 source 중복은 수량을 한 번만 복원한다.
- 다른 source/trigger 충돌은 수량과 terminal row를 바꾸지 않는다.
- 한 owner 실패가 다른 owner나 Order 상태를 되돌리지 않는다.

## Required Tests

- V9 기존 거절 row의 상태·trigger forward migration
- migration 재실행 안전성과 CHECK 검증
- Pickup 고객 취소 `CONFIRMED → RELEASED_AFTER_TERMINATION`
- Stock 고객 취소 전 row의 동일 전이와 수량 tie-out
- 거절·취소 trigger 매핑
- 상태별 trigger/source nullability CHECK
- 같은 source·trigger duplicate의 `ALREADY_APPLIED`
- 다른 source 또는 trigger의 `COMPENSATION_SOURCE_CONFLICT`
- event ID가 다른 같은 Order version 재처리의 수량 한 번 복원
- Pickup 실패와 Stock 성공의 독립 step 상태
- bounded retry 소진 시 해당 step만 manual review

## Metrics

- `beanflow.resource.restoration.count{owner,trigger,outcome}`
- `beanflow.resource.restoration.source_conflict.count{owner,trigger}`
- `beanflow.resource.restoration.lag{owner,trigger}`

Order, reservation, stock unit, store와 customer ID는 metric tag로 사용하지 않는다.

- **Not measured:** trigger별 확정 자원 복원량과 충돌 원인 분포

## Implementation Checkpoint (2026-08-03)

- V9가 Pickup·Stock의 `RELEASED_AFTER_TERMINATION`, trigger/source nullability와 legacy
  `RELEASED_BY_REJECTION` 후보 0 precheck를 직접 만든다.
- 두 owner API와 `OrderRejectedV1`/`OrderCancelledV1` listener는 trigger를 명시적으로
  전달하며 source 문자열을 파싱하지 않는다.
- owner transaction은 각 reservation row만 잠그고 동일 source/trigger replay에는 수량을
  다시 변경하지 않으며 mismatch는 `COMPENSATION_SOURCE_CONFLICT`로 실패한다.

## Revisit Conditions

고객 취소·매장 거절 외에 별도 책임과 복원 정책을 가진 종료 원인이 추가되거나, 원인별
수량 처리 자체가 달라질 때

## Related Decisions

- BR-06, BR-14
- [ADR-003](ADR-003-aggregate-reference-by-id.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-034](ADR-034-customer-cancellation-event-contract.md)
