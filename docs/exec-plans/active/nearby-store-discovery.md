# 근접 매장 Discovery 조회를 위치정보 보존 없이 제공한다

> **Status:** `ACTIVE`
> **Depends-On:** —
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
- Merchant에는 최소 Store/menu/configuration, Fulfillment에는 PickupSlot이 있지만 Store 이름,
  검색 geometry, Discovery module과 세 HTTP read endpoint는 없다.
- `StoreEntity.acceptingOrders`와 `pickupEnabled`가 현재 owner availability다. 미결정 영업시간
  model을 추가하지 않으며 `open=acceptingOrders`, `pickupAvailable=acceptingOrders && pickupEnabled`
  로 투영한다.

## Definitions

- **Precise query coordinate:** 한 nearby request에만 쓰고 어떤 durable record에도 넣지 않는 입력이다.
- **Store geography:** Merchant가 소유하고 Discovery가 읽는 `geography(Point, 4326)` 매장 위치다.
- **Discovery profile:** Store ID/name/geography/acceptingOrders/pickupEnabled의 Merchant-owned read input이다.
- **Distance cursor:** ADR-070의 `v1.<key-id>.<payload>.<signature>` HMAC token에
  `(distanceMeters, storeId)` 마지막 tuple과 nearby filter hash를 bound한 page token이다.
- **Pickup-capable:** `open`과 `pickupAvailable`이 모두 true인 매장이다.

## Scope

### In Scope

- Discovery Modulith module, Merchant/Fulfillment read Application API와 DTO projection boundary
- Store discovery profile migration, PostGIS extension/geography/GiST index와 existing-row safety gate
- nearby validation, HMAC-signed distance/store-ID cursor, radius filtering, deterministic integer-meter conversion
- 메뉴·픽업 슬롯 read endpoint, coordinate redaction test, PostGIS availability health
- PostgreSQL/PostGIS Testcontainers, API contract, pagination/concurrency/failure validation

### Non-goals

- 고객 위치 이력, 개인화, geofence, background location, coordinate cache
- 영업시간 schema/예약 정책 변경, map/route Provider, local distance fallback
- Merchant profile write UI, external geocoding, 위치 기반 analytics

## Business Rules and Invariants

- latitude는 `[-90,90]`, longitude는 `[-180,180]`, radius는 양의 integer다. invalid input은 400이다.
- raw coordinate와 원본 좌표를 복원할 cursor는 entity, DB query audit, log, trace, metric,
  `AuditRecord`, exception message에 남지 않는다.
- result는 반경 안 pickup-capable Store만 `(distanceMeters ASC, storeId ASC)`로 반환한다.
  cursor는 같은 endpoint/filter/sort contract의 다음 page에만 쓰며 다른 radius, signature/scope
  mismatch 또는 malformed/expired cursor는 400이다. `limit`은 default 20, maximum 100이다.
- Store geometry가 없거나 invalid이면 거리 0/임의 위치로 보완하지 않는다. migration gate가
  deployment를 멈추거나 owner profile이 명시적으로 disabled여야 한다.
- current owner availability만 투영하며 stale cache가 비활성 Store를 가능하다고 보이면 안 된다.

## Architecture and Transaction Boundaries

- `DiscoveryQueryService`는 read-only transaction에서 Merchant discovery profile을 PostGIS native
  projection으로 읽는다. Ordering/Controller는 Merchant repository를 직접 호출하지 않는다.
- Store profile write/migration은 Merchant owner, nearby search는 별도 read-only transaction이다.
  메뉴 endpoint는 Merchant projection, pickup slot endpoint는 Fulfillment projection을 호출한다.
- coordinate는 controller binding 뒤 query value object로만 전달하고 response/correlation/log context,
  exception details에서 제외한다. 외부 map/geocode call은 없다.
- extension/query/DB failure는 503으로 매핑한다. fallback repository, in-memory index, local Map은 없다.
- common cursor codec은 required HMAC key ring configuration으로 생성한다. missing/malformed active
  key는 unsigned/local default cursor로 대체하지 않고 application startup을 실패시킨다.

## Alternatives Considered

- Haversine application 계산: spatial index를 우회하고 PostGIS 장애를 숨기므로 제외한다.
- raw coordinate log/trace: BR-28/ADR-020과 충돌한다.
- query-coordinate cache: cache key/telemetry에 개인 위치가 남을 위험이 있어 제외한다.
- 영업시간 Aggregate 동시 도입: owner model을 추정하는 별도 product decision이므로 제외한다.

## Failure Semantics

- extension 미설치, geometry query/index failure, DB timeout은 `DEPENDENCY_UNAVAILABLE` 503이다.
  빈 `items` 200 또는 계산 불가능 distance로 대체하지 않는다.
- invalid coordinate/radius/cursor는 `INVALID_REQUEST` 400이며 spatial query를 실행하지 않는다.
- missing Store menu/slot query는 404이며 DB failure를 404로 바꾸지 않는다.
- raw coordinate가 log/trace/metric/Audit scan에서 발견되면 release blocker다. 원본 좌표를
  조사용 Audit에 복사하지 않는다.

