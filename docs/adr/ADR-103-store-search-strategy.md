# ADR-103: 매장 검색 전략과 추천 Baseline

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Customer store discovery](../exec-plans/active/productization-70-customer-store-discovery.md)

## Context

`GET /stores/nearby`는 좌표 반경 검색만 지원한다
([ADR-020](ADR-020-nearby-location-privacy.md), PostGIS 기반). 디자인의 매장 찾기(`고객 1b`)는
여기에 더해 다음을 요구한다.

- 매장명·메뉴명 검색
- 영업 중·픽업 가능 필터
- 거리·인기 정렬
- 위치 권한 거부 시 대체 경로(`고객 5c`)
- 홈의 초기 추천(`고객 1a`)

한편 위치 권한이 없으면 반경 검색 자체가 불가능하다. 그래서 "검색"은 위치 기능이 아니라 별도
탐색 경로여야 한다.

## Decision

### 검색 구성

| 요구 | 구현 |
|---|---|
| 위치 반경 | PostGIS `ST_DWithin` (기존 유지) |
| 거리 정렬 | `(distance, storeId)` Cursor |
| 이름·메뉴 검색 | PostgreSQL `pg_trgm` GIN 인덱스 |
| 필터 | 주문 접수 중, 실제 예약 가능 슬롯 존재, 거리 상한, 즐겨찾기 |
| 위치 없는 경로 | 이름 검색 + 즐겨찾기 + 최근 주문 매장 |

- 검색과 반경 조회는 **별도 endpoint**다. `GET /stores/nearby`는 좌표 필수, `GET /stores/search`는
  좌표 선택이다. 좌표가 없으면 거리 정렬을 하지 않고 그 사실을 응답에 표시한다.
- `pickupAvailable`은 `acceptingOrders && pickupEnabled`이고, ADR-076의 7일 조회 창 안에
  `startsAt > now`이며 `capacity - reservedCount - confirmedCount > 0`인 슬롯이 하나 이상 있다는
  뜻이다. 조회 시점 Projection이며 동시 예약 뒤 주문 생성이 `PICKUP_SLOT_FULL`로 실패할 수 있다.
- Merchant 검색 query가 Fulfillment table을 직접 조인하지 않는다. Merchant가 정렬된 candidate
  `limit + 1`개를 반환하면 Fulfillment의 batch port가 한 statement로 store별 가용 슬롯 존재를
  판정한다. 손상 counter가 하나라도 있으면 false가 아니라 503이다.
- candidate를 순서대로 검사해 응답 limit에 도달할 때까지만 scan boundary를 전진한다. 가용성 필터로
  결과가 짧거나 비어도 뒤 candidate가 있으면 signed `nextCursor`를 반환한다. cursor는 마지막 **반환
  row**가 아니라 마지막 **검사 candidate**의 정렬 tuple과 전체 filter를 담아 누락을 막는다.
- 기존 `GET /stores/nearby`의 `pickupAvailable`도 같은 batch 판정으로 바꾼다. endpoint마다 다른
  의미를 사용하지 않는다.
- 텍스트 검색은 매장명과 메뉴명을 대상으로 하되, 결과는 항상 **매장 단위**로 집계한다. 메뉴가
  검색어와 일치했다는 사실은 응답에 근거로 표시한다.
- 검색 결과에도 Cursor Pagination을 적용한다([ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)).
- 위치 값의 보존 정책은 [ADR-020](ADR-020-nearby-location-privacy.md)를 그대로 따른다. 검색어는
  개인 식별 목적으로 저장하지 않는다.

### 텍스트·정렬·cursor 계약

- `GET /stores/search`의 `query`는 trim하고 연속 whitespace를 한 칸으로 축약한 2~50 Unicode code
  point다. 빈 값·1자·초과는 400이며 서버가 임의로 nearby 또는 추천 결과로 바꾸지 않는다.
- 좌표는 latitude/longitude를 둘 다 주거나 둘 다 생략한다. 하나만 있거나 좌표 없이 radius가 있으면
  400이다. 위치 값 범위와 최대 10km는 기존 nearby 계약을 재사용한다.
- 매장명과 `available=true` 메뉴명의 case-insensitive literal substring을 검색한다. `%`, `_`, `\`는
  wildcard가 아니라 literal로 escape한다. 일치 매장은 한 번만 반환하고 match reason은
  `STORE_NAME | MENU_NAME | BOTH`다.
- 순서는 `store-name prefix 우선`, quantized trigram relevance 내림차순, 좌표가 있으면 distance
  micrometer 오름차순, `storeId` 오름차순이다. relevance는
  `floor(greatest store/menu similarity * 1_000_000)` 정수로 cursor에 고정한다. 좌표가 없으면 distance와
  distance sort를 응답에서 생략한다.
- 기본 page size 20, 최대 50이다. signed cursor에는 canonical query hash, 좌표·radius,
  `pickupAvailable` filter와 전체 정렬 tuple을 넣는다. 다음 페이지에서 하나라도 바뀌면 400이다.
- 매장명과 메뉴명에는 `gin(lower(name) gin_trgm_ops)` 인덱스를 사용한다. 메뉴 index는
  `available=true` 조건을 포함한다. 정확한 plan은 PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`로 검증한다.

### 추천 Baseline

홈과 검색 빈 상태의 초기 노출은 **명시적 규칙**이다.

```text
1. 즐겨찾기 매장
2. 최근 주문 매장
3. 거리순 (좌표가 있을 때)
4. 좌표가 없으면 즐겨찾기와 최근 주문 매장까지만
```

