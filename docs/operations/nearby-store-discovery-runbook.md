# Nearby Store Discovery Runbook

`GET /api/v1/stores/nearby`, `GET /api/v1/stores/{storeId}/menus`,
`GET /api/v1/stores/{storeId}/pickup-slots`의 배포 전 preflight, 시작 실패 진단과 장애 대응을
다룬다.

관련 결정: [BR-28](../product/business-policy-decisions.md), [ADR-020](../adr/ADR-020-nearby-location-privacy.md),
[ADR-070](../adr/ADR-070-signed-cursor-and-pagination-contract.md),
[ExecPlan](../exec-plans/completed/nearby-store-discovery.md).

## 1. 배포 전 preflight

V33을 적용하기 전에 target 환경에서 다음을 순서대로 확인한다. 하나라도 실패하면 배포를
중단한다. 추정 값이나 placeholder로 진행하지 않는다.

1. **PostGIS 사용 가능 여부와 권한**

   ```sql
   SELECT default_version FROM pg_available_extensions WHERE name = 'postgis';
   SELECT extversion FROM pg_extension WHERE extname = 'postgis';
   ```

   extension이 이미 설치돼 있으면 그대로 두고, 없으면 migration을 실행할 role이
   `CREATE EXTENSION`을 수행할 수 있어야 한다. 권한이 없으면 DBA가 먼저 설치한 뒤 배포한다.
   V33의 `CREATE EXTENSION IF NOT EXISTS postgis`는 두 경우 모두에서 안전하다.

2. **Store inventory**

   ```sql
   SELECT count(*) FROM merchant_store;
   ```

   - `0`이면 empty migration path다. 그대로 배포한다.
   - `0`이 아니면, 모든 store에 대해 owner가 검증한 non-blank 공개 매장명과 좌표 dataset이
     있어야 하고 store ID coverage가 정확히 일치해야 한다. 그 dataset을 같은 release의
     migration에 포함시켜 profile을 함께 insert한다. 하나라도 미해결이면 배포하지 않는다.
     placeholder 이름, `(0,0)`, 임의 좌표, 메뉴 이름, 주문 이력, 외부 geocoder로 채우지 않는다.

3. **Cursor key ring**

   `beanflow.pagination.cursor-hmac`의 active key가 설정돼 있어야 한다. 없거나 malformed이면
   애플리케이션이 시작되지 않는다. 이는 nearby 전용 설정이 아니라 공통 pagination 요구사항이다.

## 2. Migration 실패

| 증상 | 원인 | 조치 |
|---|---|---|
| `must be owner of database` 또는 `permission denied to create extension "postgis"` | migration role에 extension 생성 권한이 없다 | DBA가 대상 database에 PostGIS를 설치한 뒤 재실행한다. migration에서 extension 요구를 제거하지 않는다 |
| `Nearby discovery migration found N merchant_store row(s) without a verified StoreDiscoveryProfile` | 검증된 profile source 없이 store가 존재한다 | 배포를 중단한다. owner가 검증한 dataset을 같은 release migration에 추가하거나, 해당 환경에서 nearby 배포를 보류한다 |
| `type "geography" does not exist` | extension이 다른 schema에 설치돼 `search_path`에 없다 | extension schema를 `search_path`에 포함하거나 `public`에 설치한다 |

V33은 실패 시 전체가 rollback된다. 부분 적용 상태로 서비스를 시작하지 않는다.

## 3. Startup 실패

`StoreDiscoveryProfilePrecheck`는 애플리케이션 시작 시 실행되며 실패하면 프로세스가 종료된다.
readiness만 DOWN으로 두고 계속 실행하지 않는다.

| 메시지 | 의미 | 조치 |
|---|---|---|
| `Store discovery requires the PostGIS extension` | extension이 제거됐거나 database가 교체됐다 | extension을 복구한 뒤 재시작한다. nearby endpoint만 비활성화하는 우회는 없다 |
| `N store(s) without a profile` | store가 profile 없이 생성됐다 | 검증된 profile을 추가하거나 해당 store를 제거한다. 임의 좌표를 넣지 않는다 |
| `N orphaned profile(s)` | store 없이 profile row가 남았다 | 원인(수동 삭제, FK 우회)을 확인하고 고아 row를 제거한다 |
| `N invalid profile(s)` | blank name, non-point geometry, 잘못된 SRID 또는 invalid geometry | owner source에서 값을 정정한다 |

`beanflow.discovery.profile.precheck.count{outcome}`의 `EMPTY`/`VERIFIED`/`UNRESOLVED`와
`beanflow.discovery.profile.missing.count`로 결과를 확인한다.

## 4. 런타임 장애

