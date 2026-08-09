# ADR-077: 빠른 재주문의 새 Order 생성과 API identity

- **Status:** Accepted
- **Date:** 2026-08-09

## Context

고객이 과거 Order의 구성을 빠르게 다시 주문할 수 있어야 하지만, 현재 BeanFlow에는
draft·quote·Reorder Aggregate와 그 만료·재검증 생명주기가 없다. 기존 주문 생성은
`POST /api/v1/orders`와 ADR-005/025의 원자적 예약·멱등 경계를 갖는다. 빠른 재주문을
기존 생성 request variant로 섞거나 최상위 Reorder resource로 만들면 source Order와
새 Order의 관계, 멱등 operation scope와 성공 의미가 모호해진다.

## Decision

- 빠른 재주문 endpoint는 `POST /api/v1/orders/{sourceOrderId}/reorders`다.
- 성공은 draft나 quote가 아니라 source와 다른 ID의 새 `Order` 생성이며, 기존 주문
  생성과 같은 상태별 결과를 `201 Created`로 반환한다.
- 새 Order와 필요한 owner 예약·immutable snapshot·terminal 멱등 응답이 commit되기
  전에는 `201`을 반환하지 않는다.
- `Reorder`는 별도 Aggregate나 영속 resource가 아니다. URI의 `reorders`는 source
  Order를 새 Order 생성 command의 입력으로 사용하는 하위 command collection이다.
- Idempotency-Key 범위는 BR-25에 따라
  `actorId + REORDER_ORDER_V1 + Idempotency-Key`다. 직접 주문 생성의
  `CREATE_ORDER` scope와 분리한다.
- canonical payload는 최소 `sourceOrderId`와 이후 API 계약에서 확정할 모든 명시적
  request field를 포함한다. 같은 key라도 source Order 또는 request field가 다르면
  `409 IDEMPOTENCY_KEY_REUSED`이며 첫 Order의 응답을 재생하지 않는다.
- 같은 scope·key·canonical payload의 terminal 재요청은 ADR-057에 따라 최초 HTTP
  status와 body를 그대로 재생하고 business response나 header에 replay indicator를
  추가하지 않는다.
- source OrderLine에서는 `menuId`, ID 오름차순의 중복 없는 `optionIds`, `quantity`만
  새 주문 생성 입력으로 복사한다. note, 이름과 가격 snapshot은 복사 입력이 아니다.
- Ordering은 향후 migration 이후 생성되는 OrderLine에 normalized option ID snapshot을
  보존한다. 기존 line에 검증된 snapshot이 없으면 옵션 이름, sellable requirement 또는
  현재 Merchant state로 ID를 추론하지 않고 재주문 불가로 실패한다.
- 성공 응답은 기존 `CreateOrderResult`의 상태별 `order`와 선택적 `payment` 의미를
  유지하되 재주문 전용 `ReorderOrderResult`로 가격 비교를 함께 반환한다.
- 가격 비교는 혜택 적용 전 source/current 가격만 다룬다. required summary는
  `hasPriceChanges`, `sourceSubtotalKrw`, `currentSubtotalKrw`, signed
  `subtotalDifferenceKrw`와 `items`를 가진다. `items`에는 단가가 달라진 line만 source
  `lineSequence` 순서로 포함한다.
- 각 변경 item은 `sourceOrderLineId`, `lineSequence`, `menuId`, `quantity`,
  `sourceUnitPriceKrw`, `currentUnitPriceKrw`, `sourceLineGrossKrw`,
  `currentLineGrossKrw`, signed `lineDifferenceKrw`를 가진다. 차이는 항상 current에서
  source를 뺀 값이다. 변경이 없으면 `hasPriceChanges=false`, subtotal difference 0과 빈
  `items`를 반환한다.
