# 고객이 검색·즐겨찾기·최근 주문으로 매장을 찾는다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-30-customer-account-and-login.md`, `docs/exec-plans/completed/productization-50-customer-order-read-model.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

고객이 좌표 UUID나 매장 UUID를 직접 입력하지 않고 매장명·메뉴명으로 검색하고, 즐겨찾기·최근
주문·거리 근거를 이해할 수 있는 홈 추천을 받게 한다. 반경 검색의 `pickupAvailable`도 “설정상 가능”이
아니라 실제 예약 가능한 슬롯 존재로 통일한다.

## Current State

- `GET /stores/nearby`는 PostGIS 반경 검색을 제공하지만 좌표가 필수다.
- 매장명·메뉴명 검색, 고객 즐겨찾기와 최근 주문 매장 endpoint는 없다.
- 매장 카탈로그와 7일 픽업 슬롯 조회는 ADR-076에 따라 구현돼 있다.
- 검색 결과의 픽업 가능 여부는 Merchant와 Fulfillment 경계를 넘는 batch 판정이 없다.
- `productization-30`은 Session의 `CustomerActor`, `productization-50`은 customer-scoped Order
  Projection 기반을 제공한다.

## Definitions

- **Search candidate:** 텍스트·위치·매장 상태 조건으로 Merchant가 정렬한 매장이다.
- **Pickup available:** 7일 안에 `startsAt > now`이고 잔여 capacity가 양수인 슬롯이 하나 이상 있는
  `acceptingOrders && pickupEnabled` 매장이다.
- **Recent store:** BR-40 상태 집합의 customer-owned Order를 매장별 최신 `createdAt`으로 줄인 결과다.
- **Recommendation reason:** `FAVORITE | RECENT | NEARBY` 중 병합 순서에서 처음 선택된 근거다.
- **Scan boundary cursor:** 마지막 반환 row가 아니라 마지막으로 픽업 가용성을 검사한 candidate의
  정렬 tuple을 담는 signed cursor다.

## Scope

### In Scope

- `GET /stores/search`의 매장명·판매 중 메뉴명 literal substring 검색
- 기존 `GET /stores/nearby`와 검색의 동일한 `pickupAvailable` batch 판정
- `GET /me/favorite-stores`, `PUT/DELETE /me/favorite-stores/{storeId}`
- `GET /me/recent-stores`
- `GET /me/store-recommendations`의 즐겨찾기 → 최근 → nearby 병합
- `discovery_customer_favorite_store`와 `pg_trgm` 검색 인덱스
- signed cursor, 실행계획, 개인정보 비보존과 장애 계약

### Non-goals

- 주소 geocoding, 자동완성, 오타 교정
- Elasticsearch·별도 검색 cluster
- ML ranking, 협업 필터링과 추천 점수 학습
- 즐겨찾기 폴더·메모·수동 정렬
- 서버 장바구니

## Business Rules and Invariants

1. 검색 query는 trim·연속 whitespace 축약 뒤 2~50 Unicode code point다.
2. latitude와 longitude는 둘 다 제공하거나 둘 다 생략한다. radius만 제공하면 400이다.
3. `%`, `_`, `\`는 wildcard가 아닌 literal이다. 일치 결과는 매장 단위로 한 번만 반환한다.
4. `pickupAvailable=true`는 ADR-103의 실제 슬롯 존재 판정을 사용한다. 검색과 nearby가 다른 의미를
   쓰지 않는다.
5. favorite와 recent의 customer ID는 `CustomerActor`에서 얻고 body/query로 받지 않는다.
6. favorite row는 고객·매장 쌍에 하나다. 같은 PUT과 없는 row DELETE는 부작용 없이 재실행 가능하다.
7. recent 상태·정렬·추천 병합은 BR-40을 따른다.
8. 검색어와 정밀 좌표를 DB, application log, metric tag 또는 이벤트에 저장하지 않는다.
9. 다른 Context Aggregate는 ID와 public port로만 참조하고 JPA 객체 연관관계를 만들지 않는다.

## Architecture and Transaction Boundaries

```text
GET /stores/search or /stores/nearby
  SearchController
    MerchantStoreSearchQuery.findCandidates(filters, signedCursor, limit + 1)
    FulfillmentPickupAvailabilityQuery.existsByStoreIds(candidateStoreIds, now)
    ordered candidates + availability → page + scan-boundary nextCursor