| 증상 | 확인 | 조치 |
|---|---|---|
| `503 DEPENDENCY_UNAVAILABLE` 급증 | `beanflow.discovery.spatial.failure{reason=QUERY_FAILED}` | PostGIS extension, `idx_store_discovery_profile_location` index, connection pool과 DB 상태를 확인한다. 빈 결과나 애플리케이션 거리 계산으로 대체하지 않는다 |
| `503`이지만 `reason=TRANSACTION_FAILED` | read transaction commit 실패 | DB 가용성과 pool 고갈을 확인한다 |
| `400 INVALID_REQUEST` 급증 | `beanflow.discovery.nearby.count{outcome=INVALID_INPUT}` | client가 계약 밖 좌표·radius·limit·cursor를 보내고 있다. cursor 만료(24시간)와 key rotation 여부를 함께 확인한다 |
| 검색 지연 증가 | `beanflow.discovery.nearby.latency` | GiST index 존재와 `ANALYZE` 최신성을 확인하고 [query plan evidence](../quality/nearby-store-discovery-performance-evidence.md)와 같은 조건으로 재측정한다 |

## 5. 매장 메뉴·픽업 슬롯 조회

`GET /api/v1/stores/{storeId}/menus`와 `GET /api/v1/stores/{storeId}/pickup-slots`는 owner state를
그대로 투영하는 read-only endpoint다. write, event, AuditRecord를 만들지 않는다.

| 증상 | 의미 | 조치 |
|---|---|---|
| `404 RESOURCE_NOT_FOUND` | 해당 Store가 없다 | client가 유효하지 않은 storeId를 쓰고 있다. Store 삭제 여부를 확인한다 |
| `200`인데 `items`가 비어 있음 | 해당 Store에 메뉴가 없거나, 예약 가능한 슬롯이 없거나, Store가 `accepting_orders`/`pickup_enabled` 중 하나라도 false다 | 정상 응답이다. 장애로 오해하지 않는다. 슬롯 목록이면 `merchant_store`의 두 flag를 먼저 확인한다 |
| `503 DEPENDENCY_UNAVAILABLE` | `merchant_menu`/`merchant_menu_option` 또는 `fulfillment_pickup_slot` 읽기 실패 | DB 상태와 connection pool을 확인한다. 빈 목록이나 404로 대체하지 않는다 |
| 슬롯이 보이지 않는다는 문의 | 조회는 `starts_at > now`인 슬롯만 반환한다. 이미 시작한 슬롯은 예약할 수 없으므로 목록에도 없다(ADR-076) | 슬롯 시간대를 확인한다. 진행 중인 슬롯을 다시 보이게 하려면 슬롯 시작 시각 자체를 조정해야 한다 |
| 주문 생성이 `409 ORDER_STATE_CONFLICT`, message가 slot started | 선택한 슬롯이 결제 전에 시작했다 | 정상 동작이다. 고객에게 다른 슬롯 선택을 안내한다. 예약 수·확정 수는 바뀌지 않았다 |
| 잔여 capacity가 조회 직후 달라짐 | 동시 예약이 반영된 정상 동작 | 응답은 예약 보장이 아니다. 확정은 슬롯 row를 잠그는 예약 API가 수행한다 |
| `503`인데 DB는 정상 | 잔여 capacity가 음수로 계산됐다. `reserved_count + confirmed_count > capacity`인 손상된 counter다 | 값을 clamp하지 않는다. 해당 `fulfillment_pickup_slot` row를 확인하고 owner counter를 바로잡는다 |

`beanflow.discovery.store_catalog.read.count{operation,outcome}`으로 `MENUS`/`PICKUP_SLOTS`의
`SUCCEEDED`/`NOT_FOUND`/`DEPENDENCY_UNAVAILABLE`를 관측한다. store ID는 tag로 쓰지 않는다.

**조회 창과 예약 창 (2026-08-08, [ADR-076](../adr/ADR-076-pickup-slot-reservation-window.md)):**
두 창은 정확히 같다. `PickupReservationOperations.reserve`가 슬롯 row lock 안에서
`startsAt > now`를 검증하므로, 목록을 거치지 않고 과거 슬롯 ID를 직접 보내도 `ORDER_STATE_CONFLICT`
로 거절된다. 창이 열려 있을 때 수락된 예약의 같은 source 재시도는 창이 닫힌 뒤에도 기존 예약을
반환하므로, 결제 재시도가 시간 때문에 실패하지 않는다. `accepting_orders` 또는 `pickup_enabled`가
false인 매장은 슬롯이 있어도 빈 목록을 반환한다. 그 매장의 슬롯은 주문 생성에서 전부 거절되므로
목록에 넣으면 같은 불변식이 깨진다. 메뉴 조회는 이 flag의 영향을 받지 않는다.

## 6. 조사 시 금지 사항

- 원본 고객 좌표는 어디에도 남지 않는다. 조사를 위해 좌표를 log, trace, metric tag,
  `AuditRecord` 또는 임시 table에 복사하지 않는다.
- cursor payload, key ID와 filter hash를 log나 metric tag에 기록하지 않는다.
- PostGIS 장애를 우회하기 위해 애플리케이션 Haversine 계산, in-memory index 또는 cache를
  임시로라도 활성화하지 않는다. 장애는 503으로 노출한 채 원인을 복구한다.

## 7. Key rotation

새 key를 verification ring에 추가하고 active key로 전환한 뒤, 이전 key를 최소 24시간
(nearby cursor 수명) 유지한 다음 제거한다. 제거 시점 이후의 이전 key cursor는 `400`이며 이는
정상 동작이다.