## Data and Migration

forward migration은 PostGIS extension availability를 검증하고 `merchant_store`에 non-sensitive
`name`, `location geography(Point,4326)`, GiST index를 추가한다. existing row에는 verified Merchant
source의 name/location만 채운다. source 없이 남으면 placeholder name, `(0,0)`, default location을
쓰지 않고 endpoint activation을 중단한다.

Store geography만 저장하고 customer coordinate table/column/audit은 만들지 않는다. cursor는 DB에
저장하지 않고 ADR-070 common HMAC codec의 filter-bound boundary tuple로 전달한다. raw coordinate와
radius는 token payload가 아니라 filter hash input이며 old verification key는 24시간 rotation window 동안만
허용한다. Testcontainers는 PostGIS image/extension을 명시해 일반 PostgreSQL test가 spatial query를 local
fallback으로 통과하지 않게 한다.

## API and Event Contracts

- `GET /stores/nearby`는 OpenAPI query와 `NearbyStorePage`를 그대로 구현한다. `distanceMeters`는
  DB distance를 deterministic integer meter로 변환하고 sort/cursor는 같은 raw order key를 사용한다.
  cursor는 endpoint/filter hash/signature/24시간 expiry를 검증하고 limit omission은 20, 100 초과는
  400으로 처리한다.
- 메뉴와 slot endpoint는 JPA Entity가 아닌 current owner DTO projection을 반환한다. availability/capacity는
  response 시점 owner state이며 write graph를 노출하지 않는다.
- customer location은 event payload, Analytics, Notification, Audit에 발행하지 않는다. Discovery read는
  persistent domain event를 만들지 않는다.
- raw coordinate echo, debug source, profile private field는 schema에 추가하지 않는다.

## Milestones

1. PostGIS Testcontainers/extension migration gate와 existing Store profile inventory를 검증한다.
2. Store name/geography persistence, GiST index와 Merchant owner read API를 만든다.
3. nearby validation, spatial projection, cursor, 503 mapping과 privacy redaction을 하나의 vertical slice로 완성한다.
4. 메뉴와 pickup slot read projection/API를 추가하고 availability/capacity contract를 고정한다.
5. data-scale query plan/latency baseline, failure behavior, runbook/documentation evidence를 기록한다.

## Required Tests

- coordinate/radius/cursor bounds, HMAC tamper/unknown key/expiry/cross-filter cursor, omitted/1/100/101
  limit, empty/single/multi-page distance tie order
- radius boundary, same-distance store ID tie, disabled/open/pickup-disabled Store filtering
- Store name/geography migration: empty, verified, unresolved gate; GiST execution plan capture
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

- BR-01/28, ADR-020/070 implementation evidence
- context map, invariants, transaction boundaries, failure semantics, ubiquitous language
- OpenAPI/API conventions/error catalog/authorization matrix, test strategy, measurement plan,
  Discovery runbook, quality evidence map and this ExecPlan

## Progress

- [ ] PostGIS/container and existing profile readiness gate
- [ ] Merchant profile migration and owner API
- [ ] nearby query, cursor and privacy redaction
- [ ] menu/pickup read projections
- [ ] failure/measurement/runbook evidence
- [ ] full validation

## Surprises & Discoveries

- 2026-08-01: ADR-020/OpenAPI는 nearby contract를 제공하지만 current Store schema에는 search geometry와
  public name이 없다. unsafe default coordinate/name을 막는 migration gate가 필요하다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted existing | coordinate request-only, PostGIS failure 503 | 개인 위치 최소 보존과 false-success 방지 | BR-28, ADR-020 |
| 2026-08-01 | Plan interpretation | `open=acceptingOrders`, `pickupAvailable=acceptingOrders && pickupEnabled` | 존재 owner state만 투영 | current `merchant_store`, OpenAPI |
| 2026-08-01 | Plan boundary | menu/slot read projection을 Discovery slice에 포함 | 공개 Discovery contract 완결, write owner 불변 | OpenAPI, Context Map |
| 2026-08-01 | Accepted | nearby cursor는 versioned HMAC, endpoint/filter binding, 24시간 expiry와 20/100 limit을 사용 | radius/scope tamper와 unbounded page 방지 | ADR-070 |

## Outcomes & Retrospective

미구현 상태다. PostGIS readiness와 verified Store profile inventory가 통과한 경우에만 endpoint를
활성화한다. 완료 시 coordinate non-retention evidence, spatial failure behavior, 같은 조건의
query measurement를 actual value로 기록한다.

## Revision Notes

- 2026-08-01: BR-28/ADR-020과 Discovery OpenAPI에 대응하는 누락 ExecPlan을 최초 작성.
- 2026-08-01: HMAC cursor key rotation, failure and common page-bound contract를 ADR-070으로 고정.