GET /me/store-recommendations
  CustomerActor
    FavoriteStoreQuery.top(customerId)
    CustomerRecentStoreQuery.top(customerId)       // Ordering public query port
    NearbyStoreQuery.top(optional coordinates)
    MerchantStoreDisplayQuery.hydrate(storeIds)
    stable de-duplication → reason 포함 response

PUT/DELETE /me/favorite-stores/{storeId}
  Tx1: visible Store 존재 확인
  Tx1: customer/store PK insert-on-conflict 또는 delete
```

- 검색은 Merchant table과 Fulfillment table을 하나의 Repository SQL로 직접 조인하지 않는다.
  Fulfillment batch port는 candidate store 집합을 한 statement로 판정한다.
- recent port는 `ordering_order`에서 customer predicate와 BR-40 상태 predicate를 함께 적용하고
  `(customer_id, state, created_at DESC, store_id)` 실행계획을 검증한다. Order Aggregate를 로딩하지 않는다.
- 추천 hydrate 중 현재 비노출 매장은 제외한다. Order snapshot과 favorite row를 변경하지 않는다.
- 모든 조회 dependency가 정상일 때만 응답한다. 일부 결과를 stale cache나 빈 단계로 대체하지 않는다.

## Alternatives Considered

- `LIKE '%query%'`: 데이터 증가 시 순차 scan이므로 기각한다.
- Elasticsearch: 색인 동기화와 별도 장애 모델이 P0 요구보다 크므로 기각한다.
- 검색 SQL에서 Fulfillment table 직접 join: Context 소유권과 query 변경 결합을 만들므로 기각한다.
- recent store 복제 table: Order 상태 변경과 동기화 실패가 새 source of truth를 만들므로 기각한다.
- 좌표 없을 때 nearby fallback: 의미 없는 위치를 추정하게 되므로 기각한다.

## Failure Semantics

- 잘못된 query·좌표 쌍·radius·cursor·limit은 400이다.
- favorite target Store가 없거나 공개 탐색 대상이 아니면 404다. 다른 고객의 favorite 개념은 노출하지 않는다.
- Fulfillment counter가 음수이거나 capacity보다 사용량이 크면 pickup 불가가 아니라 503이다.
- Merchant, Fulfillment, Ordering 또는 favorite 저장소 장애는 503이다. 빈 검색·추천으로 대체하지 않는다.
- 동시 PUT은 한 row로 수렴한다. Unique 위반을 500으로 노출하거나 중복 row를 허용하지 않는다.

## Data and Migration

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE discovery_customer_favorite_store (
    customer_id uuid NOT NULL,
    store_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (customer_id, store_id)
);

CREATE INDEX ix_discovery_favorite_customer_created
    ON discovery_customer_favorite_store (customer_id, created_at DESC, store_id);

CREATE INDEX ix_merchant_store_profile_name_trgm
    ON merchant_store_discovery_profile USING gin (lower(name) gin_trgm_ops);

CREATE INDEX ix_merchant_menu_available_name_trgm
    ON merchant_menu USING gin (lower(name) gin_trgm_ops)
    WHERE available = true;
```

- 실제 table/column 이름은 migration writer lease 획득 후 최신 schema와 대조한다.
- 다른 Context Aggregate와 JPA cascade를 만들지 않는다. Store·Customer 삭제 정책은 별도 lifecycle이
  생길 때 결정하며, P0에서는 목록 hydrate가 비노출 row를 안전하게 제외한다.
- extension 생성 권한이 없으면 migration을 실패시키고 순차 검색으로 fallback하지 않는다.

## API and Event Contracts

```http
GET    /api/v1/stores/search?query=&latitude=&longitude=&radiusMeters=&pickupAvailable=&cursor=&limit=
GET    /api/v1/stores/nearby?latitude=&longitude=&radiusMeters=&pickupAvailable=&cursor=&limit=
GET    /api/v1/me/favorite-stores
PUT    /api/v1/me/favorite-stores/{storeId}
DELETE /api/v1/me/favorite-stores/{storeId}
GET    /api/v1/me/recent-stores?limit=
GET    /api/v1/me/store-recommendations?latitude=&longitude=&radiusMeters=&limit=
```

