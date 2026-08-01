# 근접 매장 Discovery 조회를 위치정보 보존 없이 제공한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/active/signed-cursor-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

인증된 고객이 위도·경도와 반경을 보내면 BeanFlow는 PostGIS에서 픽업 가능한 매장을
거리 오름차순·store ID 오름차순으로 검색한다. 고객 정밀 좌표는 request validation과
read-only query 동안에만 쓰며 DB, cache, AuditRecord, application log, trace, metric에는
저장하지 않는다. 이 Discovery slice는 기존 OpenAPI의 매장 메뉴·픽업 슬롯 read endpoint도
owner DTO projection으로 완성한다.

완료 후 고객은 안정적인 opaque cursor로 가까운 매장을 탐색한다. PostGIS/DB 장애는 빈 목록,
cached 결과 또는 애플리케이션 Haversine 계산으로 숨기지 않고 503으로 관찰된다.

## Current State

- BR-01/28과 ADR-020은 `Asia/Seoul`, request-only coordinate, PostGIS 장애의 명시적 실패를 확정했다.
- OpenAPI는 `GET /stores/nearby`, `GET /stores/{storeId}/menus`,
  `GET /stores/{storeId}/pickup-slots`와 `(distance, storeId)` cursor를 계약으로 둔다.
- Merchant에는 최소 Store/menu/configuration, Fulfillment에는 PickupSlot이 있지만 별도
  `StoreDiscoveryProfile`, Discovery module과 세 HTTP read endpoint는 없다. 기존
  `merchant_store`에는 검증 가능한 공개 이름·위치 source가 없다.
- `StoreEntity.acceptingOrders`와 `pickupEnabled`가 현재 owner availability다. 미결정 영업시간
  model을 추가하지 않으며 `open=acceptingOrders`, `pickupAvailable=acceptingOrders && pickupEnabled`
  로 투영한다.

## Definitions

- **Precise query coordinate:** 한 nearby request에만 쓰고 어떤 durable record에도 넣지 않는 입력이다.
- **Store geography:** Merchant `StoreDiscoveryProfile`이 소유하고 Discovery가 request-only query에서
  읽는 `geography(Point,4326)` 매장 위치다.
- **StoreDiscoveryProfile:** `merchant_store`와 1:1인 Merchant-owned profile이다. `store_id` PK/FK,
  검증된 공개 `name`과 `location geography(Point,4326)`를 가지며 Store 쓰기 Entity와 분리한다.
- **Discovery projection:** Merchant public Query API가 Store operational state와 profile을 조합해
  반환하는 DTO다. Discovery는 이를 영속 복제하거나 event로 동기화하지 않는다.
- **Distance cursor:** ADR-070의 `v1.<key-id>.<payload>.<signature>` HMAC token에
  `(distanceMicrometers, storeId)` 마지막 tuple과 canonical nearby filter hash를 bound한 page token이다.
- **Pickup-capable:** `open`과 `pickupAvailable`이 모두 true인 매장이다.

## Scope

### In Scope

- Discovery Modulith module, Merchant/Fulfillment read Application API와 DTO projection boundary
- 별도 Merchant `StoreDiscoveryProfile` migration, PostGIS extension/geography/GiST index와
  existing-row preflight/startup gate
- nearby validation, signed-cursor foundation을 소비하는 HMAC distance/store-ID cursor, radius filtering,
  deterministic micrometer sort and integer-meter response conversion
- 메뉴·픽업 슬롯 read endpoint, coordinate redaction test, PostGIS availability health
- PostgreSQL/PostGIS Testcontainers, API contract, pagination/concurrency/failure validation

### Non-goals

- 고객 위치 이력, 개인화, geofence, background location, coordinate cache
- 영업시간 schema/예약 정책 변경, map/route Provider, local distance fallback
- Merchant profile write UI, external geocoding, 위치 기반 analytics

## Business Rules and Invariants

- latitude는 `[-90,90]`, longitude는 `[-180,180]`, radius는 `1..10000` integer다. invalid input은 400이다.
- raw coordinate와 원본 좌표를 복원할 cursor는 entity, DB query audit, log, trace, metric,
  `AuditRecord`, exception message에 남지 않는다.
