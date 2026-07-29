# ADR-026: MenuConfiguration의 sellable unit 요구량 번역

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

주문 API는 `menuId + optionIds + quantity`를 받지만 Inventory는 판매 재고 수량을
소유한다. Menu나 Option을 재고 Aggregate와 직접 연관시키면 메뉴 구성 의미와 수량
소유권이 섞이고, 하나의 메뉴 구성이 여러 원재고 단위를 소비하는 경우를 표현할 수
없다.

## Decision

- Merchant가 `MenuConfiguration`을 소유한다.
- MenuConfiguration은 `menuId`, 중복 없는 정규화 option ID 집합과 하나 이상의
  `SellableUnitRequirement(sellableUnitId, quantityPerLineUnit)`를 가진다.
- option ID 집합은 ID 오름차순으로 정규화하고 같은 menu 안에서 유일하다. request의
  OrderLine 순서는 BR-12 배분 의미가 있으므로 정규화하지 않는다.
- `quantityPerLineUnit`은 양수다. Ordering은 line 수량을 곱하고 여러 line에서 같은
  sellableUnitId가 나오면 overflow 없이 합산한다.
- Inventory가 SellableUnit identity와 SellableStock 수량을 소유한다. Inventory는
  menu/option 의미를 역조회하지 않고 전달받은 sellableUnitId와 총 수량만
  reserve/confirm/release한다.
- Aggregate 간에는 ID로 참조하고 Merchant가 Inventory Entity나 Repository를 직접
  참조하지 않는다.
- menu/option 소속이 잘못됐거나 정규화한 option 집합에 MenuConfiguration이 없으면
  `400 INVALID_REQUEST`다. 존재하는 configuration이 현재 판매 불가하면
  `409 MENU_CONFIGURATION_NOT_AVAILABLE`, 수량 부족은
  `409 STOCK_NOT_AVAILABLE`로 구분한다.
- 주문 생성 시 menu/option 이름·가격과 함께 sellable requirement snapshot을
  Order source reference에 연결해 이후 확정·해제와 감사에서 같은 수량을 사용한다.

## Alternatives Considered

### Merchant 소유 MenuConfiguration 번역

- 메뉴 의미와 Inventory 수량 소유권을 분리한다.
- 두 Context 사이 공개 Application API와 ID 정합성 검증이 필요하다.

### Inventory가 menu/option을 직접 해석

- Ordering 호출은 단순하다.
- Inventory가 Merchant 모델에 결합되고 옵션 정책 변경이 재고 모듈로 전파된다.

### menuOptionId를 sellable unit으로 동일시

- 단일 옵션 재고에는 단순하다.
- 옵션 없는 메뉴, 조합 SKU와 한 구성당 여러 재고 단위를 표현하지 못한다.

## Rationale

Merchant는 고객이 선택한 메뉴 구성이 무엇을 의미하는지 알고 Inventory는 실제
수량을 보호한다. 공개 requirement DTO가 두 책임을 연결하면 Aggregate 간 객체
연관관계 없이 조합 상품과 다중 재고 소비를 지원할 수 있다.

## Consequences

- Merchant schema에 normalized option-set identity와 requirement 행이 필요하다.
- MenuConfiguration을 활성화하기 전에 참조 sellableUnitId 존재를 Application
  validation 또는 DB FK 정책으로 확인해야 한다.
- Ordering은 requirement를 결정적으로 합산한 뒤 global lock order에 따라 Inventory
  API를 호출한다.
- MenuConfiguration 변경은 이미 생성된 Order와 StockReservation 수량을 바꾸지
  않는다.

## Verification

- option ID request 순서가 달라도 같은 MenuConfiguration 조회
- 중복 option ID와 다른 menu option 거부
- option 없는 기본 MenuConfiguration
- 한 configuration의 여러 sellable requirement
- 여러 OrderLine의 같은 sellable unit 합산과 overflow
- configuration 없음/판매 불가/stock 부족의 서로 다른 HTTP error
- configuration 변경 후 기존 Order reservation snapshot 불변

## Metrics

- **Not measured:** configuration lookup latency와 requirement fan-out

## Revisit Conditions

재고가 원재료 BOM, 대체재, 매장 제조 recipe 또는 외부 Inventory service를
소유하게 될 때

## Related Decisions

- BR-04
- [ADR-002](ADR-002-bounded-context-boundaries.md)
- [ADR-003](ADR-003-aggregate-reference-by-id.md)
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-005](ADR-005-reservation-transaction-strategy.md)
