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

고객이 좌표 UUID나 매장 UUID를 직접 입력하지 않고 **매장명·브랜드명·지역명·메뉴명**으로 검색하고,
즐겨찾기·최근 주문·거리 근거를 이해할 수 있는 홈 추천을 받게 한다. 반경 검색의 `pickupAvailable`도
“설정상 가능”이 아니라 실제 예약 가능한 슬롯 존재로 통일한다.

`"강남 스타벅스"`처럼 여러 단어를 넣으면 각 단어가 서로 다른 속성(지역, 브랜드)에 걸려도 같은
매장이 결과가 된다. 오타는 부분 일치가 실패한 토큰에 한해 구제한다.

브랜드와 지역은 현재 BeanFlow에 **존재하지 않는 데이터**다. 따라서 이 plan은 검색 endpoint만
추가하는 것이 아니라 Brand Aggregate와 법정동 어휘, 그 쓰기 경로를 함께 만든다.

## Current State

- `GET /stores/nearby`는 PostGIS 반경 검색을 제공하지만 좌표가 필수다.
- 매장명·메뉴명 검색, 고객 즐겨찾기와 최근 주문 매장 endpoint는 없다.
- 매장 카탈로그와 7일 픽업 슬롯 조회는 ADR-076에 따라 구현돼 있다.
- 검색 결과의 픽업 가능 여부는 Merchant와 Fulfillment 경계를 넘는 batch 판정이 없다.
- `productization-30`은 Session의 `CustomerActor`, `productization-50`은 customer-scoped Order
  Projection 기반을 제공한다.
- **브랜드가 없다.** `merchant_store`, `merchant_store_discovery_profile` 어디에도 브랜드 컬럼이
  없고 Entity·테이블·API도 없다. `merchant` 모듈에서 `brand`가 나오는 유일한 곳은
  `V6`의 `card_brand`이며 무관하다.
- **지역이 없다.** 프로필은 `name`과 `location geography(Point,4326)`만 가진다. 좌표는 있으나
  주소·행정구역이 없어 `"강남구"`로 검색할 근거 데이터가 존재하지 않는다.
- **`merchant`에 쓰기 endpoint가 하나도 없다.** `@PostMapping`/`@PutMapping`/`@PatchMapping`/
  `@DeleteMapping`이 0개이며 매장과 메뉴는 `LocalDemoSeedCli`와 테스트 fixture로만 생성된다.
  따라서 매장명·메뉴명 변경을 가로챌 동기 색인 갱신 지점이 존재하지 않는다. 이 plan은 그 사실을
  숨기지 않고 명시적 재색인 커맨드와 커버리지 gauge로 다룬다.
- `pg_trgm` extension과 한글 텍스트 검색 인프라가 없다. PostGIS는 V33이 이미 설치한다.
- 마지막 Flyway 번호는 `V56`이다. 실제 번호는 ADR-072 lease 획득 후 최신 `main`에서 다시 읽는다.
- ADR-103은 2026-08-15 Amendment로 검색 대상·매칭·정렬·응답 계약이 개정됐고, ADR-112가 브랜드·지역
  데이터 모델을, BR-47이 제품 정책 수치를 확정했다.

## Definitions

- **Search candidate:** 텍스트·위치·매장 상태 조건으로 Merchant가 정렬한 매장이다.
- **Pickup available:** 7일 안에 `startsAt > now`이고 잔여 capacity가 양수인 슬롯이 하나 이상 있는
  `acceptingOrders && pickupEnabled` 매장이다.
- **Recent store:** BR-40 상태 집합의 customer-owned Order를 매장별 최신 `createdAt`으로 줄인 결과다.
- **Recommendation reason:** `FAVORITE | RECENT | NEARBY` 중 병합 순서에서 처음 선택된 근거다.
- **Scan boundary cursor:** 마지막 반환 row가 아니라 마지막으로 픽업 가용성을 검사한 candidate의
  정렬 tuple을 담는 signed cursor다.
- **검색 토큰:** 공백으로 분리하고 정규화한 검색어 조각. `"강남 스타벅스"`는 두 토큰이다.
- **정규화:** 색인과 질의 양쪽에 동일하게 적용하는 결정적 변환. NFKC → 소문자 → 연속 공백 축약 →
  trim. 두 경로가 갈라지면 검색이 조용히 0건이 되므로 단일 함수로 구현한다.
- **검색 term:** `discovery_store_search_term`의 한 행. "이 매장은 이 문자열로 찾을 수 있다"를
  뜻하며 term 종류와 정규화 문자열, 가중치를 가진다.
- **term 종류:** `STORE_NAME`, `BRAND_NAME`, `REGION_SIDO`, `REGION_SIGUNGU`,
  `REGION_EUPMYEONDONG`, `REGION_RI`, `MENU_NAME` 일곱 개의 폐쇄 어휘다.
- **관련도:** 토큰별 최고 가중 유사도의 평균. substring 매칭은 유사도 `1.0`으로 취급한다.
- **관련도 rank:** `1_000_000 - floor(relevance × 1_000_000)`. 내림차순 관련도를 오름차순 정렬
  하나로 표현해 nearby와 같은 all-ASC keyset 규칙을 쓰기 위한 canonical cursor 값이다.
- **Brand:** 여러 매장이 공유하는 상호 정체성. `merchant`가 소유하는 Aggregate Root이며 운영자만
  생성·수정한다. 매장은 브랜드를 ID로 참조한다.
- **Region:** 행정안전부 법정동 코드 10자리로 식별하는 폐쇄 어휘 항목. 매장주는 신규 생성 없이
  기존 코드를 선택만 한다. 시도·시군구·읍면동·리 4계층이며 리 행도 상위 읍·면 이름을 함께 갖는다.
- **동기 색인 갱신:** 브랜드·지역 커맨드가 자신의 transaction 안에서 검색 term을 함께 갱신하는 것.
  큐·배치·지연 갱신이 아니다.
- **재색인:** API 밖에서 바뀐 매장·메뉴 데이터를 색인에 반영하는 명시적 운영자 커맨드다.

## Scope

### In Scope

- `GET /stores/search`의 매장명·브랜드명·지역명·판매 중 메뉴명 통합 검색
  (substring 우선 + `pg_trgm` 유사도 보완, 토큰 AND, `sort=relevance|distance`)
- `merchant_brand` Aggregate와 `merchant_store.brand_id`
- `merchant_region` 법정동 어휘와 시드, `merchant_store_discovery_profile.region_code`
  (백필 뒤 NOT NULL 승격)
- `discovery_store_search_term` 동기 갱신 색인과 `pg_trgm` GIN 인덱스
- 운영자 브랜드 CRUD·매장 브랜드 지정과 매장주 지역 지정, 모두 AuditRecord 포함
- 운영자 재색인 커맨드와 색인 커버리지 관측
- 기존 `GET /stores/nearby`와 검색의 동일한 `pickupAvailable` batch 판정
- `GET /me/favorite-stores`, `PUT/DELETE /me/favorite-stores/{storeId}`
- `GET /me/recent-stores`
- `GET /me/store-recommendations`의 즐겨찾기 → 최근 → nearby 병합
- `discovery_customer_favorite_store`
- signed cursor, 실행계획, 개인정보 비보존과 장애 계약
- 목표·runtime OpenAPI 계약과 `npm run generate:api`가 만드는 프론트엔드 타입

### Non-goals

- **프론트엔드 컴포넌트·화면·디자인.** 계약과 생성 타입까지만 만든다. 검색 UI, 정렬 토글,
  지역 선택기, Storybook 도입은 별도 작업이다.
- **매장·메뉴 쓰기 API.** `merchant`에 현재 없고 이 plan도 추가하지 않는다. 매장명·메뉴명 변경은
  재색인 커맨드로만 색인에 반영된다.
- **저장된 주소지의 서버 보관.** 설정 주소지는 client storage에만 둔다. 계정 스키마에 주소·좌표
  컬럼을 추가하지 않고 공개 API 계약도 바꾸지 않는다(MD-2026-017, ADR-020 2026-08-15 평가).