- result는 반경 안 pickup-capable Store만 `(distanceMicrometers ASC, storeId ASC)`로 반환하고,
  response에는 `floor(distanceMicrometers / 1_000_000)`인 `distanceMeters`를 반환한다.
  cursor는 같은 endpoint/filter/sort contract의 다음 page에만 쓰며 다른 radius, signature/scope
  mismatch 또는 malformed/expired cursor는 400이다. `limit`은 default 20, maximum 100이다.
- Store geometry가 없거나 invalid이면 거리 0/임의 위치로 보완하지 않는다. migration gate가
  migration/deployment와 application startup을 멈춘다. Store가 하나라도 있으면 모든 Store에
  verified profile이 있어야 하며 일부 row를 암묵적으로 검색 제외하지 않는다.
- current owner availability만 투영하며 stale cache가 비활성 Store를 가능하다고 보이면 안 된다.

## Architecture and Transaction Boundaries

- `DiscoveryQueryService`는 read-only transaction에서 Merchant public Query API를 호출한다. Merchant
  Query Repository가 `StoreDiscoveryProfile`과 current Store state를 PostGIS native DTO projection으로
  읽으며 Discovery/Controller는 Merchant Entity나 Repository를 직접 호출하지 않는다.
- Store profile table/write/migration과 spatial query persistence는 Merchant owner다. Discovery는
  validation, cursor adapter와 response projection을 소유한다. 메뉴 endpoint는 Merchant projection,
  pickup slot endpoint는 Fulfillment projection을 호출한다.
- coordinate는 controller binding 뒤 query value object로만 전달하고 response/correlation/log context,
  exception details에서 제외한다. 외부 map/geocode call은 없다.
- extension/query/DB failure는 503으로 매핑한다. fallback repository, in-memory index, local Map은 없다.
- common cursor codec/key ring configuration은 signed-cursor foundation outcome을 소비한다. missing/
  malformed active key는 unsigned/local default cursor로 대체하지 않고 application startup을 실패시킨다.

## Alternatives Considered

- Haversine application 계산: spatial index를 우회하고 PostGIS 장애를 숨기므로 제외한다.
- raw coordinate log/trace: BR-28/ADR-020과 충돌한다.
- query-coordinate cache: cache key/telemetry에 개인 위치가 남을 위험이 있어 제외한다.
- 영업시간 Aggregate 동시 도입: owner model을 추정하는 별도 product decision이므로 제외한다.
- `merchant_store` 직접 확장: Store write Entity에 공개 profile과 검색 index를 결합하고 Context Map을
  위반하므로 제외한다.
- Discovery-owned persistent projection: event/outbox/reconciliation 장애 경로를 추가하므로 MVP에서는
  제외한다.

## Failure Semantics

- extension 미설치, geometry query/index failure, DB timeout은 `DEPENDENCY_UNAVAILABLE` 503이다.
  빈 `items` 200 또는 계산 불가능 distance로 대체하지 않는다.
- invalid coordinate/radius/cursor는 `INVALID_REQUEST` 400이며 spatial query를 실행하지 않는다.
- missing Store menu/slot query는 404이며 DB failure를 404로 바꾸지 않는다.
- raw coordinate가 log/trace/metric/Audit scan에서 발견되면 release blocker다. 원본 좌표를
  조사용 Audit에 복사하지 않는다.

## Data and Migration

implementation branch는 ADR-072 migration-writer lease를 얻은 뒤 target PostgreSQL에서 PostGIS extension
availability와 `CREATE EXTENSION` 권한을 preflight한다. 기존 Store inventory는 다음 둘 중 하나여야 한다.

1. `merchant_store` row가 0개여서 empty migration path가 가능하다.
2. 모든 Store에 대해 owner가 검증한 non-blank public name과 유효 좌표 dataset이 있고, Store ID
   coverage가 정확히 일치한다.

row가 하나라도 unresolved이면 migration/deployment를 시작하지 않는다. source를 추측하거나 fixture,
menu name, 주문 이력, 외부 geocoder로 보완하지 않는다.

forward migration은 PostGIS extension을 활성화하고 별도 table을 만든다.

```sql
CREATE TABLE merchant_store_discovery_profile (
    store_id uuid PRIMARY KEY REFERENCES merchant_store(id),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    location geography(Point,4326) NOT NULL
);

CREATE INDEX idx_store_discovery_profile_location
    ON merchant_store_discovery_profile USING GIST (location);
```