- source 값은 immutable OrderLine snapshot에서, current 값은 실제 새 Order에 저장한
  Merchant quote에서 가져온다. 가격 비교와 새 Order snapshot이 다르면 성공 응답을
  만들지 않는다. 쿠폰·포인트·payment 선택·배분 차이는 이 비교에 포함하지 않는다.
- 가격 변경은 재주문 실패 사유가 아니다. 고객은 line별 비교가 포함된 `201`을 받는
  시점에 이미 새 Order와 예약이 commit되었다는 결과 의미를 유지한다.

## Alternatives Considered

### `POST /api/v1/orders`에 `sourceOrderId` 추가

- 기존 resource collection을 그대로 사용한다.
- 직접 생성과 재주문 request가 하나의 endpoint에서 variant가 되고, operation별
  멱등성·인가·오류 계약이 복잡해진다.

### `POST /api/v1/reorders`

- source ID를 body에 둘 수 있다.
- 별도 Reorder resource가 존재하는 것처럼 보이며 확정된 Aggregate 부재와 맞지 않는다.

### draft 또는 quote 생성

- 고객이 가격 변동을 확인한 뒤 별도 확정할 수 있다.
- 새 resource의 만료, stale 재검증, 후속 create와 멱등성 생명주기가 필요하다.

## Rationale

source Order를 URI에서 명확히 식별하면서도 결과와 원자성은 이미 검증된 Order 생성
경계에 둔다. 별도 operation scope는 같은 고객이 직접 생성과 재주문에 동일한 key를
사용해도 두 API의 request identity가 섞이지 않게 한다.

## Consequences

- target OpenAPI에 새 path와 operation이 추가되지만 구현 전까지 runtime OpenAPI에는
  추가하지 않는다.
- 성공 시 고객은 이미 생성·예약된 Order를 받으므로 가격 확인 후 확정 단계가 없다.
- request schema와 실패 상세는 이 ADR의 후속 결정으로 계약을 닫아야 한다.
- 기존 OrderLine에는 option ID snapshot이 없어 모든 기존 주문을 재주문 가능하게
  backfill할 수 없다. 구현 migration은 snapshot 부재와 옵션 없는 빈 선택을 구분해야 한다.
- `REORDER_ORDER_V1` operation과 terminal response는 BR-26의 90일 보존 정책을 따른다.

## Verification

- target OpenAPI path·operationId·201 response와 ADR 일치
- 직접 생성과 재주문의 같은 key가 서로 다른 operation scope를 사용
- 같은 key·같은 canonical payload의 최초 201/4xx/503 exact replay
- 같은 key와 다른 source Order 또는 request field의 409와 첫 응답 미노출
- 새 Order·owner 예약·snapshot commit 전 201 부재
- Reorder Aggregate, table과 repository 부재
- source line의 normalized option ID·수량 복사와 note 부재
- option ID snapshot이 없는 기존 line의 명시적 실패와 이름·현재값 추론 부재
- 가격 동일 시 빈 변경 목록과 0 difference, 가격 상승·하락 시 source line 순서의
  정확한 signed line/subtotal difference
- 쿠폰·포인트 선택 변경이 price-change item을 만들지 않음
- 가격 summary와 새 OrderLine/current subtotal의 tie-out

## Metrics

- 재주문 attempt·success·failure count by stable outcome code
- terminal response replay와 key-reuse conflict count
- Order, customer, source ID와 raw Idempotency-Key는 metric tag에 넣지 않는다.
- **Not measured:** 재주문 빈도, 성공률과 주문 생성 지연

## Revisit Conditions

가격 확인 후 명시적 승인, 장바구니 편집, 장기 유지 quote, 외부 partner용 재주문
identity 또는 API version 분리가 필요할 때

## Related Decisions

- BR-03, BR-25, BR-26
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-005](ADR-005-reservation-transaction-strategy.md)
- [ADR-025](ADR-025-order-creation-idempotency-transaction.md)
- [ADR-057](ADR-057-idempotent-response-replay-indicator.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
