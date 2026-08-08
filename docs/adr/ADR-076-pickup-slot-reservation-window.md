# ADR-076: 픽업 슬롯 예약 가능 창과 조회 창의 일치

- **Status:** Accepted
- **Date:** 2026-08-08
- **Implementation owner:** [Nearby store discovery](../exec-plans/completed/nearby-store-discovery.md)

## Context

`PickupReservationService.reserve`는 슬롯 row lock, store 소속, 멱등 replay, capacity만 검증하고
슬롯의 시간은 전혀 보지 않았다. 그래서 이미 시작했거나 이미 끝난 슬롯도 `pickupSlotId`만 알면
예약할 수 있었고, `reserved_count`가 올라간 뒤 아무도 픽업할 수 없는 주문이 만들어졌다.

`GET /stores/{storeId}/pickup-slots`는 [MD-2026-010](../decisions/minor-decisions.md)에서
`ends_at > now`를 읽기 창으로 잡았다. 읽기 창과 쓰기 창이 달랐고, 그 사실은 문서에만 남아 있었다.
그 상태에서는 두 가지가 동시에 성립했다.

- 목록에 보이는 슬롯(진행 중인 슬롯)이 예약 가능한 슬롯보다 넓다.
- 목록에 보이지 않는 슬롯(이미 끝난 슬롯)도 여전히 예약된다.

BR-05는 슬롯 예약·확정·해제의 상태 전이는 정의했지만 "언제까지 예약할 수 있는가"는 정의하지
않았다. 이 결정 없이는 read repository의 predicate를 어느 쪽으로 옮겨도 근거가 없다.

## Decision

픽업 슬롯의 예약 가능 창은 **슬롯이 시작하기 전까지**다. 주입된 `Clock` 기준으로
`startsAt > now`인 슬롯만 예약할 수 있고, 조회 창은 이 창과 정확히 같다.

### Write path

`PickupReservationService.reserve`는 슬롯 row lock을 잡은 뒤, capacity를 보기 전에 시간을
검증한다.

- `startsAt > now`가 아니면 `ORDER_STATE_CONFLICT`(409)로 실패한다.
- 실패 시 `reserved_count`, `confirmed_count`와 예약 row는 그대로 둔다. 부분 상태를 만들지 않는다.
- 시간 검증은 멱등 replay 뒤에 온다. 창이 열려 있을 때 수락된 예약을 같은 `sourceReference`로 다시
  호출하면 저장된 예약 ID를 그대로 돌려준다. 창이 닫혔다는 이유로 이미 성립한 예약을 실패로
  바꾸지 않는다.
- 판정은 슬롯 row lock 안에서 하므로 동시 예약과 같은 순서로 직렬화된다.

`ORDER_STATE_CONFLICT`를 쓰는 이유는 요청 자체는 유효하고 대상 리소스의 현재 상태가 명령을
허용하지 않기 때문이다. 새 error code를 만들지 않았으므로 OpenAPI의 `OrderCreationConflict`
응답 계약은 그대로다.

### Read path

`PickupSlotQueryRepository.findOpenSlots`의 predicate는 `ends_at > now`에서 `starts_at > now`로
바꾼다. 목록에 있는 슬롯은 그 순간 예약 가능한 슬롯이고, 목록에서 사라지는 순간이 예약 불가가
되는 순간이다.

`remaining_capacity`는 `capacity - reserved_count - confirmed_count`를 그대로 투영한다. 이전의
`GREATEST(..., 0)`는 손상된 counter를 그럴듯한 "full"로 바꿔 `PickupSlotQueryService`의 음수 검사를
도달 불가능하게 만들었다. clamp를 제거해 음수는 `DEPENDENCY_UNAVAILABLE`(503)로 드러난다.

### Store availability

`acceptingOrders && pickupEnabled`가 아닌 Store의 슬롯 목록은 빈 목록이다. 이 Store의 슬롯은
`MenuQuoteCalculator`가 주문 생성에서 전부 거절하므로 예약 가능한 슬롯이 하나도 없고, 빈 목록이
사실이다. Store가 존재하는 한 응답은 `200`이며 `404`가 아니다. 없는 Store는 그대로 `404`,
Store 조회 실패는 그대로 `503`이다.

판정은 Merchant의 `StorePolicyScopeOperations.pickupOrderingAvailable`이 소유한다. Discovery는
Store flag를 직접 읽지 않고, Fulfillment는 Store 신원을 모른다. 이 호출은 기존 존재 확인 statement를
대체하므로 조회당 statement 수는 늘지 않는다.

메뉴 목록은 바뀌지 않는다. `available`은 항목별 owner state이고, 주문할 수 없는 시간에도 메뉴를
열람하는 것은 정상적인 사용이다.

## Alternatives Considered

- **종료 전까지 예약 허용(`endsAt > now`):** read 창을 그대로 두고 write만 좁히는 안이다. 슬롯
  시작 직후에 들어온 주문이 남은 창 안에서 픽업될 여지가 있다는 논리지만, 매장은 시작 시각에
  맞춰 준비를 시작하므로 이미 시작한 슬롯에 새 주문을 넣으면 준비 시간이 없다. 채택하지 않았다.
- **슬롯 시작 전 고정 lead time(예: 10분 전까지):** 준비 시간을 명시적으로 보장하지만, lead time
  값 자체가 매장별 운영 정책이고 현재 데이터 모델에 매장별 준비 시간이 없다. 없는 모델을 추정하지
  않는다. `startsAt`을 기준으로 두면 매장은 슬롯 시작 시각 자체를 조정해 같은 효과를 얻는다.
