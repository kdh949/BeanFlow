# ADR-098: 주문 표시용 매장명·픽업 시간 스냅샷

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Public order reference](../exec-plans/active/productization-10-public-order-reference.md)

## Context

[ADR-004](ADR-004-order-price-snapshot.md)는 주문 시점의 메뉴명, 옵션명, 단가를 스냅샷으로
보존한다. 그러나 **매장명**과 **픽업 예정 시각**은 스냅샷이 아니다. 현재 `ordering_order`는
`store_id`와 `pickup_slot_id`만 갖는다.

고객 주문 내역(`고객 4c`)은 과거 주문의 매장명과 픽업 시간을 보여준다. 이를 조인으로 해결하면
두 문제가 생긴다.

1. 매장이 이름을 바꾸거나 슬롯 정책이 바뀌면 **과거 주문의 표시가 소급 변경된다**. 고객이 본
   영수증과 다른 내용이 나중에 보인다.
2. 목록 조회마다 `merchant_store`, `fulfillment_pickup_slot` 조인이 필요해 읽기 경로가 쓰기 모델
   구조에 묶인다.

슬롯은 더 나쁘다. 매장이 정원이나 시간대를 재구성하면 과거 주문이 가리키던 슬롯의 의미가 달라진다.

## Decision

주문에 표시용 스냅샷 컬럼을 추가한다.

```text
store_name_snapshot          varchar    주문 생성 시점의 매장 표시명
pickup_window_start_snapshot timestamptz 주문 생성 시점의 픽업 슬롯 시작 시각
pickup_window_end_snapshot   timestamptz 주문 생성 시점의 픽업 슬롯 종료 시각
```

### 규칙

- 세 값은 주문 생성 시 한 번 기록하고 **이후 어떤 경우에도 변경하지 않는다**. 매장명 변경,
  슬롯 정책 변경, 매장 폐점 모두 과거 주문의 표시를 바꾸지 않는다.
- Ordering은 Merchant의 신규 `StoreDisplaySnapshotOperations`에서 owner-verified
  `merchant_store_discovery_profile.name`을 받는다. 프로필 누락·공백·store 불일치는 주문 생성 실패며
  메뉴명이나 UUID 문자열로 대체하지 않는다.
- Ordering은 Fulfillment가 픽업 슬롯 row를 잠근 상태에서 반환하는 `PickupReservationGrant.startsAt`과
  `endsAt`을 사용한다. 예약 후 슬롯을 별도 재조회해 다른 시점의 값을 조합하지 않는다.
- 고객·점주 조회 응답은 스냅샷을 사용한다. `merchant_store`와 `fulfillment_pickup_slot`을
  조인해 현재 값을 보여주지 않는다.
- `store_id`와 `pickup_slot_id`는 그대로 유지한다. 스냅샷은 표시용이고, 예약·정산·권한 판정은
  계속 ID로 수행한다.
- 픽업 슬롯 시각 변경이 **진행 중 주문에 영향을 주는 제품 기능**이 필요해지면, 스냅샷을
  덮어쓰는 것이 아니라 명시적 변경 이력을 별도로 기록한다. 이 ADR은 그 기능을 도입하지 않는다.
- 스냅샷 값은 [ADR-068](ADR-068-immutable-integration-event-snapshots.md)의 이벤트 payload에
  자동 포함되지 않는다. 이벤트 계약 변경은 별도 결정이다.

### 마이그레이션

- 기존 주문은 현재 `merchant_store_discovery_profile.name`과
  `fulfillment_pickup_slot.starts_at/ends_at`으로 backfill한다. 이는 근사값이며
  주문 당시 값이 아닐 수 있다. 이 사실을 migration 주석과 runbook에 명시한다.
- profile 또는 slot이 없는 기존 주문은 placeholder로 채우지 않고 backfill을 실패시킨다. 누락 원인을
  owner context에서 복구한 뒤 재실행한다.
- backfill 후 `NOT NULL`을 적용한다.

## Alternatives Considered

### 1. 조회 시 조인

- 장점: 스키마 변경이 없다.
- 단점: 과거 주문 표시가 소급 변경된다. 목록 조회가 두 테이블에 더 의존한다. 매장 삭제 시
  과거 주문 표시가 깨진다.

### 2. 별도 `order_display_snapshot` 테이블

- 장점: 주문 테이블이 넓어지지 않는다.
- 단점: 모든 목록 조회에 조인이 하나 늘어난다. 1:1 관계를 분리할 이유가 없다.

### 3. 스냅샷을 JSON 컬럼 하나로 저장

- 장점: 이후 필드 추가가 쉽다.
- 단점: 정렬·필터에 쓸 수 없고 타입 검증이 약해진다. 픽업 시각은 주문보드 정렬 키이므로
  컬럼이어야 한다.

## Rationale

주문은 거래 기록이다. 거래 기록의 표시가 나중에 바뀌면 고객 문의와 분쟁에서 근거로 쓸 수 없다.
[ADR-004](ADR-004-order-price-snapshot.md)가 가격에 적용한 원칙을 표시 값에도 동일하게 적용한다.

픽업 시각을 컬럼으로 두는 두 번째 이유는 점주 주문보드의 정렬 키이기 때문이다
([ADR-100](ADR-100-store-order-board-read-model.md)). 조인 없이 인덱스를 구성할 수 있다.

## Consequences

- `ordering_order`에 컬럼 3개가 추가된다. Flyway migration과 backfill이 필요하다.
- 기존 주문의 backfill 값은 주문 당시 값이 아닐 수 있다. 이 한계를 문서에 남긴다.
- 매장명 변경이 과거 주문에 반영되지 않는다는 사실을 점주·고객센터 화면에 설명해야 한다.
- 주문 목록 조회에서 두 테이블 조인이 사라진다.

## Verification

- 주문 생성 후 매장명을 바꿔도 기존 주문 조회 결과가 바뀌지 않는지 검증한다.
- 슬롯 정책을 바꿔도 기존 주문의 픽업 시각 표시가 바뀌지 않는지 검증한다.
- 고객 주문 목록 조회 SQL에 `merchant_store`·`fulfillment_pickup_slot` 조인이 없는지 검증한다.
- backfill migration 후 모든 주문이 `NOT NULL` 제약을 만족하는지 검증한다.
- 주문 생성 시 Store profile 누락과 Pickup grant 시각 누락이 fallback 없이 전체 rollback인지 검증한다.

## Metrics

- 주문 목록 조회의 SQL 수와 실행계획
- 주문 목록 조회 지연 p50·p95

## Revisit Conditions

- 픽업 시각 변경이 진행 중 주문에 반영되어야 하는 제품 요구가 생길 때
- 다국어 매장명 표시가 필요할 때
- 스냅샷 필드가 5개를 넘어 별도 테이블 분리 이득이 생길 때

## Related Decisions

- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-096](ADR-096-public-order-reference.md)
- [ADR-097](ADR-097-store-pickup-number.md)
- [ADR-099](ADR-099-customer-order-read-model.md)
- [ADR-100](ADR-100-store-order-board-read-model.md)
