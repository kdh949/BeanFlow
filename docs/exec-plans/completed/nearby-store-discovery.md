# 근접 매장 Discovery 조회를 위치정보 보존 없이 제공한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/signed-cursor-foundation.md`
> **Completed-At:** `2026-08-07`

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

- [x] PostGIS/container, extension privilege and existing profile preflight/startup gate
- [x] Merchant profile migration and owner API
- [x] nearby query, cursor and privacy redaction
- [x] menu/pickup read projections
- [x] failure/measurement/runbook evidence
- [x] full validation

Milestone 1~5를 모두 완료했다. 세 read endpoint가 Runtime OpenAPI와 parity를 유지하고,
두 규모 measurement와 runbook/quality evidence를 실제 값으로 남겼다.

## Surprises & Discoveries

- 2026-08-01: ADR-020/OpenAPI는 nearby contract를 제공하지만 current Store schema에는 search geometry와
  public name이 없다. unsafe default coordinate/name을 막는 preflight/startup gate가 필요하다.
- 2026-08-01: `merchant_store` 직접 확장은 Context Map의 translation boundary와 충돌하므로 별도
  Merchant `StoreDiscoveryProfile`과 public Query DTO로 소유권을 분리했다.
- 2026-08-06: `postgis/postgis:17-3.5`에는 `linux/amd64` manifest만 있고 arm64 manifest가 없다
  (`docker manifest inspect` 확인, `-alpine`/`-master` 태그도 동일). CI runner는 `ubuntu-latest`
  이므로 native이고, Apple Silicon workstation은 Docker 에뮬레이션으로 같은 image를 실행한다.
  실측 기동 결과는 `PostgreSQL 17.5`와 `POSTGIS="3.5.2"`이며 `CREATE EXTENSION postgis` 권한도
  확인했다. multi-arch third-party rebuild로 바꾸면 image 출처가 달라지므로 채택하지 않았다.
- 2026-08-06: V33이 모든 환경에서 PostGIS를 요구하므로 공통 Testcontainers image를
  `postgres:17.6`에서 `postgis/postgis:17-3.5`로 교체해야 했다. 12개 migration test가 각자
  image 이름을 갖고 있어 `BEANFLOW_POSTGRES_IMAGE` 단일 상수로 모았다. 이제 plain PostgreSQL
  image로는 spatial test가 통과할 수 없다.
- 2026-08-06: `merchant_store`에는 name/location source가 없고 Store 생성 API도 없다.
  release evidence의 `shared/production deployment environment = 0`,
  `production/shared 환경에 적용된 migration = 0`과 함께 empty migration path가 유일하게 근거
  있는 경로였다. verified dataset이 없으므로 backfill을 시도하지 않았다.
- 2026-08-06: `ApiExceptionHandler`가 binding/validation 예외 message를 `details[].reason`으로
  응답에 넣는다. latitude/longitude를 typed parameter로 바인딩하면 conversion 예외 message가
  원본 좌표를 그대로 echo하므로 BR-28을 위반한다. 그래서 좌표·radius·limit·cursor를 raw
  `String`으로 받고 Discovery가 직접 검증해 값 없는 `INVALID_REQUEST`만 반환한다.
- 2026-08-06: PostgreSQL은 `uuid`를 bytewise로 정렬하고 Java `UUID.compareTo`는 signed long
  기준이라 tie-break 순서가 다르다. keyset 비교와 정렬을 모두 SQL에서 수행하므로 실제 계약은
  PostgreSQL 순서이고, 테스트 기대값도 canonical hex 문자열 순서로 맞췄다.
- 2026-08-07: `merchant_menu`에는 별도 visibility 컬럼이 없어 "currently visible menus" 요약을
  필터 규칙으로 해석할 근거가 없었다. `available`이 required 필드이므로 전 메뉴를 반환하고 실제
  owner flag를 투영하기로 하고 MD-2026-011에 기록했다.
- 2026-08-07: `PickupReservationService.reserve`가 슬롯 시간을 검증하지 않는다는 사실을 확인했다.
  조회만 `ends_at > now`로 좁히면 read/write 범위가 달라지므로 사용자 결정을 받아 read 범위만
  좁히고 차이를 MD-2026-010과 runbook에 남겼다.
