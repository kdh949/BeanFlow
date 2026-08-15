# ADR-103: 매장 검색 전략과 추천 Baseline

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Customer store discovery](../exec-plans/active/productization-70-customer-store-discovery.md)

> 2026-08-15: 검색 대상에 브랜드명·지역명이 추가되고 매칭·정렬·응답 계약이 개정됐다.
> 아래 [Amendments](#amendments)가 원 Decision보다 우선한다. 원 Decision 본문은 개정 이전
> 계약을 이해하기 위한 이력으로 남긴다.

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

## Amendments

### 브랜드·지역 검색과 오타 허용 Amendment (2026-08-15)

원 Decision은 검색 대상을 매장명과 판매 중 메뉴명으로 한정하고, 매칭을 literal substring으로,
정렬을 단일 고정 규칙으로 정했다. 제품 요구가 "매장명·브랜드명·메뉴명·지역명을 한 검색창에서"로
확장되면서 다음 여섯 항목을 개정한다. 개정하지 않은 항목(위치 privacy, cursor 서명, Fulfillment
batch 판정, 추천 baseline, Elasticsearch 미도입)은 원 Decision 그대로다.

#### A1. 검색 대상에 브랜드명과 지역명을 추가한다

검색 term은 `STORE_NAME`, `BRAND_NAME`, `REGION_SIDO`, `REGION_SIGUNGU`, `REGION_EUPMYEONDONG`,
`MENU_NAME` 여섯 종류의 폐쇄 어휘다. 브랜드와 지역 데이터 모델은
[ADR-112](ADR-112-store-brand-and-administrative-region.md)가 소유한다.
(아래 A7이 `REGION_RI`를 더해 일곱 종류로 넓힌다.)

검색 대상이 네 곳으로 늘면서 매 요청 4-way 조인 위에 유사도를 계산하는 것이 불가능해졌다.
매장당 검색 가능 문자열을 한 행씩 모은 **동기 갱신 색인 테이블**
`discovery_store_search_term`을 도입한다. 색인은 브랜드·지역 쓰기 커맨드와 같은 트랜잭션에서
갱신하며 배치나 큐로 지연시키지 않는다. 색인 갱신 실패는 원 커맨드를 롤백시킨다.

#### A2. 매칭은 substring 우선 + trigram 유사도 보완의 하이브리드다

원 Decision의 case-insensitive literal substring 매칭을 **1순위로 유지**한다. 어떤 토큰이
substring으로 걸리지 않을 때만 같은 토큰에 `pg_trgm` 유사도 매칭을 추가로 적용한다.
유사도 임계값은 `0.3`이다.

`%`, `_`, `\`를 literal로 escape하는 규칙은 그대로 유지한다. substring 경로의 결과는 개정 전과
동일하므로 기존 계약 테스트가 그대로 유효하고, 유사도 경로는 "원래 0건이던 검색"만 구제한다.

원 Decision의 "검색어 자동완성과 오타 교정을 도입하지 않는다" 중 **오타 교정 부분은 철회한다.**
자동완성은 여전히 도입하지 않는다.

#### A3. 다중 토큰은 AND로 해석한다

원 Decision은 정규화한 검색어 전체를 하나의 substring으로 취급했다. 개정 후에는 공백으로 분리한
최대 5개 토큰 각각이 해당 매장의 term 중 적어도 하나에 매칭돼야 결과에 포함된다.
`"강남 스타벅스"`는 `강남`이 `REGION_SIGUNGU`에, `스타벅스`가 `BRAND_NAME`에 걸린 매장을 반환한다.

지역명을 별도 파라미터로 인식하는 파서는 두지 않는다. 토큰은 모두 동등하게 전체 term 집합에
대해 평가된다.

#### A4. 정렬을 클라이언트가 선택한다

`sort` 파라미터를 추가한다. 기본값은 `relevance`다.

| `sort` | 정렬 튜플 | 좌표 |
|---|---|---|
| `relevance` (기본) | `(relevanceRank, distanceMicrometers, storeId)` | 선택. 없으면 거리 항은 상수 `0` |
| `distance` | `(distanceMicrometers, storeId)` | **필수.** 없으면 400 |

`relevanceRank`는 `1_000_000 - floor(relevance × 1_000_000)`이다. 내림차순 관련도를 오름차순
정렬 하나로 표현해 nearby의 `distanceMicrometers`와 같은 all-ASC keyset 규칙을 쓰기 위함이다.
관련도를 부동소수 그대로 cursor에 넣으면 page 경계에서 누락·중복이 생긴다.

`relevance`는 토큰별 최고 가중 유사도의 평균이며 term 종류별 가중치는
`STORE_NAME 1.00`, `BRAND_NAME 0.90`, `REGION_* 0.80`, `MENU_NAME 0.70`이다. substring 매칭은
유사도 `1.0`으로 취급한다. 원 Decision의 "store-name prefix 우선"은 이 가중치 체계에 흡수한다.

관련도 점수를 응답에 노출하지 않는다. 산식은 공개 계약이 아니며 튜닝 여지를 남긴다.

#### A5. 응답에 매칭된 메뉴 목록을 포함한다

원 Decision의 `matchReason`(`STORE_NAME | MENU_NAME | BOTH`)은 브랜드·지역이 추가되면서
표현력이 부족하다. `matchReason`을 term 종류의 집합(A7 반영 후 일곱 종류)으로 확장하고, 추가로 검색어에 걸린
메뉴를 매장당 최대 3개까지 `matchedMenus`로 내려준다. 정렬은
`(가중 유사도 DESC, 메뉴명 ASC, 메뉴ID ASC)`이며 매칭 메뉴가 없으면 빈 배열이다. 매장이 결과에서
빠지지는 않는다.

결과는 원 Decision대로 **항상 매장 단위**로 집계한다.

#### A6. `openOnly` 필터를 추가한다

기존 `pickupAvailable`(7일 내 실제 예약 가능 슬롯 존재)은 의미를 바꾸지 않고 유지한다.
그보다 약한 `openOnly`를 추가한다. `openOnly=true`는 `acceptingOrders && pickupEnabled`만 요구하고
슬롯 존재는 묻지 않는다. 두 필터는 독립이며 동시에 지정하면 AND다. 기본값은 둘 다 미지정이고,
그때 결과에는 닫힌 매장도 포함된다.

닫힌 매장을 결과에서 지우지 않는 것이 기본값인 이유는, 이름을 알고 검색한 사용자에게 0건을
돌려주는 것이 고장으로 읽히기 때문이다. 상태는 `open`, `pickupAvailable` 플래그로 표시한다.

#### A7. 리 단위 지역명도 검색 대상이다 (2026-08-15 추가)

A1의 term 종류에 `REGION_RI`를 더해 **일곱 종류**가 된다. 가중치는 다른 `REGION_*`과 같은 `0.80`이며
`matchReason` 집합도 일곱 종류로 넓어진다.

법정동 자료의 74%가 리 단위인데 A1 시점의 3계층 어휘로는 리 이름이 검색되지 않았다.
데이터 모델과 근거는 [ADR-112 리 단위 지역 어휘 Amendment](ADR-112-store-brand-and-administrative-region.md)가
소유한다. 매칭·정렬·cursor 규칙은 A2~A6 그대로이며 정렬 튜플이 바뀌지 않으므로
[ADR-070](ADR-070-signed-cursor-and-pagination-contract.md) 등록 내용도 그대로다.

#### 유지하는 상한

페이지 기본 `20`·최대 `50`(공통 `DiscoveryLimit`)과 검색어 `2~50` code point는 개정하지 않는다.
`DiscoveryLimit`은 다른 Discovery endpoint가 공유하는 공통 파라미터이므로 이 endpoint만을 위해
바꾸지 않는다.

#### Amendment의 결과

- `pg_trgm` GIN 인덱스가 매장명·메뉴명 컬럼이 아니라 색인 테이블의 `term_normalized`에 놓인다.
  원 Decision의 `ix_merchant_store_profile_name_trgm`, `ix_merchant_menu_available_name_trgm`는
  색인 테이블 인덱스로 대체된다.
- 색인 테이블이라는 유지 대상이 새로 생긴다. 동기 갱신이므로 stale 결과는 없지만,
  **API 밖에서 바뀌는 데이터**(시드·직접 DML)는 재색인 커맨드로만 반영된다. 이 한계는 숨기지 않고
  커버리지 gauge와 runbook으로 관측 가능하게 만든다.
- cursor scope가 `sort` 값에 따라 둘로 나뉜다. [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)에
  두 정렬 튜플을 등록한다.
- 검색어와 토큰은 원 Decision대로 어디에도 저장하지 않는다. 색인 테이블에 저장되는 것은 매장의
  공개 속성이지 사용자의 검색어가 아니다.

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

### 5. 색인 없이 매 요청 4-way 조인 (2026-08-15 Amendment)

- 장점: 유지할 색인이 없고 항상 최신이다.
- 단점: 오타 허용을 위해 전체 매장·브랜드·지역·메뉴에 `similarity`를 계산해야 해 GIN 인덱스가
  사실상 무력화된다. 데이터 증가에 선형으로 열화한다.

### 6. 색인 테이블을 배치로 주기 갱신 (2026-08-15 Amendment)

- 장점: 쓰기 경로가 단순하고 fan-out 비용이 없다.
- 단점: 매장명·브랜드명 변경이 즉시 반영되지 않아 "오래된 검색 결과"라는 실패 모드가 생긴다.
  `AGENTS.md`의 stale 대체 금지 원칙과 충돌한다.

### 7. 매장당 한 행에 모든 텍스트를 이어붙인 단일 검색 문서 (2026-08-15 Amendment)

- 장점: 행 수가 매장 수와 같아 관리가 쉽다.
- 단점: `similarity`가 문자열 길이에 희석돼 메뉴가 많은 매장의 점수가 부당하게 낮아진다.
  토큰별 term 매칭이 필요하다.

### 8. substring을 버리고 순수 trigram 유사도로 교체 (2026-08-15 Amendment)

- 장점: 매칭 경로가 하나라 구현과 테스트가 단순하다.
- 단점: `"스타"`처럼 짧은 입력이 `"스타벅스 강남역점"` 같은 긴 이름과 유사도가 낮게 나와
  **정확한 부분 검색이 오히려 실패한다.** substring 우선 + 유사도 보완의 하이브리드를 선택한다.

### 9. 한글 오타 구제를 위한 자모 분해 색인 (2026-08-15 Milestone 5, 후속 과제로 등록)

Milestone 5 구현에서 **A2의 유사도 보완이 한글 짧은 상호에는 사실상 발동하지 않는다**는 것이
측정됐다. `스타벅스 강남점`과 오타 `스타박스`의 `similarity`가 임계값 `0.3`에 크게 못 미친다.
한글은 한 글자가 한 문자라 4~8자 이름의 trigram 집합이 작고, 한 글자만 달라져도 겹치는 trigram이
급감하기 때문이다. 라틴 표기 상호(`starbucks` ↔ `starbuks`)에서는 정상 동작한다.

해소책은 `term_normalized`와 질의 토큰을 **자모로 분해한 파생 컬럼**을 하나 더 두고 그쪽에
trigram 인덱스를 거는 것이다. `스타벅스`가 `ㅅㅡㅌㅏㅂㅓㄱㅅㅡ` 10자가 되어 trigram 수가 늘고
한 글자 차이가 부분 차이로 바뀐다.

이번 Amendment에서 채택하지 않는다.

- 색인 스키마와 정규화 계약이 다시 바뀐다. MD-2026-015가 정한 "색인과 질의가 같은 함수"를
  자모 분해까지 확장해야 하고, 분해 방식(호환 자모 vs 조합형, 종성 분리 규칙)이 새 결정이다.
- 임계값을 낮추는 것으로 대신할 수 없다. 무관한 매장을 대량으로 끌어온다.
- 오타 구제는 A2가 "원래 0건이던 검색만 구제한다"고 못박은 보조 경로이고, 지금도 substring
  경로는 한글에서 완전히 동작한다. 즉 **검색이 깨진 것이 아니라 구제가 좁은 것**이다.

이 한계는 숨기지 않는다. 아래 Revisit Condition으로 등록하고
[ExecPlan 70](../exec-plans/active/productization-70-customer-store-discovery.md)의 Non-goals에
명시한다.

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

### Milestone 5 implementation evidence (2026-08-15) — 후보 질의와 두 cursor scope

`StoreSearchCandidateRepositoryIntegrationTest`(18건)와 `StoreSearchQueryIntegrationTest`(11건)이
PostgreSQL 17 + `pg_trgm` 위에서 A2~A7을 측정했다.

- **A3 다중 토큰 AND.** `"강남 스타벅스"`가 지역과 브랜드에 각각 걸린 매장만 반환하고 한 토큰만
  맞은 매장 둘은 제외됐다. 관련도는 `avg(0.80, 0.90) = 0.85`로 rank `150000`이었다.
- **A2 substring 우선.** 토큰이 메뉴명에 substring으로 걸리고 같은 매장의 매장명과는 유사도
  `0.75`(가중 후 `0.75`)로 더 가까운 상황에서, 점수는 메뉴명 가중치 `0.70`이 됐고 `matchReason`도
  `MENU_NAME` 하나였다. 유사도 경로가 "구제"에 한정된다는 것이 결과 값으로 확인된다.
- **A2 임계값의 세션 독립성.** 세션 `pg_trgm.similarity_threshold`를 `0.05`와 `0.9`로 바꿔도
  같은 결과가 나왔다. 질의 안의 명시 비교만으로는 더 엄격한 세션을 막지 못해 transaction 지역
  설정을 함께 쓴다(MD-2026-024).
- **A4 가중치 순서.** 같은 토큰이 매장명·브랜드명·지역명·메뉴명에 각각 걸린 네 매장의 rank가
  `0`, `100000`, `200000`, `300000`으로 나왔다.
- **A4 관련도 동점의 page 순회.** 관련도가 완전히 동점인 다섯 매장을 2건씩 넘겼을 때 누락도
  중복도 없었다. 관련도를 numeric으로만 계산해 양자화하므로 page 경계에서 동점 판정이 흔들리지
  않는다.
- **A5 `matchedMenus`.** 매장당 최대 3개로 잘리고 동점 4개는 메뉴명 오름차순으로 결정됐다.
  매장명으로만 걸린 매장은 빈 배열을 갖고 결과에서 빠지지 않았다.
- **A6 `openOnly`.** 미지정일 때 닫힌 매장이 결과에 남고 `open`·`pickupAvailable` 플래그로
  구분됐다. `openOnly=true`는 그 둘을 제외했다.
- **A7 `REGION_RI`.** 리에 지정된 매장이 읍·면 이름과 리 이름 양쪽으로 검색되고 `matchReason`이
  각각 `REGION_EUPMYEONDONG`, `REGION_RI`였다. 전국에 중복되는 리 이름(`상리`)은 반경 필터가
  갈랐다.
- **cursor.** 정렬·필터·검색어·좌표가 달라진 cursor와 다른 endpoint의 cursor, 만료된 cursor가
  모두 400이다.
- **개인정보.** 검색이 AuditRecord와 도메인 이벤트를 만들지 않는 것을 실제 행 수로 확인했고,
  어떤 metric 태그에도 검색어가 남지 않는 것을 태그 값 전수 검사로 확인했다.

후속 증빙: M6의 픽업 가용성·scan-boundary 계약은 아래 절에, M12의 V59 GIN·V57 favorite·V63
recent 전후 실행계획은 [Customer store discovery query plan evidence](../quality/customer-store-discovery-query-performance-evidence.md)에
기록했다.

### Milestone 6 implementation evidence (2026-08-15) — 픽업 가용성 batch와 scan boundary

`PickupAvailabilityQueryTest`, `NearbyStoreDiscoveryIntegrationTest`,
`NearbyStoreDiscoveryValidationTest`와 `StoreSearchQueryIntegrationTest`가 PostgreSQL 17 위에서
측정했다.

- **batch statement 수.** `PickupAvailabilityQueryOperations.findStoresWithAvailableSlots`가
  후보 1개일 때도 6개(그중 한 매장은 슬롯 61개)일 때도 statement **1개**를 썼다. 같은 매장 id를
  중복해 넘겨도 1개였고, 빈 후보 목록은 DB에 닿지 않아 0개였다. `store_id = ANY(?::uuid[])`와
  `GROUP BY store_id`라 SQL 문자열이 후보 수와 무관하게 고정이다.
- **판정의 경계.** 예약 가능 슬롯이 있는 매장만 가용으로 나왔다. 만석, 이미 시작한 슬롯,
  `startsAt`이 정확히 `now`인 슬롯, 7일 창 밖 슬롯, 슬롯 없음은 모두 불가였다. 하한
  `startsAt > now`는 `PickupSlotQueryOperations.listOpenSlots`가 쓰는 예약 가능 경계와 같다.
- **손상 counter는 503.** `capacity - reserved - confirmed < 0`인 슬롯을 가진 매장이 후보에
  하나라도 있으면 그 매장만 빠지는 것이 아니라 batch 전체가 `DEPENDENCY_UNAVAILABLE`이다.
  나머지 후보가 정상이어서 그럴듯한 page가 나올 수 있는 상황에서도 그렇다. DB `CHECK`가 그 행을
  막고 있어 제약을 잠시 내리고 확인한 뒤 되돌렸다.
- **`pickupAvailable`은 AND.** 슬롯이 있어도 `pickupEnabled=false`인 매장은 `false`, 슬롯이 없는
  매장도 `false`, 둘 다 만족해야 `true`였다. 검색 후보 질의의 필드 이름을 `pickupCapable`로 바꿔
  SQL이 아는 절반임을 드러냈다(MD-2026-027).
- **nearby 의미 통일.** `merchant`의 `NearbyStoreProfileProjection`에서 `pickupAvailable`을
  삭제하고 `discovery`가 batch 결과로 채운다. 개정 전에는 nearby가 `acceptingOrders &&
  pickupEnabled`인 매장만 반환했으므로 이 값이 언제나 `true`였고, 기존 통합 테스트의 단언 하나가
  실제로 뒤집혔다. nearby의 소유자 상태 hard filter 자체는 유지한다(MD-2026-026).
- **scan boundary.** 두 endpoint가 같은 `scanCandidates` 함수를 쓴다. 후보 6개 중 첫·마지막만
  가용한 fixture를 `limit=2`로 넘겼을 때 둘 다 3쪽에 정확히 두 매장을 누락·중복 없이 반환했다.
  가운데 쪽은 빈 배열과 `nextCursor`를 함께 냈다. 가용 매장이 마지막 하나뿐인 fixture에서는
  첫 page가 빈 배열 + cursor, 다음 page가 그 매장이었다. 매 page가 정확히 `limit`개의 후보를
  검사하므로 불가 매장이 길게 이어져도 scan이 전진한다.
- **cursor.** `pickupAvailable`이 두 endpoint의 filter hash에 들어가, 필터를 켜기 전에 발급된
  cursor는 400이다. nearby의 canonical form 개정은 ADR-070 2026-08-15 nearby amendment다.

M12의 V59 GIN·V57 favorite·V63 recent 전후 실행계획은
[Customer store discovery query plan evidence](../quality/customer-store-discovery-query-performance-evidence.md)에
기록했다.

## Metrics

- 매장 검색 p50·p95·p99
- 검색 결과 0건 비율
- 좌표 없는 검색 비율
- 추천 근거별 노출·선택 수
- `pg_trgm` 인덱스 크기와 갱신 비용

## Revisit Conditions

- 매장 수 또는 메뉴 수가 늘어 `pg_trgm` 조회가 목표 지연을 만족하지 못한다고 측정될 때
- 검색어 자동완성·오타 교정이 실제 요구가 될 때
- **한글 상호의 오타 검색이 실제 요구로 확인될 때.** Milestone 5에서 A2의 유사도 보완이 한글
  짧은 이름에는 발동하지 않는 것을 측정했다(위 Alternatives 9). 자모 분해 색인이 해소책이며
  색인 스키마와 정규화 계약 개정을 동반한다. 0건 검색 비율과
  `beanflow.discovery.search.empty`가 이 요구를 드러내는 신호다
- 실제 사용자 행동 데이터가 쌓여 추천 규칙을 비교 평가할 수 있을 때

## Related Decisions

- [ADR-020](ADR-020-nearby-location-privacy.md)
- [ADR-076](ADR-076-store-catalog-read-contract.md)
- [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)
- [ADR-112](ADR-112-store-brand-and-administrative-region.md) — 2026-08-15 Amendment가 소비하는
  브랜드·행정구역 데이터 모델
- [BR-47](../product/business-policy-decisions.md) — 개정된 검색 계약의 제품 정책 수치