- **메뉴 단위 결과(가격 비교형 목록).** 결과 단위는 매장이며 매칭 메뉴는 매장 카드에 최대 3개
  포함된다. 메뉴가 결과 행이 되는 목록은 커서 튜플과 중복 매장 처리가 달라 별도 결정이 필요하다.
- 주소 geocoding과 역지오코딩, 검색어 자동완성
- 상권 별칭 사전(`홍대`, `가로수길`)
- 브랜드 단위 정산·브랜드 페이지·브랜드 소유자 계정
- Elasticsearch·별도 검색 cluster
- ML ranking, 협업 필터링과 추천 점수 학습
- 검색어 저장·인기 검색어·개인화
- 즐겨찾기 폴더·메모·수동 정렬
- 서버 장바구니

## Business Rules and Invariants

전체 정책은 [BR-47](../../product/business-policy-decisions.md)과 BR-40이며 아래는 구현 불변식이다.

1. 검색 query는 trim·연속 whitespace 축약 뒤 2~50 Unicode code point이고 토큰은 최대 5개다.
2. latitude와 longitude는 둘 다 제공하거나 둘 다 생략한다. radius만 제공하면 400이다.
   `sort=distance`는 좌표가 필수이며 좌표 없이 요청하면 400이다.
3. `%`, `_`, `\`는 wildcard가 아닌 literal이다. 일치 결과는 매장 단위로 한 번만 반환한다.
4. 토큰별로 substring을 먼저 적용하고 걸리지 않은 토큰에만 유사도 `0.3` 이상 매칭을 추가한다.
   **모든** 토큰이 해당 매장의 term 중 적어도 하나에 매칭돼야 결과에 포함된다(AND).
5. term 가중치는 `STORE_NAME 1.00`, `BRAND_NAME 0.90`, `REGION_* 0.80`, `MENU_NAME 0.70`이고
   substring 매칭은 유사도 `1.0`이다. `REGION_RI`도 다른 `REGION_*`과 같은 `0.80`이다.
   관련도 점수를 응답에 노출하지 않는다.
6. `pickupAvailable=true`는 ADR-103의 실제 슬롯 존재 판정을 사용한다. 검색과 nearby가 다른 의미를
   쓰지 않는다. `openOnly=true`는 `acceptingOrders && pickupEnabled`만 요구하고 둘은 독립이다.
   둘 다 미지정이 기본이며 그때 닫힌 매장도 결과에 포함하고 상태를 플래그로 표시한다.
7. `matchedMenus`는 매장당 최대 3개이고 `(가중 유사도 DESC, 메뉴명 ASC, 메뉴ID ASC)` 순이다.
   매칭 메뉴가 없으면 빈 배열이며 매장이 결과에서 빠지지 않는다.
8. 모든 매장 프로필은 유효한 `region_code`를 가진다(DB `NOT NULL`). `brand_id`는 nullable이다.
   리 행에 지정된 매장은 `REGION_*` term을 4행(시도·시군구·읍면동·리), 리가 없으면 3행 갖는다.
   빈 문자열 계층으로는 term을 만들지 않는다.
9. 활성 브랜드의 정규화 이름은 유일하다. 중복 등록은 409다.
10. 브랜드 생성·수정과 매장 브랜드 지정은 `PLATFORM_OPERATOR`만, 매장 지역 지정은 해당 매장의
    `STORE_OWNER`만 수행한다. `STORE_STAFF`는 지역을 바꿀 수 없다. 모두 AuditRecord를 남긴다.
11. 색인 갱신은 원 커맨드와 **같은 transaction**이다. 갱신 실패는 커맨드 전체를 rollback한다.
    데이터만 반영되고 색인이 누락된 상태를 만들지 않는다.
12. 브랜드명 변경의 색인 fan-out은 소속 매장 1000개를 상한으로 하고 초과는 409다. 비동기 큐로
    우회하지 않는다.
13. 정규화는 색인 기록과 질의가 **같은 함수**를 사용한다.
14. favorite와 recent의 customer ID는 `CustomerActor`에서 얻고 body/query로 받지 않는다.
15. favorite row는 고객·매장 쌍에 하나다. 같은 PUT과 없는 row DELETE는 부작용 없이 재실행 가능하다.
16. recent 상태·정렬·추천 병합은 BR-40을 따른다.
17. 검색어, 토큰과 정밀 좌표를 DB, application log, metric tag, trace 또는 이벤트에 저장하지 않는다.
    색인 테이블에 저장하는 것은 매장의 공개 속성이며 사용자의 검색어가 아니다. 검색은 AuditRecord와
    도메인 이벤트를 만들지 않는다.
18. 다른 Context Aggregate는 ID와 public port로만 참조하고 JPA 객체 연관관계를 만들지 않는다.

## Architecture and Transaction Boundaries

### 모듈 소유권과 순환 의존 회피

| 데이터 | 소유 모듈 |
|---|---|
| `merchant_brand`, `merchant_store.brand_id`, `merchant_region`, `region_code` | `merchant` |
| `discovery_store_search_term`, `discovery_customer_favorite_store` | `discovery` |

`merchant`의 브랜드·지역 커맨드는 색인을 갱신해야 하고 `discovery`의 검색은 매장 상태를 읽어야
한다. 두 방향을 그대로 두면 Spring Modulith 구조 검증이 순환 의존으로 깨진다. ADR-112대로
**색인 갱신 port를 `shared/api`에 선언**하고 `discovery/internal`이 구현한다. `merchant`는
`shared`에만 의존하고 `discovery`를 모른다.

```kotlin
// shared/api
interface StoreSearchIndexOperations {
    fun replaceStoreTerms(command: ReplaceStoreSearchTermsCommand)
    fun replaceBrandTerms(command: ReplaceBrandSearchTermsCommand)
}
```

### Transaction 경계

- **T1 브랜드 생성·수정** (운영자): `merchant_brand` 쓰기 + 소속 매장 `BRAND_NAME` term 교체 +
  AuditRecord를 한 transaction에 commit. fan-out 상한 1000 초과는 409.
- **T2 매장 브랜드 지정·해제** (운영자): `brand_id` 갱신 + 해당 매장 `BRAND_NAME` term 교체 +
  AuditRecord.
- **T3 매장 지역 지정** (매장주): `region_code` 갱신 + `REGION_*` term 3행 교체 + AuditRecord.
- **T4 재색인** (운영자): 매장 단위 transaction의 반복. 중간 실패 시 처리 매장 수와 실패 매장 ID를
  동기 응답에 남기고 부분 실패를 성공으로 보고하지 않는다.
- **T5 검색·추천** (고객): read-only transaction. 외부 호출 없음.

### 조회 흐름

```text
GET /stores/search or /stores/nearby
  SearchController
    MerchantStoreSearchQuery.findCandidates(filters, signedCursor, limit + 1)
    FulfillmentPickupAvailabilityQuery.existsByStoreIds(candidateStoreIds, now)
    ordered candidates + availability → page + scan-boundary nextCursor
    DiscoveryMatchedMenuQuery.topByStoreIds(pageStoreIds, tokens, 3)

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
- 검색 candidate query는 색인 테이블만 읽고 매장·브랜드·지역·메뉴 테이블을 4-way 조인하지 않는다.
  토큰 배열을 `unnest(?::text[]) WITH ORDINALITY`로 전개해 토큰별 최고 가중 점수를 구하고,
  `HAVING COUNT(*) = <토큰 수>`로 AND 의미론을 표현한다.
- `term_normalized % token`이 GIN trigram 인덱스를 타고 `similarity(...) >= 0.3`이 재검증한다.
  `%` 연산자의 임계값은 세션 GUC `pg_trgm.similarity_threshold`에 의존하므로 **세션 설정에
  의존하지 않도록 쿼리에서 임계값을 명시 비교**한다.
- `matchedMenus`는 확정된 page의 매장 ID 배열(최대 50)로 2차 조회하며
  `ROW_NUMBER() OVER (PARTITION BY store_id ORDER BY ...)`로 매장당 3개를 자른다.
- 색인 갱신은 해당 매장·term 종류의 기존 행을 지우고 다시 넣는 replace 방식이다. 부분 갱신으로
  고아 term이 남지 않게 한다.

