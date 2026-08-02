# ADR-062: 정산 Batch별 Item 조회와 이의제기 식별 경로

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

점주는 `GET /stores/{storeId}/settlements`로 정산 Batch 요약을 조회할 수 있고,
`POST /settlement-items/{itemId}/disputes`로 확정 SettlementItem에 이의를 제기한다.
기존 `SettlementBatch` 응답에는 Item 목록이나 `itemId`가 없고 별도 Item 조회 경로도
없어, 문서화된 API만 사용하는 점주가 이의제기 식별자를 얻을 수 없다.

Batch 응답에 모든 Item을 중첩하면 Batch pagination 안에 크기가 다른 Item collection이
들어가고 대량 매장의 응답이 커진다. 매장 전체 Item 검색은 주문번호·기간·상태 filter와
index 정책을 추가로 결정해야 한다. MVP의 기본 사용자 흐름은 정산 Batch를 선택하고
그 명세에서 대상 Item을 찾는 방식으로 고정한다.

## Decision

- 점주용 Batch별 Item 목록 endpoint를 추가한다.
  - `GET /stores/{storeId}/settlements/{settlementBatchId}/items`
- 응답은 `SettlementItemPage`이며 각 Item에 이의제기 API가 요구하는
  `settlementItemId`를 포함한다.
- Item projection은 원장 식별과 금액 대조에 필요한 다음 snapshot만 제공한다.
  - `settlementItemId`, `settlementBatchId`, `orderId`, `completedAt`
  - `grossPaidKrw`, `feeKrw`, `benefitCostKrw`, `netSettlementKrw`, `currency`
- Batch 목록에는 Item을 내장하지 않는다. Item 목록은 독립 cursor pagination을 쓴다.
- 정렬은 `(completedAt ASC, settlementItemId ASC)`로 고정하고 cursor는 두 값을
  노출하지 않는 opaque string이다. 같은 완료 시각은 Item ID로 안정적으로 정렬한다.
- Controller는 `STORE_OWNER | STORE_STAFF` 역할을 확인하고 Application Query Service는
  `ACTIVE` StoreMembership과 Batch의 `storeId` 일치를 검증한다.
- actor가 해당 store membership을 갖지 않으면 403이다. Batch가 없거나 path의 store와
  Batch 소유 store가 다르면 actor scope에서 보이지 않는 resource로 404다.
- Controller는 Repository를 직접 호출하지 않는다. Settlement Query Service와 DTO
  projection 또는 Query Repository가 목록을 읽고 쓰기 Aggregate 객체 그래프를
  조회 편의 때문에 확장하지 않는다.
- 반환된 `settlementItemId`는 기존
  `POST /settlement-items/{itemId}/disputes`의 path parameter로 그대로 사용한다.
- 매장 전체 Item 검색은 실제 주문번호 기반 CS 요구와 filter 사용량이 확인될 때 별도
  계약으로 추가한다.

## Alternatives Considered

### SettlementBatch 응답에 Item 배열 내장

- Batch와 Item을 한 번에 그릴 수 있다.
- Batch page마다 Item 수가 달라 응답 크기와 중첩 pagination 경계가 불안정하다.

### 매장 단위 SettlementItem 검색 endpoint

- 주문번호를 알고 시작하는 CS 흐름과 여러 Batch 횡단 검색에 유리하다.
- MVP에 filter, index, 정렬과 동일 주문 결과 정책을 추가해야 한다.

### Item 조회 없이 운영 화면 내부 ID 사용

- 공개 API가 작다.
- 문서화되지 않은 데이터 경로에 의존하고 API 소비자가 이의제기 흐름을 완결할 수 없다.

## Rationale

정산 명세는 Batch가 자연스러운 탐색·인가 경계이고 Item은 수가 많아 독립 pagination이
필요하다. Batch별 endpoint는 기존 요약 응답을 작게 유지하면서 이의제기까지 이어지는
식별 경로를 공개 계약으로 완결한다.

## Consequences

- Settlement Context에 store+batch 범위의 Item Query Service/Repository가 필요하다.
- 클라이언트는 Batch 목록 뒤 선택한 Batch의 Item 목록을 한 번 더 호출한다.
- Batch-store 객체 수준 인가와 cursor scope 검증이 필요하다.
- 주문번호 직접 검색은 이번 MVP endpoint의 책임이 아니다.

## Failure Scenarios

- store ID만 검사하고 Batch 소유 store를 검사하지 않으면 다른 매장 정산 명세가
  노출된다.
- ID tie-breaker 없는 completedAt 정렬은 같은 시각 Item의 누락·중복 page를 만든다.
- 다른 Batch에서 발급한 cursor를 재사용하면 page scope가 섞일 수 있으므로 거부한다.
- JPA 쓰기 Aggregate 관계를 Batch→Items로 확장하면 대량 조회와 Aggregate 경계가
  결합된다.

## Verification

- 같은 store의 Batch Item을 안정 순서와 cursor로 전부 조회한다.
- 반환된 `settlementItemId`로 이의제기 요청을 구성할 수 있다.
- 타 store membership, revoked membership과 role 불일치를 403으로 거부한다.
- Batch-store mismatch와 보이지 않는 Batch를 404로 처리한다.
- 같은 completedAt Item의 page 경계에서 누락·중복이 없다.

## Required Tests

- empty/single/multi-page SettlementItem 목록
- `(completedAt, settlementItemId)` 동률·경계 pagination
- 다른 Batch cursor 재사용 거부
- OWNER/STAFF active membership 성공
- membership 없음·revoked·role mismatch·타 store 403
- Batch 없음과 Batch-store mismatch 404
- Item page의 `settlementItemId`와 dispute path 연결 contract
- Query Repository SQL 수와 쓰기 Aggregate 연관관계 비확장

## Metrics

- `beanflow.settlement.item_query.count{outcome}`
- `beanflow.settlement.item_query.page_size`

Store, Batch, Item, Order와 actor ID는 metric tag로 사용하지 않는다.

- **Not measured:** Batch당 Item 수와 페이지 탐색 깊이

## Revisit Conditions

주문번호 기반 CS 검색이 주요 진입 경로로 측정되거나, Batch당 Item 수와 API 호출 수가
현재 탐색 UX의 운영 문제로 확인될 때

## Implementation Evidence

- 2026-08-03 Plan 20은 `GET /stores/{storeId}/settlements/{settlementBatchId}/items`를
  `(completedAt ASC, settlementItemId ASC)` DTO projection으로 구현했다. default 20/maximum 100,
  active StoreMembership과 Batch-store 404 hiding을 적용한다.
- common signed-cursor codec에 endpoint/store/batch filter를 bind하고 15분 TTL을 사용한다.
  empty/single/multi-page, 동일 completedAt 경계, tamper/expiry/cross-store/cross-batch reuse와
  membership 테스트가 통과했다.

## Related Decisions

- BR-16, BR-22, BR-23, BR-24
- [ADR-003](ADR-003-aggregate-reference-by-id.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-018](ADR-018-settlement-dispute-hold-and-refile.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