- 2026-08-07: nearby benchmark 첫 실행의 `p50 = 117.936 ms`는 `DriverManagerDataSource`가 호출마다
  새 connection을 여는 측정 결함이었다. 단일 connection 재사용으로 바꾸자 같은 조건에서
  `p50 = 0.397 ms`가 나왔다. 결함 수치와 원인을 evidence에 함께 남겼다.
- 2026-08-07: planner는 10,000/100,000 규모 모두에서 plain `Index Scan`이 아니라 GiST
  `Bitmap Index Scan` 경로를 골랐다. 접근 방식을 강제하는 assertion 대신 index 사용 여부만
  검증하도록 고쳤고, 수치를 좋게 만들기 위한 index는 추가하지 않았다.
- 2026-08-06: profile을 JPA Entity로 매핑하면 `geography` 매핑용 persistence dependency가
  추가로 필요하고 `ddl-auto: validate`와 충돌한다. Merchant가 JDBC native projection만 소유하도록
  해 새 production dependency 없이 Store 쓰기 Entity도 그대로 두었다.
- 2026-08-06: V33 coverage gate가 `CustomerCancellationMigrationTest`의 V23 backfill 케이스에서
  실제로 발동했다. 이 케이스는 store를 먼저 넣고 head까지 migrate했기 때문이다. gate는 ADR-020이
  정한 동작이므로 유지하고, V23을 검증하는 케이스가 V23을 target하도록 정정했다. 이는 gate가
  기존 store에 대해 실제로 배포를 멈춘다는 첫 실증이다. 앞으로 store를 seed한 뒤 head까지
  migrate하는 테스트는 profile까지 함께 seed해야 한다.
- 2026-08-06: 이 workstation의 Docker VM 파일시스템이 가득 차 있어(118G 중 111G 사용, 여유 1.2G)
  전체 `./gradlew clean build`가 완주하지 못했다. 실패는 모두
  `ContainerLaunchException … Container exited with code 1`과 그 뒤의 Spring context 실패였고,
  같은 클래스를 10개 단위로 나눠 실행하면 전부 통과한다. 메모리 문제가 아님은 PostGIS 컨테이너
  6개 동시 기동(각 45 MiB)으로 확인했다. 원인은 저장소 코드가 아니라 host 자원이다.
- 2026-08-06: `OrderTerminationResourceListenerIntegrationTest`가 부하 상황에서
  `expected: SUCCEEDED but was: PROCESSING`으로 한 번 실패했다. 단독 실행에서는 이 branch와
  `main` 모두 통과한다. emulated container가 느려 async publication timing에 민감해진 것으로
  보이지만 측정 근거가 없어 이번 변경의 회귀로 단정하지 않는다.

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
| 2026-08-06 | Minor decision | 공통 Testcontainers image를 `postgis/postgis:17-3.5`로 고정하고 단일 상수로 모음 | V33이 모든 환경에서 extension을 요구하며 plain image로 spatial test가 통과하면 안 됨 | Minor Decision, `BEANFLOW_POSTGRES_IMAGE` |
| 2026-08-06 | Minor decision | nearby query parameter를 raw `String`으로 받고 Discovery가 직접 검증 | framework conversion 예외 message가 원본 좌표를 error body에 echo하는 것을 차단 | Minor Decision, BR-28 |
| 2026-08-06 | Minor decision | profile을 JPA Entity가 아닌 Merchant JDBC native projection으로만 읽음 | spatial 매핑용 새 production dependency와 Store 쓰기 Entity 확장을 모두 회피 | Minor Decision, ADR-020 |
| 2026-08-06 | Minor decision | coordinate query parameter는 plain finite decimal만 허용하고 exponent 표기를 거부 | canonical filter hash를 결정적으로 유지하고 검증 표면을 좁힘 | Minor Decision, ADR-070 |
| 2026-08-07 | Answered | `GET /stores/{storeId}/pickup-slots`는 `ends_at > now`인 슬롯만 반환한다. read 전용 범위이며 `PickupReservationService.reserve`의 시간 미검증 동작은 이번 범위에서 바꾸지 않는다 | 계약이 "available pickup slots"이고 종료된 슬롯은 픽업할 수 없다. read/write 범위 차이는 문서에 명시해 숨기지 않는다 | Minor Decision, 이 plan, pickup slot runbook |
| 2026-08-07 | Plan interpretation | `GET /stores/{storeId}/menus`는 store의 메뉴를 모두 반환하고 `available`에 현재 owner state를 그대로 투영한다 | schema가 `available`을 required로 두므로 항상 true면 계약이 무의미하다. `merchant_menu`에 별도 visibility 컬럼이 없어 없는 모델을 추정하지 않는다 | target OpenAPI, `merchant_menu` |