- 최근 주문 매장은 BR-40에 따라 현재 상태가 `PAID`, `ACCEPTED`, `PREPARING`, `READY`,
  `COMPLETED`인 customer-owned Order만 사용한다. 매장별 최신 `createdAt`으로 중복 제거하고
  `PENDING_PAYMENT`, `EXPIRED`, `CANCELLED`는 제외한다.
- 단계 사이에서 같은 매장은 한 번만 반환하고 첫 번째 단계의 근거를 유지한다.
- 개인화 모델, 협업 필터링, 학습 기반 랭킹을 도입하지 않는다.
- 규칙은 코드 한 곳에 두고 화면이 재구현하지 않는다.
- 추천 근거를 응답에 표시한다(`FAVORITE`, `RECENT`, `NEARBY`). 사용자가 왜 이 매장이 위에 있는지
  알 수 있어야 한다.

### 도입하지 않는 것

- Elasticsearch. 텍스트 검색 요구가 매장명·메뉴명 부분 일치 수준이고, 대상 행 수가 작다.
  `pg_trgm`으로 목표를 만족하지 못한다고 **측정된 뒤** 재검토한다.
- 검색어 자동완성과 오타 교정.
- ML 기반 추천.

## Alternatives Considered

### 1. 처음부터 Elasticsearch

- 장점: 텍스트 검색 품질과 확장성이 좋다.
- 단점: 색인 동기화, 재색인, 장애 시 fallback 정책이 모두 새 운영 대상이 된다. `AGENTS.md`가
  요구하는 필요성·장애 정책 문서화 기준을 아직 만족하지 못한다. 매장 수가 작을 때는 이득도 작다.

### 2. `LIKE '%keyword%'`

- 장점: 인덱스와 확장 없이 즉시 동작한다.
- 단점: 인덱스를 쓸 수 없어 전체 스캔이 된다. 매장·메뉴가 늘면 선형으로 나빠진다.

### 3. PostgreSQL Full Text Search(`tsvector`)

- 장점: 표준 기능이고 랭킹을 제공한다.
- 단점: 한국어 형태소 분석기가 필요하다. 매장명·메뉴명은 짧은 고유명사가 많아 부분 일치가 더
  중요하고, 이는 `pg_trgm`이 잘 맞는다.

### 4. 추천을 학습 기반으로

- 장점: 장기적으로 전환율이 높을 수 있다.
- 단점: 학습 데이터가 없다. 실제 사용자 트래픽 없이 만든 모델의 성능을 주장할 수 없다.

## Rationale

검색과 추천은 요구가 커진 뒤에 도구를 바꾸기 쉬운 영역이다. 반대로 처음부터 외부 검색 엔진을
도입하면 동기화 실패와 stale 결과라는 실패 모드를 제품 초기에 떠안게 된다.

명시적 규칙 추천은 설명 가능하고 테스트 가능하다. 근거를 응답에 담으면 나중에 규칙을 바꿀 때
비교 기준이 생긴다.

## Consequences

- `pg_trgm` extension과 GIN 인덱스가 추가된다. Flyway migration이 필요하다.
- 즐겨찾기·최근 주문 매장 조회가 새 capability로 추가된다.
- 검색 endpoint가 하나 늘고 Cursor 계약이 하나 늘어난다.
- 텍스트 검색 품질의 상한이 부분 일치 수준으로 제한된다. 이는 의도된 선택이다.

## Verification

- `EXPLAIN ANALYZE`로 `pg_trgm` GIN 인덱스 사용을 확인하고 인덱스 전후를 같은 조건에서 비교한다.
- 좌표 없는 검색이 거리 정렬 없이 정상 동작하고 그 사실이 응답에 표시되는지 검증한다.
- 픽업 가능 필터 결과와 상세 화면의 슬롯 예약 가능 여부가 일치하는지 검증한다.
- 가용성 필터로 빈 페이지와 `nextCursor`가 함께 반환되어도 다음 페이지에 누락·중복이 없는지 검증한다.
- 슬롯 capacity 손상이 pickup 불가 false가 아니라 503인지 검증한다.
- 메뉴명 일치로 검색된 매장이 중복 없이 매장 단위로 집계되는지 검증한다.
- Cursor 다음 페이지가 누락·중복 없이 이어지는지 검증한다.
- query 길이·whitespace·wildcard literal, optional 좌표 쌍과 filter 변경 cursor 400을 검증한다.
- 추천 규칙의 순서가 즐겨찾기·최근·거리 순으로 결정적인지 검증한다.
- 좌표가 없을 때도 즐겨찾기가 사라지지 않고, 제외 상태 주문의 매장이 recent로 노출되지 않는지
  검증한다.

## Metrics

- 매장 검색 p50·p95·p99
- 검색 결과 0건 비율
- 좌표 없는 검색 비율
- 추천 근거별 노출·선택 수
- `pg_trgm` 인덱스 크기와 갱신 비용

## Revisit Conditions

- 매장 수 또는 메뉴 수가 늘어 `pg_trgm` 조회가 목표 지연을 만족하지 못한다고 측정될 때
- 검색어 자동완성·오타 교정이 실제 요구가 될 때
- 실제 사용자 행동 데이터가 쌓여 추천 규칙을 비교 평가할 수 있을 때

## Related Decisions

- [ADR-020](ADR-020-nearby-location-privacy.md)
- [ADR-076](ADR-076-store-catalog-read-contract.md)
- [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)