verified non-empty dataset은 같은 release evidence로 exact Store ID coverage를 검증한 뒤 적용한다.
application startup validator는 migration 뒤 `merchant_store`와 profile의 양방향 coverage, non-blank
name과 valid SRID/point를 다시 확인한다. 누락/고아/invalid profile이 하나라도 있으면 readiness만
DOWN으로 숨기지 않고 application startup을 실패시킨다. 현재 Store 생성 API는 없으며 후속 Store
write는 Store와 required profile을 같은 Merchant transaction에서 만들기 전에는 활성화하지 않는다.

Store profile geography만 저장하고 customer coordinate table/column/audit은 만들지 않는다. cursor는 DB에
저장하지 않고 signed-cursor foundation의 ADR-070 codec을 사용한다. range filter는 raw
`ST_DWithin(..., radiusMeters)`이고 query projection/cursor predicate는
`floor(ST_Distance(...) * 1_000_000)` micrometer tuple이다. latitude/longitude는 finite BigDecimal로
parse한 뒤 trailing zero를 제거하고 signed zero를 `0`으로 canonicalize해 filter hash에만 넣는다.
raw coordinate와 radius는 token payload가 아니라 filter hash input이며 old verification key는 24시간
rotation window 동안만 허용한다. 공통 Testcontainers configuration은 PostgreSQL 17/PostGIS 3.5
image와 extension을 명시해 일반 PostgreSQL image나 local distance fallback으로 spatial test가
통과하지 않게 한다.

## API and Event Contracts

- `GET /stores/nearby`는 OpenAPI query와 `NearbyStorePage`를 그대로 구현한다. `radiusMeters`는
  1..10000이고, range filter/raw distance, micrometer sort/cursor tuple, integer-meter response는
  ADR-070의 same canonical expression을 쓴다. `37.5`와 `37.5000`의 coordinate filter hash는 같고
  raw text는 cursor에 없다. cursor는 endpoint/filter hash/signature/24시간 expiry를 검증하고 limit
  omission은 20, 100 초과는 400으로 처리한다.
- 메뉴와 slot endpoint는 JPA Entity가 아닌 current owner DTO projection을 반환한다. availability/capacity는
  response 시점 owner state이며 write graph를 노출하지 않는다.
- customer location은 event payload, Analytics, Notification, Audit에 발행하지 않는다. Discovery read는
  persistent domain event를 만들지 않는다.
- raw coordinate echo, debug source, profile private field는 schema에 추가하지 않는다.

## Milestones

1. signed-cursor foundation outcome, PostgreSQL 17/PostGIS 3.5 Testcontainers, extension privilege와
   existing Store profile inventory를 검증한다.
2. 별도 `StoreDiscoveryProfile`, GiST index, startup coverage validator와 Merchant public Query API를 만든다.
3. nearby validation, spatial projection, cursor, 503 mapping과 privacy redaction을 하나의 vertical slice로 완성한다.
4. 메뉴와 pickup slot read projection/API를 추가하고 availability/capacity contract를 고정한다.
5. data-scale query plan/latency baseline, failure behavior, runbook/documentation evidence를 기록한다.

## Required Tests

- coordinate/radius `1/10000/10001`/cursor bounds, HMAC tamper/unknown key/expiry/cross-filter cursor,
  `37.5`/`37.5000` filter normalization, omitted/1/100/101 limit, empty/single/multi-page micrometer/store-ID tie order
- radius boundary, same-distance store ID tie, disabled/open/pickup-disabled Store filtering
- StoreDiscoveryProfile migration: empty, exact verified coverage, missing/orphan/invalid/unresolved startup
  gate; GiST execution plan capture
- `merchant_store`에 name/location/index가 추가되지 않고 Discovery persistent replica/event가 없는지 검증
- PostGIS unavailable/timeout 503, no Haversine/in-memory/cache fallback, DB error not 404
- request/response/log/trace/metric/Audit DB scan에서 raw coordinate/cursor leakage 부재
- menu visibility, slot remaining capacity, missing Store and concurrent availability change
- PostGIS Testcontainers, MockMvc/OpenAPI contract, Modulith boundary, fixed Clock

## Validation Commands

- `./gradlew test --tests '*Discovery*' --tests '*Merchant*' --tests '*Fulfillment*'`
- `./gradlew test --tests '*ModularityTests'`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