## Outcomes & Retrospective

Milestone 1~5가 모두 구현·검증됐다.

**구현된 것 (2026-08-06)**

- V33이 PostGIS extension, `merchant_store_discovery_profile`과 GiST index를 만들고, 검증되지 않은
  `merchant_store` row가 하나라도 있으면 migration을 중단한다. `merchant_store`에는 검색용 이름,
  geometry 또는 spatial index가 추가되지 않았다.
- `StoreDiscoveryProfilePrecheck`가 PostGIS 설치, 양방향 coverage, non-blank name과
  SRID 4326 point를 startup에서 다시 확인하고 위반 시 애플리케이션 시작을 실패시킨다.
  readiness DOWN으로 숨기지 않는다.
- Merchant `StoreDiscoveryQueryOperations`가 spatial native DTO projection을 제공하고, Discovery는
  Merchant Entity/Repository를 직접 사용하지 않는다. Discovery는 영속 복제본도 동기화 event도
  만들지 않는다.
- `GET /api/v1/stores/nearby`가 raw `ST_DWithin` range filter,
  `floor(ST_Distance * 1_000_000)` micrometer tuple, `(distanceMicrometers, storeId)` keyset과
  ADR-070 HMAC cursor를 사용하고 응답에는 floored integer meter만 노출한다. Runtime OpenAPI로
  승격했다.
- 좌표는 request 범위에서만 쓰이고 응답 body, error detail, metric tag, `AuditRecord`에 남지
  않는다. PostGIS 실패는 fallback 없이 503이다.

**추가 구현 (2026-08-07)**

- Merchant `StoreMenuQueryOperations`가 메뉴·옵션 DTO projection을, Fulfillment
  `PickupSlotQueryOperations`가 잔여 capacity projection을 소유한다. Discovery
  `StoreCatalogController`가 `GET /stores/{storeId}/menus`와
  `GET /stores/{storeId}/pickup-slots`의 HTTP 계약과 응답 투영만 소유하고 owner public Query API만
  호출한다. 두 endpoint를 Runtime OpenAPI로 승격해 target과 operation 집합이 정확히 일치한다.
- 메뉴는 store의 메뉴를 모두 반환하고 `available`에 현재 owner state를 투영한다. 슬롯은 주입된
  Clock 기준 종료되지 않은 것만 `(startsAt, pickupSlotId)` 순서로 반환하며 잔여 capacity는 SQL에서
  0 미만이 되지 않는다. 응답은 예약·가격 보장이 아니다.
- Store 존재 확인은 catalogue owner인 Merchant가 수행한다. 없는 Store는 404, 정상적인 빈 카탈로그는
  200, 영속 실패는 503이며 실패를 404나 빈 목록으로 바꾸지 않는다.
- statement counting 회귀 테스트가 메뉴 2개·슬롯 1개 statement를 카탈로그 규모와 무관하게 고정한다.

**검증 상태 (2026-08-07)**

- 통과: `./gradlew test --tests '*Discovery*' --tests '*Merchant*' --tests '*Fulfillment*'`,
  `./gradlew test --tests '*RuntimeOpenApi*' --tests '*ModularityTests'`,
  `./gradlew clean build -x test`(spotless/compile/assemble), `bash scripts/verify-docs.sh`,
  `git diff --check`.
- 통과: 전체 test class를 10개 단위 chunk로 나눈 실행에서 모든 chunk `BUILD SUCCESSFUL`.
- **미완주:** 단일 `./gradlew clean build`. 이 workstation의 Docker VM 디스크 포화(118G 중 111G
  사용)로 여러 컨테이너가 동시에 살아 있을 때 기동이 실패한다. 저장소 코드 문제가 아니며
  CI(`ubuntu-latest`, native amd64)에서 최종 확인이 필요하다.