## Alternatives Considered

- `LIKE '%query%'`: 데이터 증가 시 순차 scan이므로 기각한다.
- Elasticsearch: 색인 동기화와 별도 장애 모델이 P0 요구보다 크므로 기각한다.
- 검색 SQL에서 Fulfillment table 직접 join: Context 소유권과 query 변경 결합을 만들므로 기각한다.
- recent store 복제 table: Order 상태 변경과 동기화 실패가 새 source of truth를 만들므로 기각한다.
- 좌표 없을 때 nearby fallback: 의미 없는 위치를 추정하게 되므로 기각한다.
- 색인 없이 매 요청 매장·브랜드·지역·메뉴 4-way 조인 + 유사도: GIN 인덱스가 무력화되어 기각한다.
- 색인 테이블 배치 갱신: 이름 변경이 즉시 반영되지 않아 stale 결과를 만들므로 기각한다.
- 매장당 한 행에 모든 텍스트를 이어붙인 단일 문서: `similarity`가 길이에 희석돼 메뉴가 많은 매장이
  부당하게 낮은 점수를 받으므로 기각한다.
- substring을 버리고 순수 유사도로 교체: `"스타"` 같은 짧은 입력이 긴 매장명과 유사도가 낮아
  정확한 부분 검색이 오히려 실패하므로 기각한다. 하이브리드를 선택한다.
- 브랜드를 프로필 텍스트 컬럼으로: 중복 브랜드를 막을 수 없고 이름 변경 비용이 매장 수에
  비례하므로 기각한다(ADR-112).
- 지역 자유 텍스트와 역지오코딩: 각각 표기 흔들림과 외부 의존 실패 모델 때문에 기각한다(ADR-112).

## Failure Semantics

- 잘못된 query·토큰 수 초과·좌표 쌍·radius·cursor·limit·`sort`는 400이다. 오류 메시지에 검색어
  원문, 좌표, cursor를 넣지 않는다.
- `sort=distance`인데 좌표가 없으면 400이다.
- favorite target Store가 없거나 공개 탐색 대상이 아니면 404다. 다른 고객의 favorite 개념은 노출하지 않는다.
- Fulfillment counter가 음수이거나 capacity보다 사용량이 크면 pickup 불가가 아니라 503이다.
- Merchant, Fulfillment, Ordering 또는 favorite 저장소 장애는 503이다. 빈 검색·추천으로 대체하지 않는다.
- `pg_trgm` extension 부재와 색인 테이블 접근 실패는 503이다. 빈 목록이나 순차 검색으로 대체하지
  않는다. extension 생성 권한이 없으면 migration을 실패시킨다.
- 결과 0건은 정상 200이며 장애와 구분한다.
- 존재하지 않는 매장·브랜드·법정동 코드는 404다.
- 활성 브랜드 정규화 이름 중복은 `409 BRAND_NAME_CONFLICT`다.
- 브랜드 소속 매장 1000개 초과 상태의 이름 변경은 `409 BRAND_FANOUT_LIMIT_EXCEEDED`이며 부분 갱신을
  남기지 않는다.
- 색인 갱신 실패는 원 커맨드 transaction 전체를 rollback한다. 부분 성공이 없다.
- 재색인 중 일부 매장이 실패하면 성공 매장 수와 실패 매장 ID를 응답에 포함하고 부분 실패를 성공으로
  보고하지 않는다.
- 동시 PUT은 한 row로 수렴한다. Unique 위반을 500으로 노출하거나 중복 row를 허용하지 않는다.

**색인 신선도의 한계를 숨기지 않는다.** `merchant`에 매장·메뉴 쓰기 API가 없으므로 시드나 직접
DML로 매장명·메뉴가 바뀌면 색인이 자동으로 따라가지 않는다. 이 상태를 감지 가능하게 만들기 위해
색인 커버리지 gauge와 term 수 대비 매장·메뉴 행 수 점검 쿼리를 runbook에 남긴다. 매장·메뉴 쓰기
API가 생기는 시점에 해당 커맨드가 색인 갱신을 흡수하는 것이 정식 해소책이다.

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
```

마지막 Flyway 번호는 `V56`이었다. 2026-08-15 ADR-072 lease를 획득하고 최신 `main`에서 다시 읽어
확정했다. 다른 `Writes-Migration=true` plan은 `productization-100`(`Implementation-Ready=false`)과
`analytics-refund-and-late-event-projection`(schema milestone 미착수)뿐이고 미병합 schema branch가
없어 lease가 비어 있었다. 단계 1은 **V57**(스키마)과 **V58**(법정동 시드)을 쓴다.

### 단계 1 — 어휘·브랜드 스키마와 시드

```sql
CREATE TABLE merchant_region (
    code varchar(10) PRIMARY KEY,
    sido varchar(40) NOT NULL CHECK (length(trim(sido)) > 0),
    sigungu varchar(40) NOT NULL DEFAULT '',
    eupmyeondong varchar(40) NOT NULL DEFAULT '',
    ri varchar(40) NOT NULL DEFAULT '',
    full_name varchar(120) NOT NULL CHECK (length(trim(full_name)) > 0)
);