- **write 검증 없이 read 창만 넓히기:** 목록과 예약이 일치하지만, 목록을 거치지 않고 슬롯 ID를
  직접 보낸 요청은 여전히 통과한다. 계약을 클라이언트의 선의에 의존시키므로 채택하지 않았다.
- **pickup 불가 Store에 매장 가용성 필드 노출:** 슬롯을 계속 반환하고 응답에 `open`/`pickupAvailable`을
  추가하는 안이다. 원인이 명시적이지만 예약 불가능한 슬롯을 계속 노출해 위 불변식이 깨지고,
  target·runtime OpenAPI schema를 함께 바꿔야 한다. 채택하지 않았다.
- **pickup 불가 Store 카탈로그 전체를 404:** nearby가 이 Store를 숨기는 것과 같은 수준으로 감춘다.
  하지만 존재하는 Store를 없다고 응답해 "없는 Store는 404, DB 장애는 503"이라는 failure semantics의
  의미가 흐려지고, 일시적으로 주문을 닫은 매장의 메뉴 열람까지 막는다. 채택하지 않았다.

## Rationale

읽기 창과 쓰기 창이 하나면 "목록에 있으면 예약할 수 있다"가 계약이 되고, 클라이언트가 두 창의
차이를 알 필요가 없다. 판정 기준을 `startsAt` 하나로 두면 시간 비교가 단조로워 경계 정의가
모호해지지 않는다.

## Consequences

- 이미 시작한 슬롯을 지정한 주문 생성은 409로 거절된다. 이전에는 성공했다.
- `GET /stores/{storeId}/pickup-slots`는 진행 중인 슬롯을 더 이상 반환하지 않는다. 응답 스키마는
  바뀌지 않는다.
- 주문을 닫았거나 pickup을 끈 Store의 슬롯 목록은 빈 목록이다. 응답만으로는 "슬롯이 없음"과
  "매장이 pickup을 받지 않음"을 구분할 수 없다. 이는 이 결정이 받아들인 비용이며, 원인 구분이
  실제로 필요해지면 매장 가용성 필드를 추가하는 후속 결정으로 다룬다.
- 슬롯 시작 직전에 시작한 주문 생성 트랜잭션은 예약 시점 판정에서 실패할 수 있다. 이것은 정상
  동작이며 고객에게는 다른 슬롯 선택으로 안내한다.
- 손상된 counter는 이제 조회에서 503으로 드러난다. 조용히 0으로 보이지 않는다.

## Verification

- `PickupSlotReservationWindowUnitTest` — 고정 Clock에서 `startsAt == now` 거절과
  `startsAt == now + 1ns` 수락, 거절 시 예약 저장 없음.
- `PickupReservationRepositoryTest`
  - 시작한 슬롯과 끝난 슬롯의 예약 거절, `reserved_count`·`confirmed_count`·예약 row 불변.
  - 창이 열려 있을 때 수락된 예약을 창이 닫힌 뒤 같은 source로 재호출하면 같은 ID 반환, 카운터 1회만 증가.
  - 마지막 capacity에 대한 동시 예약이 정확히 한 번만 성공.
- `DiscoveryStoreCatalogIntegrationTest`
  - 끝난 슬롯과 진행 중인 슬롯 모두 목록에서 제외, `(startsAt, id)` 정렬.
  - `reserved + confirmed == capacity`인 슬롯은 `remainingCapacity = 0`으로 반환.
  - counter가 손상된 투영은 clamp되지 않고 503.
  - `acceptingOrders = false`와 `pickupEnabled = false` Store는 슬롯이 있어도 빈 목록 200이고
    메뉴는 그대로 반환된다.
- `StorePolicyScopeIntegrationTest` — `pickupOrderingAvailable`의 세 flag 조합, 없는 Store의 404,
  DataAccess 실패가 `false`가 아니라 503.
- `DiscoveryStoreCatalogQueryCountTest` — 슬롯 수와 무관하게 statement 1개.

## Metrics

새 metric을 만들지 않는다. 시간 창 위반은 `ORDER_STATE_CONFLICT`이므로
`beanflow.order.reservation.conflicts`의 resource tag 대상이 아니다. 이 창 때문에 실패하는
주문 생성 비율을 별도로 봐야 할 필요가 확인되면 그때 measurement plan과 함께 추가한다.

## Revisit Conditions

- 매장별 준비 lead time이 데이터 모델에 도입될 때.
- 빈 슬롯 목록의 원인을 클라이언트가 구분해야 한다는 요구가 실제로 확인될 때.
- 슬롯 시작 이후 도착한 주문을 다음 슬롯으로 자동 이동시키는 제품 결정이 생길 때.
- 슬롯 조회에 시간 상한이나 pagination이 도입되어 조회 창 정의가 다시 필요할 때.

## Related Decisions

- [BR-05 픽업 슬롯 확정 시점](../product/business-policy-decisions.md) — Slot Reservation Window Amendment (2026-08-08)
- [MD-2026-010](../decisions/minor-decisions.md) — 이 ADR로 대체됨
- [ADR-005](ADR-005-reservation-transaction-strategy.md) — 예약 트랜잭션과 lease
- [ADR-020](ADR-020-nearby-location-privacy.md) — Discovery read model 경계