동일 PostGIS image, row count, radius, page limit에서 `EXPLAIN (ANALYZE, BUFFERS)`와 latency를
measurement plan에 actual value로 기록한다. 기준선 없는 성능 수치는 쓰지 않는다.

## Observability

- `beanflow.discovery.nearby.count{outcome}`, `beanflow.discovery.nearby.latency`
- `beanflow.discovery.spatial.failure{reason}`, `beanflow.discovery.profile.missing.count`

reason/outcome은 closed vocabulary다. raw/rounded coordinate, cursor payload, store/customer ID, IP,
request URI query string을 log/trace/metric tag에 넣지 않는다.

## Documentation Updates

- BR-01/28, ADR-020/070 implementation evidence와 Merchant profile ownership
- context map, invariants, transaction boundaries, failure semantics, ubiquitous language
- OpenAPI/API conventions/error catalog/authorization matrix, test strategy, measurement plan,
  Discovery runbook, quality evidence map and this ExecPlan

## Progress

- [ ] PostGIS/container, extension privilege and existing profile preflight/startup gate
- [ ] Merchant profile migration and owner API
- [ ] nearby query, cursor and privacy redaction
- [ ] menu/pickup read projections
- [ ] failure/measurement/runbook evidence
- [ ] full validation

## Surprises & Discoveries

- 2026-08-01: ADR-020/OpenAPI는 nearby contract를 제공하지만 current Store schema에는 search geometry와
  public name이 없다. unsafe default coordinate/name을 막는 preflight/startup gate가 필요하다.
- 2026-08-01: `merchant_store` 직접 확장은 Context Map의 translation boundary와 충돌하므로 별도
  Merchant `StoreDiscoveryProfile`과 public Query DTO로 소유권을 분리했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted existing | coordinate request-only, PostGIS failure 503 | 개인 위치 최소 보존과 false-success 방지 | BR-28, ADR-020 |
| 2026-08-01 | Plan interpretation | `open=acceptingOrders`, `pickupAvailable=acceptingOrders && pickupEnabled` | 존재 owner state만 투영 | current `merchant_store`, OpenAPI |
| 2026-08-01 | Plan boundary | menu/slot read projection을 Discovery slice에 포함 | 공개 Discovery contract 완결, write owner 불변 | OpenAPI, Context Map |
| 2026-08-01 | Accepted | nearby cursor는 versioned HMAC, endpoint/filter binding, 24시간 expiry와 20/100 limit을 사용 | radius/scope tamper와 unbounded page 방지 | ADR-070 |
| 2026-08-01 | Accepted | nearby는 signed-cursor foundation을 소비하고 10km/raw-range/micrometer tuple/decimal-normalized filter hash를 사용 | cursor ownership 중복과 rounded-distance page gap 방지 | ADR-070 |
| 2026-08-01 | Accepted | 별도 Merchant StoreDiscoveryProfile과 동기 public Query DTO를 사용 | Store Entity 검색 확장과 새 projection 동기화 장애를 모두 피함 | ADR-020, Context Map |
| 2026-08-01 | Accepted | empty 또는 exact verified profile coverage만 허용하고 unresolved row는 startup 실패 | placeholder·부분 검색 활성화 방지 | ADR-020, failure semantics |

## Outcomes & Retrospective

미구현 상태다. signed-cursor dependency가 완료되면 implementation-ready로 승격할 수 있지만,
각 target environment의 PostGIS privilege와 empty/exact verified Store profile inventory는 별도
fail-closed release gate다. gate 실패는 새 제품 결정을 요구하는 모호성이 아니라 정해진 배포 중단
결과다. 완료 시 coordinate non-retention evidence, spatial failure behavior와 같은 조건의 query
measurement를 actual value로 기록한다.

## Revision Notes

- 2026-08-01: BR-28/ADR-020과 Discovery OpenAPI에 대응하는 누락 ExecPlan을 최초 작성.
- 2026-08-01: HMAC cursor key rotation, failure and common page-bound contract를 ADR-070으로 고정.
- 2026-08-01: 10km radius, raw range predicate와 micrometer cursor tuple, decimal filter normalization을
  ADR-070 amendment와 signed-cursor foundation으로 고정했다.
- 2026-08-01: 별도 Merchant StoreDiscoveryProfile, PostgreSQL 17/PostGIS 3.5, exact coverage
  preflight/startup gate와 no-replica Query API boundary를 확정했다.