**측정**

`scripts/perf/nearby-store-search.sh`가 닫힌 식으로 10,000/100,000 profile dataset을 재현하고
고정 조건에서 실행계획과 200회 반복 latency를 기록한다. 두 규모 모두 GiST bounding-box index
condition을 사용했고 p50은 0.397 ms → 1.850 ms였다. 비교 가능한 기준선이 없으므로 성능 개선을
주장하지 않으며, 컨테이너가 emulation으로 실행된 점과 미측정 항목을
[evidence 문서](../../quality/nearby-store-discovery-performance-evidence.md)에 남겼다.

**남은 작업과 후속 조건**

- 영업시간·휴일 model은 여전히 미정의다. `open`/`pickupAvailable`은 현재 owner state 투영이며
  영업시간 정책이 생기면 재검토한다.
- 픽업 슬롯 조회는 종료된 슬롯을 숨기지만 `PickupReservationOperations.reserve`는 슬롯 시간을
  검증하지 않는다. read와 write 범위 차이는 MD-2026-010에 기록했고, 쓰기 경로까지 좁히려면 별도
  제품 결정이 필요하다.
- 각 target environment의 PostGIS privilege와 empty/exact verified Store profile inventory는 계속
  fail-closed release gate이며, gate 실패는 새 제품 결정을 요구하는 모호성이 아니라 정해진 배포
  중단 결과다.

## Revision Notes

- 2026-08-01: BR-28/ADR-020과 Discovery OpenAPI에 대응하는 누락 ExecPlan을 최초 작성.
- 2026-08-01: HMAC cursor key rotation, failure and common page-bound contract를 ADR-070으로 고정.
- 2026-08-01: 10km radius, raw range predicate와 micrometer cursor tuple, decimal filter normalization을
  ADR-070 amendment와 signed-cursor foundation으로 고정했다.
- 2026-08-01: 별도 Merchant StoreDiscoveryProfile, PostgreSQL 17/PostGIS 3.5, exact coverage
  preflight/startup gate와 no-replica Query API boundary를 확정했다.
- 2026-08-06: completed signed-cursor dependency와 이미 true인 readiness metadata에 맞춰
  Outcomes의 조건부 표현을 정정했다. 구현 범위는 바꾸지 않았다.
- 2026-08-06: Milestone 1~3 구현 결과, PostGIS image architecture와 좌표 error-echo 발견,
  raw-string parameter binding·JDBC projection·공통 container image 결정을 기록했다.
  메뉴·슬롯 endpoint와 latency baseline이 남아 plan은 `ACTIVE`를 유지했다.
- 2026-08-07: Milestone 4~5를 완료했다. 메뉴·픽업 슬롯 owner projection과 Discovery catalogue
  endpoint, statement-count 회귀, 재현 가능한 두 규모 measurement를 추가하고 plan을 `COMPLETED`로
  옮겼다.
- 2026-08-08 (post-merge review remediation): 아래 "남은 작업과 후속 조건"의 첫 항목이던
  read/write 창 불일치가 제품 결정으로 해소됐다. 예약 가능 창은 `startsAt > now`로 확정하고
  쓰기 경로에 검증을 추가했으며, 조회 predicate를 `ends_at > now`에서 `starts_at > now`로 맞췄다.
  잔여 capacity의 `GREATEST(..., 0)` clamp도 제거해 손상된 counter가 503으로 드러나게 했다.
  같은 결정에서 카탈로그 조회의 나머지 경계도 닫았다. 슬롯 목록은 7일 horizon,
  `acceptingOrders && pickupEnabled`가 아닌 매장은 빈 목록, 메뉴는 매장당 1,000개·옵션 5,000개
  published bound를 넘으면 잘린 목록 대신 503이다. 결정은
  [ADR-076](../../adr/ADR-076-store-catalog-read-contract.md)과 BR-05 Slot Reservation Window
  Amendment에 기록했고 MD-2026-010은 `Superseded`다. 이 항목 위의 서술은 그 시점의 기록이므로
  덮어쓰지 않는다.