CREATE TABLE merchant_brand (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (length(trim(name)) > 0),
    normalized_name varchar(120) NOT NULL CHECK (length(trim(normalized_name)) > 0),
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_merchant_brand_active_normalized_name
    ON merchant_brand (normalized_name) WHERE status = 'ACTIVE';

ALTER TABLE merchant_store ADD COLUMN brand_id uuid REFERENCES merchant_brand(id);
CREATE INDEX ix_merchant_store_brand
    ON merchant_store (brand_id) WHERE brand_id IS NOT NULL;

ALTER TABLE merchant_store_discovery_profile
    ADD COLUMN region_code varchar(10) REFERENCES merchant_region(code);
```

#### V57·V58 개정 (2026-08-15)

리 단위 검색을 위해 `merchant_region`에 `ri` 열이 추가된다(ADR-112 리 Amendment). V57·V58은
**이미 이 branch에 있지만 merge되지 않았고 어떤 환경에도 적용되지 않았으므로** 새 migration을
덧붙이지 않고 두 파일을 그 자리에서 고친다. ADR-072가 금지하는 것은 **적용·배포된** migration의
재번호화와 checksum 수선이며, 미병합 Draft stack의 미적용 migration 정리는 2026-08-12 선례대로
history를 다시 쓰지 않는 additive commit으로 처리한다. 실제 DB는 테스트 컨테이너뿐이라 매번
`clean` 후 재적용된다.

`merchant_region` 시드는 폐지되지 않은 법정동 약 2만 행이다. MD-2026-016대로
`scripts/generate-region-seed.py`가 원본을 결정적으로 변환하고 시드 SQL만 커밋한다. 시드는 정렬된
`INSERT ... ON CONFLICT DO NOTHING`이라 재실행 가능하다.

### 단계 2 — 검색 색인 테이블과 초기 적재

**V59로 구현했다(2026-08-15).** 아래는 실제 적용된 DDL이며 초안에서 두 가지가 바뀌었다.
`term_normalized`는 `varchar(120)` → `varchar(400)`이고, 백필은 이 migration에 없다.

```sql
CREATE TABLE discovery_store_search_term (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES merchant_store(id) ON DELETE CASCADE,
    term_kind varchar(24) NOT NULL CHECK (term_kind IN (
        'STORE_NAME', 'BRAND_NAME', 'REGION_SIDO', 'REGION_SIGUNGU',
        'REGION_EUPMYEONDONG', 'REGION_RI', 'MENU_NAME'
    )),
    source_id uuid,
    term_normalized varchar(400) NOT NULL CHECK (length(trim(term_normalized)) > 0),
    display_text varchar(200) NOT NULL,
    weight numeric(3,2) NOT NULL CHECK (weight > 0 AND weight <= 1),
    CONSTRAINT ck_search_term_menu_source
        CHECK ((term_kind = 'MENU_NAME') = (source_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_search_term_identity
    ON discovery_store_search_term (
        store_id, term_kind,
        COALESCE(source_id, '00000000-0000-0000-0000-000000000000'::uuid),
        term_normalized
    );

CREATE INDEX ix_search_term_trgm
    ON discovery_store_search_term USING gin (term_normalized gin_trgm_ops);

CREATE INDEX ix_search_term_store_kind
    ON discovery_store_search_term (store_id, term_kind);
```

#### 길이 상한

`display_text`는 원본 그대로라 가장 긴 원본인 매장명·메뉴명의 `varchar(200)`을 따른다. 초안의
`varchar(120)`은 브랜드명·법정동명 상한이라 200자 매장명을 담지 못한다. `term_normalized`는 NFKC
결과라 원본보다 길어질 수 있어(`U+FDFD` 한 글자가 18자가 된다) 원본 상한의 두 배를 준다. 상한을
넘는 이름은 잘라 담지 않고 색인 쓰기를 명시적으로 실패시킨다. 잘라 담으면 뒷부분으로는 검색되지
않는 매장이 조용히 생긴다.

#### 초기 적재를 migration이 하지 않는 이유

기존 매장의 `STORE_NAME`·`MENU_NAME` term은 **migration SQL이 아니라 애플리케이션 재색인**이
채운다(MD-2026-018). 구현 불변식 13이 색인과 질의의 정규화를 한 함수로 못박았는데 SQL 백필은 그
함수를 부를 수 없어 `normalize(NFKC) + lower()`로 다시 구현해야 하고, 실제로 비교해 보니 두 구현이
갈렸다. 측정 결과는 Surprises에 있다.

`BRAND_NAME`은 브랜드가 아직 없어 비어 있고 `REGION_*`은 단계 3의 백필 이후 재색인으로 채운다.

`source_id`가 nullable이므로 PK에 넣을 수 없다. 대리 키 `id`와 `COALESCE` 식 unique 인덱스로
정체성을 보장한다.

ADR-103 원 Decision의 `ix_merchant_store_profile_name_trgm`,
`ix_merchant_menu_available_name_trgm`는 만들지 않는다. 2026-08-15 Amendment로 trigram 인덱스가
색인 테이블로 이동했다.

### 단계 3 — 지역 커버리지 gate

V33 → V34 선례를 따른다. 컬럼 생성(단계 1) → 운영자 값 입력 → fail-closed 검증 순이다.

```sql
ALTER TABLE merchant_store_discovery_profile
    ALTER COLUMN region_code SET NOT NULL;
```

`region_code`가 비어 있는 매장이 하나라도 있으면 이 migration은 실패하며 그것이 의도다. 지역이
없는 매장은 지역명 검색에서 조용히 사라진다.

브랜드에는 커버리지 gate를 두지 않는다. 브랜드 없음이 정상 상태다.

### 공통

- 실제 table/column 이름은 migration writer lease 획득 후 최신 schema와 대조한다.
- 세 단계는 하나의 lease를 공유한다. 단계 사이에 다른 schema writer를 시작하지 않는다.
- 다른 Context Aggregate와 JPA cascade를 만들지 않는다. Store·Customer 삭제 정책은 별도 lifecycle이
  생길 때 결정하며, P0에서는 목록 hydrate가 비노출 row를 안전하게 제외한다.
- extension 생성 권한이 없으면 migration을 실패시키고 순차 검색으로 fallback하지 않는다.
- 되돌리기는 새 테이블·컬럼 드롭으로 충분하다. 기존 데이터를 변형하지 않으므로 nearby 경로는
  영향받지 않는다.

## API and Event Contracts

```http
GET    /api/v1/stores/search?query=&sort=&latitude=&longitude=&radiusMeters=&pickupAvailable=&openOnly=&cursor=&limit=
GET    /api/v1/stores/nearby?latitude=&longitude=&radiusMeters=&pickupAvailable=&cursor=&limit=
GET    /api/v1/me/favorite-stores
PUT    /api/v1/me/favorite-stores/{storeId}
DELETE /api/v1/me/favorite-stores/{storeId}
GET    /api/v1/me/recent-stores?limit=
GET    /api/v1/me/store-recommendations?latitude=&longitude=&radiusMeters=&limit=

GET    /api/v1/regions?query=&cursor=&limit=
PUT    /api/v1/stores/{storeId}/region

POST   /api/v1/operations/brands
GET    /api/v1/operations/brands?cursor=&limit=
GET    /api/v1/operations/brands/{brandId}
PATCH  /api/v1/operations/brands/{brandId}
PUT    /api/v1/operations/stores/{storeId}/brand
DELETE /api/v1/operations/stores/{storeId}/brand
POST   /api/v1/operations/search-index/rebuild
```

- search 기본 page size는 20, 최대 50이다. recent/recommendations 기본 10, 최대 20이다.
  기존 공통 `DiscoveryLimit`을 유지하고 이 endpoint를 위해 상한을 바꾸지 않는다.
- `sort`는 `relevance`(기본) 또는 `distance`이며 `distance`는 좌표가 필수다.
- search cursor는 ADR-070의 2026-08-15 amendment를 따른다. endpoint identifier가 `sort`에 따라
  `stores-search-relevance`/`stores-search-distance`로 갈리고, filter hash에 정규화 토큰 배열,
  `sort`, `pickupAvailable`, `openOnly`, 좌표·radius의 canonical form을 넣는다. raw 검색어와 raw
  좌표 text는 payload에 넣지 않는다.
- search item은 `storeId`, 표시명, 브랜드(있을 때), 지역, term 종류 집합의 match reason, 좌표가
  있을 때만 distance, `open`, `pickupAvailable`과 매장당 최대 3개의 `matchedMenus`를 반환한다.
  관련도 점수는 노출하지 않는다. 내부 profile 식별자는 UI 입력으로 요구하지 않는다.
- recommendation item은 매장 표시 정보와 `FAVORITE | RECENT | NEARBY` reason을 반환한다.
- 브랜드 목록 cursor 정렬은 `(normalizedName ASC, brandId ASC)`, 법정동 목록은
  `(fullName ASC, code ASC)`다.
- 브랜드·지역·재색인 명령은 `Idempotency-Key`와 1~200자 reason을 요구하고 AuditRecord를 남긴다.
- 재색인은 동기 응답으로 성공 매장 수와 실패 매장 ID를 반환한다. `202`를 성공으로 쓰지 않는다.
- 이벤트 계약은 변경하지 않는다.

### 매장을 가로지르는 메뉴 검색

색인 테이블은 매장 경계로 분할되지 않으므로 `MENU_NAME` term 조회는 **전 매장의 메뉴**를 한 번에
대상으로 한다. 매장을 순회하며 각각 조회하지 않는다. 반경 필터는 색인 매칭으로 좁혀진 후보에
`ST_DWithin(profile.location, 요청 좌표, radiusMeters)`를 적용하는 순서다.

```text
1) discovery_store_search_term 토큰 매칭   → 매장 후보 (매장 무관, GIN trigram)
2) merchant_store_discovery_profile 조인   → ST_DWithin 반경 필터 (GiST)
3) openOnly/pickupAvailable 필터와 정렬    → page + scan-boundary cursor
4) 확정 page의 매장 ID로 matchedMenus 2차 조회 (매장당 3개)
```

기존 `GET /stores/{storeId}/menus`(ADR-076)는 한 매장의 전체 카탈로그 조회이고 이 경로는 매장을
가로지르는 검색이다. 목적과 계약이 다르며 서로를 대체하지 않는다.

### 프론트엔드 계약

- **설정 주소지는 client storage에만 둔다.** 서버는 좌표를 저장하지 않으므로 화면이 주소지를
  보관하고 매 요청 `latitude`/`longitude`/`radiusMeters`로 전송한다. 주소지가 기기·브라우저에
  묶이고 storage를 지우면 사라지는 것은 알려진 한계이며 기능 결함으로 숨기지 않는다.
- `openapi/beanflow-v1.yaml`의 기존 `/stores/search`를 개정하고 새 endpoint 스키마를 추가한다.
- 컨트롤러와 계약·보안·실패 테스트가 존재하는 시점에 runtime spec에 반영하며
  `RuntimeOpenApiParityTest`가 양방향 검증한다.
- `frontend`는 `npm run generate:api`가 `src/api/schema.d.ts`에 타입을 생성하는 것까지가 이 plan의
  범위다. 컴포넌트·라우트·상태 관리·스타일은 만들지 않는다.

## Milestones

1. migration writer lease와 최신 schema 확인. `SearchTextNormalizer`(shared)와 법정동 시드 생성
   스크립트 작성, 단계 1 migration(`pg_trgm`·region·brand·favorite) 작성.
   **완료 조건:** Testcontainers에서 시드 행 수와 대표 코드(역삼동 `1168010100`)가 조회되고,
   정규화 함수의 NFKC·대소문자·공백 단위 테스트가 통과한다.
1-B. **리 단위 지역 어휘 (2026-08-15 추가).** ADR-112 리 Amendment에 맞춰 V57에 `ri` 열을 넣고
   시드 생성 스크립트가 리 이름을 뽑도록 고친 뒤 V58을 재생성한다.
   **완료 조건:** 리 행(`강원특별자치도 춘천시 동면 감정리`)의 `ri`가 `감정리`이고 같은 행의
   `eupmyeondong`이 `동면`으로 남아 있으며, 리가 없는 지역의 `ri`가 빈 문자열이다. 시드 행 수는
   `20560`으로 변하지 않는다.
2. 단계 2 migration(색인 테이블)과 `shared/api` 색인 갱신 port, `discovery/internal` 구현,
   기존 매장·메뉴 초기 적재. 색인 term 종류에 `REGION_RI`를 포함한다.
   **완료 조건:** 재색인을 실행한 뒤 모든 매장이 `STORE_NAME` term 1행을 갖고 커버리지 gauge가
   `1.0`을 보고한다. 실행 전에는 `0`을 보고해야 하며 그 상태가 관측 가능해야 한다.
3. 운영자 브랜드 CRUD·매장 브랜드 지정과 `BRAND_NAME` term 동기 갱신, fan-out 상한, AuditRecord.
   **완료 조건:** 브랜드명 변경이 소속 매장 term을 같은 transaction에서 갱신하고, 색인 갱신을 강제
   실패시키면 브랜드 변경도 rollback된다.
4. 매장주 지역 지정과 `GET /regions`, `REGION_*` term 동기 갱신, 단계 3 커버리지 gate.
   **완료 조건:** 지역이 빈 매장이 남아 있으면 커버리지 migration이 실패한다. `STORE_STAFF`와 타
   매장 소유자의 변경은 403이다.
5. Merchant search candidate Query와 exact 정렬·signed cursor 두 scope 구현,
   `matchedMenus` 2차 조회.
   **완료 조건:** 다중 토큰 AND, substring 우선 + 유사도 보완, 관련도 동점 page 순회가 검증된다.
6. Fulfillment pickup availability batch port와 nearby 의미 통일, `openOnly` 필터.
7. favorite command/query와 customer ownership 계약 구현.
8. Ordering recent-store Query port와 BR-40 상태·정렬 구현.
9. recommendation 병합 endpoint 구현.
10. 운영자 재색인 커맨드와 커버리지 gauge, `docs/operations/store-keyword-search-runbook.md`.
11. target/runtime OpenAPI, Error Catalog와 계약 테스트 갱신,
    `npm run generate:api && npx tsc --noEmit`.
12. 동일 fixture의 `EXPLAIN (ANALYZE, BUFFERS)` 전후 evidence 작성.

## Required Tests

- query 길이·whitespace·literal wildcard와 case-insensitive 매장/메뉴 일치.
- 토큰 5개 초과 400, 정규화 함수의 NFKC 합성/분해·전각/반각·연속 공백·양끝 공백.
- 매장명·브랜드명·지역명·메뉴명 각각의 단일 토큰 검색과 매장 단위 dedupe.
- `"강남 스타벅스"` 다중 토큰 AND: 지역과 브랜드가 서로 다른 term에 걸린 매장만 반환하고 한
  토큰만 맞는 매장은 제외된다.
- substring 경로가 개정 전과 동일한 결과를 내고 유사도 경로는 0건이던 검색만 구제하는지.
- 유사도 임계값 `0.3` 경계의 매칭·비매칭 각각.
- 세션 `pg_trgm.similarity_threshold`를 바꿔도 결과가 동일한지(쿼리 내 명시 비교 검증).
- term 가중치 순서: 동일 유사도에서 매장명 매칭이 메뉴명 매칭보다 상위.
- `sort=relevance`/`sort=distance` 각각의 정렬과 `sort=distance` 좌표 누락 400.
- 관련도 rank 양자화가 `0..1_000_000` 밖으로 나가지 않고 단조인지.
- 관련도 동점 매장이 여러 개인 상황을 포함한 cursor page 순회의 누락·중복 부재.
- 다른 `sort`·`openOnly`·`pickupAvailable`·좌표로 재사용한 cursor의 400과 만료 cursor 400.
- `pickupAvailable`·`openOnly` 단독·동시 지정의 결과 차이와 미지정 시 닫힌 매장 포함.
- `matchedMenus` 최대 3개, 동점 4개 이상일 때 정렬 결정성, 매칭 없는 매장의 빈 배열.
- 결과 0건이 200 빈 배열이고 503과 구분되는지.
- 브랜드 정규화 이름 동시 등록의 단일 성공과 409.
- 브랜드명 변경의 색인 fan-out 원자성, 1000개 초과 409, 부분 갱신 부재.
- 브랜드명 변경과 같은 브랜드 매장의 브랜드 해제 동시 실행에서 term 중복·유실 부재.
- 색인 갱신 강제 실패 시 브랜드·지역 변경 rollback.
- 같은 매장에 대한 동시 지역 변경의 최종 상태와 term 일치.
- 지역 미입력 매장이 남아 있을 때 커버리지 migration 실패.
- `sigungu`가 빈 문자열인 행정구역(세종시)의 정상 저장·검색.
- 리 행의 `ri`가 리 이름이고 `eupmyeondong`이 상위 읍·면 이름을 유지하는지.
- 리에 지정된 매장이 읍·면 이름과 리 이름 **양쪽**으로 검색되고 `matchReason`이 각각
  `REGION_EUPMYEONDONG`, `REGION_RI`인지.
- 리가 없는 지역에 `REGION_RI` term이 만들어지지 않고 `REGION_*` term이 3행인지.
- 전국에 중복되는 리 이름(`상리`)이 반경 밖에서는 결과에 섞이지 않는지.
- 법정동 시드 재실행 시 행 수 동일.
- `STORE_STAFF`·타 매장 소유자의 지역 변경 403, `STORE_OWNER`의 브랜드 생성 403,
  비운영자 재색인 403, 미인증 검색 401.
- `pg_trgm` 부재·색인 조회 실패의 503과 빈 목록 미대체.
- 재색인 부분 실패가 성공으로 보고되지 않고 실패 매장 ID가 응답에 포함되는지.
- 검색 profile이 없는 매장이 재색인에서 실패로 기록되고 나머지 매장 처리가 계속되는지.
- 매장명을 직접 DML로 바꾼 뒤 재색인 전에는 결과가 따라오지 않고 재색인 후에는 따라오는지.
- 재색인 재실행이 term 행을 늘리지 않는지(종류 단위 교체의 멱등성).
- 색인 쓰기가 커맨드 transaction 밖에서 호출되면 새 transaction을 열지 않고 거부되는지.
- 정규화 후 빈 문자열이 되는 term이 조용히 건너뛰어지지 않고 400으로 거부되는지.
- 판매 중이 아닌 메뉴에 `MENU_NAME` term이 만들어지지 않는지.
- SQL `lower()`로는 재현되지 않는 정규화 결과(`İ` → `i̇`, 어말 시그마 `Σ` → `ς`)가 색인에
  그대로 저장되는지.
- 커버리지 gauge가 재색인 전 `0`, 재색인 후 `1.0`, 색인되지 않은 매장 추가 시 그 사이 값인지.
- Spring Modulith 구조 검증에서 `merchant ↔ discovery` 순환 의존 부재.
- 좌표 쌍·radius 경계, 좌표 유무에 따른 distance 필드·정렬 계약.
- 한 매장의 여러 메뉴 일치가 매장 한 건과 정확한 match reason으로 합쳐지는지 검증.
- pickupAvailable batch SQL 수가 candidate 수와 무관하게 고정인지 검증.
- availability 필터로 짧거나 빈 page와 nextCursor가 함께 나올 때 다음 page 누락·중복이 없는지 검증.
- 손상 slot counter가 false나 빈 page가 아닌 503인지 검증.
- favorite 동시 PUT, 반복 PUT/DELETE, 다른 customer 격리와 비노출 store 처리.
- BR-40의 모든 상태, 매장별 dedupe, 동률 tie-break와 추천 단계 dedupe.
- 좌표 없는 추천이 favorite와 recent를 유지하고 nearby를 추정하지 않는지 검증.
- raw query·토큰·좌표가 DB, log, metric tag, trace, event에 남지 않는지 검증(태그 키·값 단언).
- 검색 요청이 AuditRecord와 도메인 이벤트를 만들지 않는지 검증.
- `pg_trgm`과 recent/favorite index 실행계획 및 인덱스 추가 전후 같은 조건 측정.

## Validation Commands

```bash
./gradlew test --tests '*StoreSearch*' --tests '*FavoriteStore*' --tests '*RecentStore*'
./gradlew test --tests '*NearbyStore*' --tests '*PickupAvailability*'
./gradlew test --tests '*Brand*' --tests '*Region*' --tests '*SearchIndex*'
./gradlew spotlessCheck
./gradlew build --stacktrace
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
(cd frontend && npm run generate:api && npx tsc --noEmit)
```

## Observability

폐쇄 어휘 태그만 사용한다. 검색어, 토큰, 좌표, 매장 ID, 요청 URI는 어떤 태그에도 넣지 않는다.

| 지표 | 종류 | 태그 |
|---|---|---|
| `beanflow.discovery.search.count` | counter | `outcome` = `SUCCEEDED\|INVALID_INPUT\|DEPENDENCY_UNAVAILABLE` |
| `beanflow.discovery.search.latency` | timer | `outcome`, `sort` = `RELEVANCE\|DISTANCE` |
| `beanflow.discovery.search.page.size` | summary | — |
| `beanflow.discovery.search.tokens` | summary | — (토큰 **개수**만, 값 아님) |
| `beanflow.discovery.search.empty` | counter | `sort` |
| `beanflow.discovery.search.index.coverage` | gauge | — (색인된 매장 수 / 전체 매장 수) |
| `beanflow.discovery.search.index.update` | counter | `outcome`, `trigger` = `BRAND\|REGION\|REBUILD` |

`empty` counter는 "검색은 되는데 결과가 늘 0건"인 상태(색인 누락, 정규화 불일치)를 장애와 구분해
드러내기 위한 것이다.

추가로 유지한다.

- nearby·recommendation p50·p95·p99
- 좌표 없는 검색 비율
- pickup availability batch candidate 수와 query 지연
- 추천 reason별 노출·선택 수
- favorite command 결과와 권한 거부 수
- 브랜드 지정 매장 비율, 브랜드명 변경 1회당 갱신된 term 수
- `pg_trgm` index 크기·갱신 비용

## Documentation Updates

- [ADR-103](../../adr/ADR-103-store-search-strategy.md) 2026-08-15 Amendment — 완료
- [ADR-112](../../adr/ADR-112-store-brand-and-administrative-region.md) — 완료
- [ADR-070](../../adr/ADR-070-signed-cursor-and-pagination-contract.md) 정렬 tuple 등록 — 완료
- [BR-47](../../product/business-policy-decisions.md), [BR-40](../../product/business-policy-decisions.md) — BR-47 완료
- `docs/decisions/minor-decisions.md` MD-2026-015, MD-2026-016, MD-2026-018 — 완료
- `docs/security/authorization-matrix.md` — 완료
- `docs/api/api-conventions.md` — 검색 endpoint 규약
- `docs/api/error-catalog.md` — `BRAND_NAME_CONFLICT`, `BRAND_FANOUT_LIMIT_EXCEEDED`
- `docs/architecture/ubiquitous-language.md` — Brand, Region, 검색 term, 관련도
- `docs/architecture/capability-map.md`, `docs/architecture/context-map.md`
- `docs/operations/store-keyword-search-runbook.md` — 신규. 재색인 절차, 커버리지 점검 쿼리,
  색인 신선도 한계
- `docs/testing/test-strategy.md` — 검색 테스트 범주
- `README.md` — 현재 상태 목록
- `scripts/verify-docs.sh` — 새 필수 문서 등록
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- 신규 검색 실행계획 evidence 문서

## Progress

- 2026-08-12: 최초 작성. 아직 구현을 시작하지 않았다.
- 2026-08-15: 브랜드·지역 검색으로 범위를 확장했다. 사용자 결정 12건을 ADR-103 Amendment,
  ADR-112, ADR-070, BR-47, MD-2026-015/016, authorization matrix에 반영했다. 코드 변경은 없다.
- 2026-08-15: **Milestone 1 완료.** `feature/productization-70-store-keyword-search`에서 ADR-072
  lease를 획득하고 V57(스키마)·V58(법정동 시드 20,560행)과 `SearchTextNormalizer`,
  `scripts/generate-region-seed.py`를 추가했다. 완료 조건을 실제로 확인했다.
  - `SearchTextNormalizerTest` 9건 통과. NFKC 합성/분해, 전각/반각, `U+3000`·`U+00A0`,
    연속·양끝 공백, tr locale 소문자, 멱등성, 토큰 순서 보존을 고정한다.
  - `StoreSearchVocabularyMigrationTest` 10건 통과. 시드 행 수 `20560`과 대표 코드
    역삼동 `1168010100`(`서울특별시`/`강남구`/`역삼동`)을 조회하고, 세종특별자치시의 빈
    `sigungu`, 두 단어 시군구(`부천시 원미구`), 시드 재실행 후 동일 행 수, 활성 브랜드
    정규화 이름 유일성, 브랜드·지역 FK를 함께 확인한다.
  - 시드 스크립트는 같은 원본에서 byte 단위로 같은 SQL을 만든다(두 번 내려받아 diff 확인).
  - `./gradlew build` 전체 통과: 240 클래스 **1,121건 중 실패 0, skip 1**. skip은 기존
    `NearbyStoreDiscoveryBenchmark`로 이번 변경과 무관하다.
  - `./gradlew spotlessCheck`와 `scripts/verify-docs.sh` 통과.
  - **`Not run`:** `npm run generate:api && npx tsc --noEmit`(Milestone 11에서 계약 갱신과 함께),
    시드 스크립트의 다운로드 경로, 검색 질의 성능 evidence(Milestone 12).
- 2026-08-15: **Milestone 1-B 완료.** V57에 `ri` 열을 넣고 시드 생성 스크립트가 리 이름을 뽑도록
  고친 뒤 V58을 재생성했다. 20,560행 중 **15,209행**이 리 값을 갖고 전체 행 수는 변하지 않았다.
  `StoreSearchVocabularyMigrationTest` **13건 통과**(리 3건 추가).
  - 감정리 `5111031024`의 `eupmyeondong`이 `동면`으로 남고 `ri`가 `감정리`인 것을 고정한다.
    리 이름으로 덮어썼는지 여부를 이 단언 하나가 잡는다.
  - `ri` 유무와 코드 뒤 2자리 `00` 여부가 20,560행 전체에서 일치하는 것을 단언한다.
  - 전국 중복 이름(`상리` 23건)이 행을 잃지 않는 것을 확인한다.
  - 재생성 결과가 이전과 byte 단위로 동일하다(두 번째 원본으로 diff).
  - `./gradlew build` 전체 재통과: 240 클래스 **1,124건 중 실패 0, skip 1**.
    `spotlessCheck`와 `scripts/verify-docs.sh`도 통과.
- 2026-08-15: **Milestone 2 완료.** V59(색인 테이블), `shared/api`의 `StoreSearchIndexOperations`,
  `discovery/internal`의 색인 쓰기·재색인·커버리지 gauge, `merchant/api`의 색인 소스 port를 추가했다.
  - `StoreSearchTermIndexMigrationTest` **6건 통과.** 세 인덱스 생성과 `gin_trgm_ops` 연산자
    클래스, `COALESCE` 정체성 unique, 메뉴만 `source_id`를 갖는 CHECK, 가중치 범위, 알 수 없는
    term 종류 거부, 매장 삭제 시 CASCADE, 그리고 **migration이 행을 넣지 않는다는 것**을 고정한다.
  - `StoreSearchIndexRebuildIntegrationTest` **9건 통과.** 재색인 결과(색인 2·건너뜀 0·실패 0),
    판매 중 메뉴만 색인, 가중치 `1.00`/`0.70`/`0.90`/`0.80`, 재실행 멱등성, 직접 DML 변경이
    재색인 전에는 반영되지 않고 후에는 반영되는 것, profile 없는 매장의 실패 보고와 나머지 매장
    계속 처리, 브랜드 fan-out 교체·해제, transaction 밖 색인 쓰기 거부, 공백 term 400 거부,
    `REGION_*` 4행 → 3행 교체, 커버리지 gauge `0` → `1.0` → `2/3`을 확인한다.
  - 색인 문자열이 Kotlin 정규화 결과인 것을 `İSTANBUL` → `i̇stanbul`, `ΟΔΟΣ` → `οδος`,
    `  Ｓｔａｒ　버클  ` → `star 버클`로 고정한다. 앞의 두 개가 SQL 백필로는 낼 수 없던 값이다.
  - `./gradlew test --tests "io.github.kdh949.beanflow.architecture.*"` 통과.
    `merchant ↔ discovery` 순환 의존 없음.
  - `./gradlew build` 전체 통과: 242 클래스 **1,139건 중 실패 0, skip 1**(19분 42초).
    Milestone 1-B의 1,124건에서 15건 늘었고 skip은 기존 `NearbyStoreDiscoveryBenchmark`다.
    `spotlessCheck`와 `scripts/verify-docs.sh`도 통과.
  - **`Not run`:** `npm run generate:api && npx tsc --noEmit`(Milestone 11),
    검색 질의 성능 evidence(Milestone 12).
- 2026-08-15: 미착수 — Milestone 3~12.

## Surprises & Discoveries

- 기존 nearby의 픽업 가능 의미는 실제 잔여 슬롯 존재와 같지 않아 검색 endpoint만 추가해서는 화면
  간 결과가 일치하지 않는다.
- **(2026-08-15) `merchant` 모듈에 쓰기 endpoint가 하나도 없다.**
  `@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping`이 0개이며 매장·메뉴는
  `LocalDemoSeedCli`와 테스트 fixture로만 생성된다. 따라서 "매장명·메뉴명 변경 시 동기 색인 갱신"은
  가로챌 지점 자체가 없다. 브랜드·지역 쓰기 경로에서만 동기 갱신하고 나머지는 명시적 재색인
  커맨드와 커버리지 gauge로 다룬다. 브랜드·지역 명령이 BeanFlow 최초의 Merchant 쓰기 경로다.
- **(2026-08-15) `merchant ↔ discovery` 순환 의존 위험.** 색인 갱신(merchant → discovery)과 매장
  상태 조회(discovery → merchant)가 동시에 필요해 Spring Modulith 검증이 깨질 수 있다. 색인 갱신
  port를 `shared/api`에 두어 회피하며 ADR-112에 기록했다.
- **(2026-08-15) 오타 허용과 keyset cursor의 충돌.** 유사도는 실수라 그대로 cursor에 넣으면 page
  경계에서 동점 판정이 흔들려 누락·중복이 생긴다. nearby의 `distanceMicrometers`와 같은 정수
  양자화(`relevanceRank`)로 해결하고 전체 tuple을 all-ASC로 맞췄다.
- **(2026-08-15) 법정동명을 공백으로 자르면 시군구가 깨진다.** `"경기도 부천시 원미구 원미동"`처럼
  시군구가 두 단어인 행이 있어 단어 수로는 계층을 나눌 수 없다. 법정동 코드
  `2(시도)+3(시군구)+3(읍면동)+2(리)` 구조로 상위 행을 찾아 그 이름을 접두사로 잘라 내야 정확하다.
  단어 수 분포는 1~5단어로 흩어져 있어 이 방식만 전체 20,560행에서 실패 0건이었다.
- **(2026-08-15) 세종특별자치시에는 시도 자리 행이 없다.** `3600000000`이 존재하지 않고
  `3611000000`이 최상위다. `<시도>00000000`을 상위로 가정하면 세종 151행이 전부 누락된다.
  2자리 접두사별 **최소 코드**를 시도 행으로 삼으면 세종의 `sigungu`가 자연히 빈 문자열이 되어
  ADR-112가 예상한 모양과 일치한다.
- **(2026-08-15) 원본에 이름 끝 공백이 있다.** `"경기도 부천시 원미구 "` 등 4행이다. 그대로 두면
  하위 행의 접두사 제거가 어긋나 24행이 실패한다. 읽는 즉시 `strip`한다.
- **(2026-08-15) 리 단위 행이 시드의 74%다.** 폐지되지 않은 20,560행 중 15,209행이 리다.
  설계 당시 3계층 어휘는 이 비중을 모르고 정한 것이었고, 그대로 두면 리에 있는 매장이 리 이름으로
  검색되지 않는다. **한계로 남기지 않고 ADR-112 리 Amendment로 `ri` 열과 `REGION_RI` term 종류를
  추가하기로 했다.** 리 행의 `eupmyeondong`은 상위 읍·면 이름을 그대로 두므로 검색 범위가 이동하지
  않고 넓어진다. 법정동 코드 뒤 2자리가 리 코드라 새 자료 없이 판별된다.
- **(2026-08-15) `merchant_store` 컬럼 집합을 통째로 고정한 기존 테스트가 `brand_id`로 깨졌다.**
  `StoreDiscoveryProfileMigrationTest`가 ADR-020의 "검색용 이름·geometry·spatial index를 추가하지
  않는다"를 지키려고 컬럼 목록 전체를 `containsExactlyInAnyOrder`로 고정하고 있었다. `brand_id`는
  검색 편의 필드가 아니라 ADR-112가 DDL까지 명시한 Aggregate 참조라 규칙 위반이 아니고, 고정
  목록이 규칙보다 넓게 잡혀 있던 것이다. 목록 고정은 유지한 채(새 컬럼은 여전히 실패해야 판단이
  강제된다) `brand_id`만 추가하고, 이름·좌표·`region_code` 부재 단언을 따로 넣어 ADR-020이 실제로
  막는 것을 직접 고정했다. 대상 테스트만 돌렸을 때는 잡히지 않고 전체 build에서 드러났다.
- **(2026-08-15) SQL `lower()`는 Kotlin 정규화를 재현하지 못한다.** 색인 백필을 migration SQL로
  쓰려면 `normalize(NFKC) + lower() + 공백 축약`으로 정규화를 두 번째로 구현해야 한다. 같은 입력을
  두 경로에 넣어 비교하는 테스트를 먼저 만들어 확인한 결과 두 건이 갈렸다.
  `İSTANBUL`은 SQL `istanbul` / Kotlin `i̇stanbul`(`U+0069 U+0307`)이고, `ΟΔΟΣ`는
  SQL `οδοσ` / Kotlin `οδος`다. Java `String.lowercase()`는 Unicode SpecialCasing과 어말 시그마
  조건을 적용하지만 PostgreSQL `lower()`는 적용하지 않는다. 이런 차이는 실패하지 않고 **색인만
  조용히 어긋나** 해당 매장이 검색되지 않는다. 문자를 개별 치환해 맞추는 대신 두 번째 구현 자체를
  없애고 초기 적재를 애플리케이션 재색인으로 옮겼다(MD-2026-018). 대가로 V59 적용 직후 커버리지가
  `0`이며, 그 창은 gauge와 runbook으로 드러낸다.
- **(2026-08-15) 색인 문자열 상한을 원본 상한과 같게 두면 안 된다.** NFKC는 문자열을 늘릴 수 있다
  (`U+FDFD` 한 글자 → 18자). 원본이 `varchar(200)`이라고 `term_normalized`를 같은 200으로 잡으면
  긴 이름에서 색인 쓰기가 DB 오류로 깨진다. 두 배인 `varchar(400)`으로 두고, 그래도 넘치면 잘라
  담지 않고 명시적으로 거부한다.
- **(2026-08-15) 이 환경에서는 스크립트의 다운로드 경로를 실행할 수 없다.** TLS 가로채기로 Python
  `urllib`의 CA 검증이 실패한다(`CERTIFICATE_VERIFY_FAILED`). `--source-zip`으로 미리 받아 둔
  원본을 넘겨 생성했고 checksum 검증 경로는 그대로 통과했다. **다운로드 경로 자체는 검증되지
  않았다(`Not run`).**

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-12 | PostgreSQL `pg_trgm`, Context 간 batch port와 scan-boundary cursor 사용 | [ADR-103](../../adr/ADR-103-store-search-strategy.md) |
| 2026-08-12 | recent는 결제 승인 이후 현재 실행·완료 상태만 포함 | [BR-40](../../product/business-policy-decisions.md) |
| 2026-08-12 | 좌표 없는 추천도 favorite → recent 순서를 유지 | [BR-40](../../product/business-policy-decisions.md) |
| 2026-08-15 | 검색 대상에 브랜드명·지역명 추가, 결과는 매장 단위 + 매칭 메뉴 최대 3개 | [ADR-103 A1/A5](../../adr/ADR-103-store-search-strategy.md), [BR-47](../../product/business-policy-decisions.md) |
| 2026-08-15 | 매칭은 substring 우선 + 유사도 `0.3` 보완 하이브리드. 오타 교정 non-goal 철회 | [ADR-103 A2](../../adr/ADR-103-store-search-strategy.md) |
| 2026-08-15 | 다중 토큰은 AND. 지역 인식 파서를 두지 않는다 | [ADR-103 A3](../../adr/ADR-103-store-search-strategy.md) |
| 2026-08-15 | `sort=relevance\|distance`를 클라이언트가 선택. 관련도는 정수 양자화 | [ADR-103 A4](../../adr/ADR-103-store-search-strategy.md), [ADR-070](../../adr/ADR-070-signed-cursor-and-pagination-contract.md) |
| 2026-08-15 | `openOnly`를 `pickupAvailable`과 독립 필터로 추가. 기본은 닫힌 매장 포함 | [ADR-103 A6](../../adr/ADR-103-store-search-strategy.md) |
| 2026-08-15 | page 최대 50과 query 2~50자 상한은 개정하지 않는다 | [ADR-103](../../adr/ADR-103-store-search-strategy.md) |
| 2026-08-15 | Brand는 `merchant` 소유 Aggregate. `brand_id`는 nullable | [ADR-112](../../adr/ADR-112-store-brand-and-administrative-region.md) |
| 2026-08-15 | 지역은 법정동 코드 폐쇄 어휘. 백필 뒤 NOT NULL 승격 | [ADR-112](../../adr/ADR-112-store-brand-and-administrative-region.md) |
| 2026-08-15 | 브랜드는 운영자, 지역은 매장주가 관리 | [ADR-112](../../adr/ADR-112-store-brand-and-administrative-region.md), authorization matrix |
| 2026-08-15 | 검색 전용 term 테이블을 동기 갱신. 배치·큐를 쓰지 않는다 | [ADR-103 A1](../../adr/ADR-103-store-search-strategy.md), [ADR-112](../../adr/ADR-112-store-brand-and-administrative-region.md) |
| 2026-08-15 | 브랜드 fan-out 상한 1000, term 가중치, 법정동 시드 생성 방식 | MD-2026-015, MD-2026-016 |
| 2026-08-15 | 설정 주소지는 client storage에만 두고 서버는 좌표를 저장하지 않는다. BR-28 Revisit Condition을 승격하지 않는다 | MD-2026-017, [ADR-020](../../adr/ADR-020-nearby-location-privacy.md) 2026-08-15 평가 |
| 2026-08-15 | 메뉴 검색은 반경 내 전 매장을 대상으로 하되 결과 단위는 매장 카드로 유지한다 | [BR-47](../../product/business-policy-decisions.md) |
| 2026-08-15 | 시도/시군구/읍면동은 공백이 아니라 법정동 코드 계층으로 분해한다 | 이 문서 Surprises, `scripts/generate-region-seed.py` |
| 2026-08-15 | ~~리 이름으로는 검색되지 않는다~~ → **철회.** 리 열과 `REGION_RI` term 종류를 추가해 리도 검색 대상으로 만든다 | [ADR-112 리 Amendment](../../adr/ADR-112-store-brand-and-administrative-region.md), [ADR-103 A7](../../adr/ADR-103-store-search-strategy.md), [BR-47](../../product/business-policy-decisions.md) |
| 2026-08-15 | 미병합·미적용 V57/V58은 새 migration을 덧붙이지 않고 그 자리에서 고친다 | 이 문서 Data and Migration, [ADR-072](../../adr/ADR-072-execplan-unattended-execution-and-migration-lane.md) |
| 2026-08-15 | 원본 checksum은 ZIP이 아니라 내부 텍스트 내용으로 고정한다 | MD-2026-016, `scripts/generate-region-seed.py` |
| 2026-08-15 | 색인 초기 적재는 migration SQL 백필이 아니라 애플리케이션 재색인이 수행한다 | MD-2026-018, 이 문서 Surprises |
| 2026-08-15 | `term_normalized`는 `varchar(400)`. 상한 초과는 절단이 아니라 거부 | 이 문서 단계 2, `StoreSearchIndexService.kt` |
| 2026-08-15 | 색인 쓰기 port는 `Propagation.MANDATORY`로 커맨드 transaction을 강제한다 | 구현 불변식 11, `StoreSearchIndexService.kt` |
| 2026-08-15 | 재색인은 `STORE_NAME`·`MENU_NAME`만 교체한다. 브랜드·지역 term은 소유 커맨드가 채운다 | `StoreSearchIndexRebuildService.kt` |

## Outcomes & Retrospective

아직 없다. 측정하지 않은 성능·동작 결과를 여기에 기록하지 않는다.

## Revision Notes

- 2026-08-12: 최초 작성.
- 2026-08-15: 매장명·메뉴명 검색에서 매장명·브랜드명·지역명·메뉴명 통합 검색으로 확장.
  Brand Aggregate, 법정동 어휘, 동기 색인 테이블, 운영자·매장주 쓰기 경로와 재색인 커맨드를
  In Scope에 추가했다. 별도 초안이던 `store-brand-region-keyword-search.md`는 이 문서로 흡수하고
  삭제했다.
- 2026-08-15: Milestone 1 구현 중 법정동 자료의 74%가 리 단위임을 확인하고, 리를 검색 불가 한계로
  남기는 대신 ADR-112 리 Amendment와 ADR-103 A7으로 `ri` 열과 `REGION_RI` term 종류를 추가했다.
  term 종류가 여섯에서 일곱으로 늘고 지역 어휘가 4계층이 된다. 정렬 튜플과 cursor 계약은 그대로다.
- 2026-08-15: Milestone 2 구현 중 SQL `lower()`가 Kotlin 정규화를 재현하지 못하는 것을 측정으로
  확인하고, 단계 2의 "migration 백필"을 애플리케이션 재색인으로 옮겼다(MD-2026-018).
  `term_normalized` 상한도 `varchar(120)`에서 `varchar(400)`으로 고쳤다. 완료 조건은 "재색인 실행
  뒤 커버리지 `1.0`"으로 구체화했다.
- 2026-08-15: 매장을 가로지르는 메뉴 검색의 조회 순서를 명시하고, 설정 주소지를 client storage
  경계로 확정했다. 서버 스키마와 공개 API 계약은 변경되지 않는다. 메뉴 단위 결과 목록을
  Non-goals에 추가했다.