- search 기본 page size는 20, 최대 50이다. recent/recommendations 기본 10, 최대 20이다.
- search cursor는 query, optional coordinate/radius, pickup filter와 전체 정렬 tuple을 HMAC 서명한다.
- search item은 `storeId`, 표시명, match reason, 좌표가 있을 때만 distance, pickupAvailable과
  대표 메뉴 요약을 반환한다. 내부 profile/menu 식별자는 UI 입력으로 요구하지 않는다.
- recommendation item은 매장 표시 정보와 `FAVORITE | RECENT | NEARBY` reason을 반환한다.
- 이벤트 계약은 변경하지 않는다.

## Milestones

1. migration writer lease와 최신 schema 확인, `pg_trgm`·favorite migration 작성.
2. Merchant search candidate Query와 exact 정렬·signed cursor 구현.
3. Fulfillment pickup availability batch port와 nearby 의미 통일.
4. favorite command/query와 customer ownership 계약 구현.
5. Ordering recent-store Query port와 BR-40 상태·정렬 구현.
6. recommendation 병합 endpoint 구현.
7. target/runtime OpenAPI, Error Catalog와 계약 테스트 갱신.
8. 동일 fixture의 `EXPLAIN (ANALYZE, BUFFERS)` 전후 evidence 작성.

## Required Tests

- query 길이·whitespace·literal wildcard와 case-insensitive 매장/메뉴 일치.
- 좌표 쌍·radius 경계, 좌표 유무에 따른 distance 필드·정렬 계약.
- 한 매장의 여러 메뉴 일치가 매장 한 건과 정확한 match reason으로 합쳐지는지 검증.
- pickupAvailable batch SQL 수가 candidate 수와 무관하게 고정인지 검증.
- availability 필터로 짧거나 빈 page와 nextCursor가 함께 나올 때 다음 page 누락·중복이 없는지 검증.
- 손상 slot counter가 false나 빈 page가 아닌 503인지 검증.
- favorite 동시 PUT, 반복 PUT/DELETE, 다른 customer 격리와 비노출 store 처리.
- BR-40의 모든 상태, 매장별 dedupe, 동률 tie-break와 추천 단계 dedupe.
- 좌표 없는 추천이 favorite와 recent를 유지하고 nearby를 추정하지 않는지 검증.
- raw query·좌표가 DB, log, metric tag, event에 남지 않는지 검증.
- `pg_trgm`과 recent/favorite index 실행계획 및 인덱스 추가 전후 같은 조건 측정.

## Validation Commands

```bash
./gradlew test --tests '*StoreSearch*' --tests '*FavoriteStore*' --tests '*RecentStore*'
./gradlew test --tests '*NearbyStore*' --tests '*PickupAvailability*'
./gradlew spotlessCheck
./gradlew build --stacktrace
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- 검색·nearby·recommendation p50·p95·p99
- 검색 결과 0건과 좌표 없는 검색 비율
- pickup availability batch candidate 수와 query 지연
- 추천 reason별 노출·선택 수
- favorite command 결과와 권한 거부 수
- `pg_trgm` index 크기·갱신 비용

## Documentation Updates

- [ADR-103](../../adr/ADR-103-store-search-strategy.md)
- [BR-40](../../product/business-policy-decisions.md)
- `docs/api/api-conventions.md`
- `docs/security/authorization-matrix.md`
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- 신규 검색 실행계획 evidence 문서

## Progress

아직 시작하지 않았다. 선행 plan 완료와 migration writer lease 후 `Implementation-Ready`를 전환한다.

## Surprises & Discoveries

- 기존 nearby의 픽업 가능 의미는 실제 잔여 슬롯 존재와 같지 않아 검색 endpoint만 추가해서는 화면
  간 결과가 일치하지 않는다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-12 | PostgreSQL `pg_trgm`, Context 간 batch port와 scan-boundary cursor 사용 | [ADR-103](../../adr/ADR-103-store-search-strategy.md) |
| 2026-08-12 | recent는 결제 승인 이후 현재 실행·완료 상태만 포함 | [BR-40](../../product/business-policy-decisions.md) |
| 2026-08-12 | 좌표 없는 추천도 favorite → recent 순서를 유지 | [BR-40](../../product/business-policy-decisions.md) |

## Outcomes & Retrospective

아직 없다.

## Revision Notes

- 2026-08-12: 최초 작성.
