# 점주 거래 카탈로그와 주문 정책 완성

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-merchant-screen-contract-completion.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`와
[ADR-118](../../adr/ADR-118-merchant-transactional-catalog-lifecycle.md)을 따른다. 기존 고객·점주 화면
계약 스택의 quote serialization review finding을 실제 production writer와 함께 해소하고, 점주
`메뉴·가격` 화면과 Store 주문 정책 화면을 구현한다.

선행 고객·점주 화면 계약 계획은 review finding 보완과 combined local/remote 검증을 마치고 completed
path로 이동했다. ADR-118과 BR-52가 권한·수명주기·직렬화·멱등성·상한을 확정했으므로 이 계획은
`Implementation-Ready: true`다. 실제 구현은 migration writer lease를 획득한 뒤 Milestone 순서대로
진행하며, 각 수직 슬라이스의 production source와 계약은 해당 검증을 같은 PR에서 완료한다.

## Purpose / Big Picture

완료 후 ACTIVE same-store `OWNER | STAFF`는 `/store/catalog`에서 다음을 수행할 수 있다.

```text
매장 선택
  -> 주문 접수/픽업 정책 조회·변경
  -> active/archived 메뉴 목록 조회
  -> 메뉴·옵션·선택 조합·재고 요구량 생성/전체 교체
  -> 메뉴 보관
  -> 검색/고객 메뉴에 원자적으로 반영
```

고객이 quote를 확인한 뒤 점주가 가격·옵션·판매 상태 또는 Store 주문 정책을 바꾸면 최종 Order는
두 transaction의 실제 lock 순서에 따라 이전 상태로 완결되거나 `ORDER_QUOTE_STALE`로 실패한다. 이미지,
메뉴 설명·카테고리 같은 표시 전용 변경은 거래가 같으므로 fingerprint를 바꾸지 않는다.

이 plan은 reviewer용 선형 stack을 만든다. 첫 PR은 docs-only이며, 이후 각 PR은 바로 이전 PR head를
base로 한다. merge, force-push, 기존 PR base 변경, 임의 close를 하지 않는다. 각 구현 PR은 자신의
migration, backend command/read, OpenAPI와 generated schema, 소비 UI, 핵심 테스트와 Storybook state를
함께 포함한다.

## Current State

1. `merchant_store`에는 `accepting_orders`, `pickup_enabled`, coarse JPA `version`이 있지만 production
   authoring API가 없다.
2. `merchant_menu`, `merchant_menu_option`, `merchant_menu_configuration`, requirement table은 quote와
   customer catalogue에서 읽지만 migration/seed/direct DML 외 writer가 없다.
3. `Menu.version`은 image와 display content에도 증가하고 Option/requirement 변경은 이를 자동 증가시키지
   않는다. `Store.version`도 image pointer 변경에 증가한다.
4. ADR-116 기반 final quote 구현은 Merchant의 Menu, Store display, StoreSettlementTerms를 일반 SELECT로
   읽고 그 snapshot을 Order workflow가 재사용한다. 현재 production writer가 없어 API로 돈 관련 race를
   재현하기 어렵지만, Accepted serialization contract는 충족하지 못한다.
5. ADR-103은 Menu name search term을 동기 색인으로 소유하지만 BR-47은 Store/Menu write API 부재 때문에
   direct DML 뒤 수동 rebuild가 필요하다고 기록한다.
6. public `GET /stores/{storeId}/menus`에는 Store당 Menu 1,000개·Option 5,000개 complete-response bound가
   있다. write path에는 이 상한이 없다.
7. 점주 frontend에는 `/store` 주문보드, 환불, 정산, 이의제기, 지역 route가 있지만 메뉴·가격 route가
   없다. `useMerchantStores("ANY")`, `StoreSelector`, merchant Session/CSRF client는 재사용 가능하다.
8. 2026-08-27 running `beanflow_storybook` MCP에서 component inventory, Store console page,
   `FeedbackState`, `Button` 문서와 story 작성 지침을 확인했다. UI는 기존 Store page composition을
   `COMPOSE`하고 두 공통 component를 `REUSE`하며 새 병렬 primitive를 만들지 않는다.

## Definitions

- **거래 카탈로그:** 새 Order의 메뉴/옵션 이름·가격·판매 가능성, 선택 조합과 sellable-unit 요구량을
  결정하는 Merchant owner state다. display category/description과 image pointer는 포함하지 않는다.
- **Store 주문 정책:** `acceptingOrders`와 `pickupEnabled` 두 flag와 그 optimistic version이다.
- **commerce root lock:** `merchant_store` row의 shared/exclusive PostgreSQL lock이다. final Order는 shared,
  Store/Menu 거래 writer는 exclusive mode를 사용한다.
- **trade version:** Menu의 거래 의미 전체에 대한 optimistic version이다. child Option/Configuration/
  requirement 변화도 parent Menu `tradeVersion`을 한 번 증가시킨다.
- **보관(archive):** current customer catalogue, search와 새 quote에서 제외하되 DB row와 Audit를 유지하는
  terminal v1 lifecycle transition이다. hard delete나 restore가 아니다.
- **full replacement:** 요청이 aggregate의 원하는 전체 거래 상태를 제출하고 누락된 active child를 archive
  하는 command다. 부분 patch나 best-effort child 저장이 아니다.

## Scope

### In Scope

- ADR-118, BR-52, ADR-116/076/103 amendment와 authorization/capability 문서
- Store ordering policy authenticated GET/PUT, version, Audit, idempotency와 Store console section
- Merchant authoring용 paged Menu list와 individual aggregate read
- Menu aggregate create/full-replace/archive
- Option/Configuration/requirement lifecycle, validation, bounds와 DB constraints
- `ordering_policy_version`, `trade_version`, archived timestamp/status, command response ledger와 필요한 index
- `MENU_NAME` 검색 색인의 같은-transaction 교체
- final Order의 Merchant shared lock, quote/final read port 분리와 fingerprint decoupling
- target/runtime OpenAPI, generated `frontend/src/api/schema.d.ts`, API contract tests
- `/store/catalog` UI, canonical Storybook page states, interaction/a11y tests
- 실제 PostgreSQL writer-vs-Order 동시성 테스트와 combined stack regression

### Non-goals

- Store 자체 생성·폐점, 브랜드·지역·고객 표시 profile·이미지 authoring 변경
- Menu/Option hard delete 또는 archived item restore
- 재고 수량·sellable unit lifecycle authoring UI. 이 plan은 기존 Inventory 식별자를 requirement로 참조한다.
- coupon, point policy, pickup slot 생성/수정 command
- customer catalogue pagination이나 ADR-076 published bound 변경
- STAFF 금액 상한, OWNER 승인, 이중 승인 또는 scheduled publication
- 가격 보장 quote, reservation token, cart hold 또는 주문 생성 idempotency 의미 변경
- 외부 POS 동기화, event-driven catalogue propagation, Elasticsearch 또는 새 production dependency
- UI redesign exploration. 현재 Store console visual language와 documented component를 재사용/조합한다.

## Business Rules and Invariants

1. 모든 authoring read/mutation은 ACTIVE same-store `OWNER | STAFF`를 실행 시점에 다시 확인한다. revoked와
   cross-store는 frontend state로 우회할 수 없다.
2. `basePriceKrw`와 `additionalPriceKrw`는 KRW 정수이며 0 이상이다. line 합계와 overflow 검증은 기존
   BR-02/Order domain을 재사용한다.
3. Menu name과 Option name은 trim 후 1..200자이고 control character를 허용하지 않는다. name normalization은
   search index와 API response에서 동일하다.
4. Menu가 `available=true`이면 active Configuration이 하나 이상이어야 한다. Configuration은 같은 Menu의
   active Option만 중복 없이 참조하고 option ID 문자열 오름차순 canonical key가 Menu 안에서 unique다.
5. active Configuration은 requirement를 1..50개 가지며 각 `quantityPerLineUnit > 0`, sellableUnitId가
   Configuration 안에서 unique다.
6. Store당 active Menu 1,000개, Store당 active Option 5,000개, Menu당 active Option 100개, Menu당 active
   Configuration 500개를 넘는 desired state는 owner 변경 전에 400으로 거절한다.
7. full replacement에서 누락된 child는 archive한다. DB row를 delete하지 않으며 archived child를 active
   Configuration이 참조할 수 없다.
8. archive Menu는 모든 active child를 함께 archive하고 customer catalogue/search/quote에서 제외한다.
   과거 Order snapshot은 바꾸거나 current catalogue로 재계산하지 않는다.
9. 정규화된 desired state가 current와 같으면 version/updatedAt/Audit/search index를 바꾸지 않는다.
10. 실제 거래 변경은 Menu `tradeVersion`을 command당 한 번만 증가시킨다. 이미지, display category/
    description과 Store customer display는 거래 version을 건드리지 않는다.
11. owner state, command response, Audit와 검색 색인은 하나의 local transaction에서 전부 commit/rollback한다.
    transaction 안에 Provider 호출이 없다.
12. 같은 actor·operation·Idempotency-Key·payload는 최초 terminal response를 재생한다. key가 같고 payload가
    다르면 409이며 어떤 owner write도 하지 않는다.
13. final Order는 Merchant shared Store lock을 먼저 획득하고 commit/rollback까지 유지한다. Merchant writer는
    같은 Store exclusive lock을 먼저 획득한다. 어떤 경로도 Store A lock 뒤 Store B lock을 잡지 않는다.
14. writer-first이면 이전 fingerprint가 stale이다. Order-first이면 writer가 Order commit을 기다린다. 두 상태의
    값을 섞은 Order나 partial owner write는 허용하지 않는다.
15. quote fingerprint는 거래 canonical value와 거래 version만 사용한다. 이미지·설명·카테고리 변경은 같고,
    price/name/availability/option/config/requirement/Store policy 변경은 다르다.

## Architecture and Transaction Boundaries

### Modules and aggregates

- **Identity:** catalogue 전용 `StoreAccessOperations`가 actor의 대상 Store membership row를 shared lock으로
  읽고 ACTIVE `OWNER | STAFF`와 실제 role을 반환한다. membership 부재는 404, inactive·revoked·role 부족은
  403이며 Identity는 Merchant repository를 직접 읽지 않는다.
- **Merchant:** Store commerce root, Menu aggregate와 child lifecycle, command idempotency, Audit orchestration,
  authoring projection을 소유한다.
- **Discovery:** 기존 `StoreSearchIndexOperations` 구현으로 `MENU_NAME` term을 원자 교체한다. Merchant가
  Discovery repository를 직접 호출하지 않는다.
- **Ordering:** quote read와 final-order locked read를 명시적으로 분리한다. 최종 workflow는 locked snapshot을
  그대로 사용하되 lock은 transaction 종료까지 유지된다.
- **Frontend Store surface:** `/store/catalog`가 authenticated authoring projection만 사용한다. customer public
  catalogue나 client 계산값을 mutation authority로 사용하지 않는다.

### Transaction S — Store ordering policy

```text
CSRF + Session actor
  -> ACTIVE OWNER|STAFF membership FOR SHARE
  -> merchant_store FOR UPDATE
  -> idempotency replay/conflict
  -> expected orderingPolicyVersion
  -> desired-state validation/no-op
  -> flags + version + updatedAt
  -> Audit + terminal response
  -> commit
```

Search term은 Store policy flag를 source로 사용하지 않으므로 이 transaction에서 색인 문자열을 바꾸지 않는다.
customer search의 ordering/pickup filter는 current Store policy projection으로 즉시 달라진다.

### Transaction M — Menu create/replace/archive

```text
CSRF + Session actor
  -> ACTIVE OWNER|STAFF membership FOR SHARE
  -> merchant_store FOR UPDATE
  -> idempotency replay/conflict
  -> target Menu ownership/lifecycle + expected tradeVersion
  -> canonical desired aggregate + 1,000/5,000/100/500/50 bound
  -> Menu/Option/Configuration/requirement write or no-op
  -> MENU_NAME search term replacement
  -> Audit + terminal response
  -> commit
```

create request의 Menu, Option, Configuration UUID는 client가 cryptographically random UUID로 제출한다. ID는
권한이나 secret이 아니며 server는 request 내부 uniqueness, Store scope와 기존 global collision을 검증한다.
collision은 다른 resource 존재를 노출하지 않는 409다. client-generated ID 덕분에 Configuration이 같은
atomic request의 새 Option을 명시적으로 참조할 수 있다. `Idempotency-Key`가 lost-response replay를 보호한다.

### Transaction O — final Order Merchant snapshot

```text
기존 order idempotency arbitration
  -> merchant_store FOR SHARE
  -> Store policy/display + requested Menu roots/children + applicable settlement terms read
  -> downstream point policy/pickup/stock/coupon/point lock (기존 안정 순서)
  -> full fingerprint 비교
  -> match: reserve + immutable Order snapshot + terminal response commit
  -> stale: 거래 write rollback, BR-25 terminal FAILED response 별도 저장
```

quote endpoint는 non-locking `inspectForQuote`, final Order는 `lockForOrderCreation` port를 호출한다. boolean
`lock` 분기와 이름만 locked인 일반 read를 남기지 않는다.

### Expected files

- `src/main/resources/db/migration/V<next>__add_store_ordering_policy_authoring.sql`
- `src/main/resources/db/migration/V<next>__create_merchant_transactional_catalog_lifecycle.sql`
- `src/main/kotlin/io/github/kdh949/beanflow/merchant/api/*Catalog*Operations.kt`
- `src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/MerchantPersistence.kt`
- `src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/*Catalog*Service.kt`
- `src/main/kotlin/io/github/kdh949/beanflow/identity/internal/*Catalog*Controller.kt`
- `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderQuoteCoordinator.kt`
- `src/main/kotlin/io/github/kdh949/beanflow/shared/api/StoreSearchIndexOperations.kt` only if an existing operation
  cannot atomically replace `MENU_NAME`; do not create a parallel index owner.
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`, `frontend/src/api/schema.d.ts`
- `frontend/src/features/merchant/StoreCatalogPage.tsx` and focused test/story files
- `frontend/src/router.tsx` and existing navigation composition

Exact class names may follow existing package conventions, but Controller->Repository direct calls, Entity response exposure,
write-model association expansion and new production dependencies are prohibited.

## Alternatives Considered

1. **기존 row update만:** review race는 재현하지만 실제 `메뉴·가격` 수명주기를 완성하지 못해 제외한다.
2. **hard delete CRUD:** 복구·Audit·stale cart 의미가 나빠져 archive를 선택한다.
3. **각 Menu/child lock:** phantom과 lock 순서가 복잡해 Store shared/exclusive commerce root를 선택한다.
4. **모든 final Order의 Store exclusive lock:** 같은 Store 주문을 직렬화하므로 shared Order lock을 선택한다.
5. **coarse JPA version fingerprint:** display/image false stale와 child 변경 누락을 동시에 가져 제외한다.
6. **검색 event/outbox:** 현재 local transaction에서 해결 가능한 일을 eventual consistency와 recovery state로
   늘리므로 기존 동기 index port를 재사용한다.
7. **PATCH child endpoints:** 부분 성공과 조합 중간 상태가 생긴다. Menu aggregate full replacement를 선택한다.

## Failure Semantics

| 상황 | 응답/상태 | durable 결과 |
| --- | --- | --- |
| invalid text/money/duplicate ID/reference/bound | `400 INVALID_REQUEST` | 없음 |
| inactive/revoked/role 부족 | `403 ACCESS_DENIED` | 없음 |
| 다른 Store target 또는 없는 target | `404 RESOURCE_NOT_FOUND` | 없음; 존재 누설 금지 |
| stale expected version | `409 MERCHANT_CONTENT_STALE` | 없음 |
| same key, different payload | `409 IDEMPOTENCY_KEY_REUSED` | 최초 command만 유지 |
| active config가 archived/missing Option 참조 | `409 RESOURCE_STATE_CONFLICT` | 없음 |
| ID collision | generic `409 RESOURCE_STATE_CONFLICT` | 없음; 충돌 owner 비공개 |
| search index/Audit/DB failure | typed `503 DEPENDENCY_UNAVAILABLE` | transaction 전체 rollback |
| final fingerprint mismatch | `409 ORDER_QUOTE_STALE` | 거래 write 0, BR-25 terminal FAILED response만 저장 |
| lock timeout/deadlock victim | typed `503`, retry 가능 | rollback; 성공/no-op으로 위장 금지 |

같은 command의 terminal validation/409 response 저장 여부는 BR-25/ADR-064와 기존 Merchant command ledger
관례에 맞춰 Milestone 0 OpenAPI에서 명시한다. 외부 Provider, fallback cache, in-memory 성공 또는 검색 rebuild
자동 대체를 추가하지 않는다.

## Data and Migration

1. 실행 직전 ADR-072 repository-wide migration-writer lease를 획득하고 exact predecessor head의 마지막
   Flyway 번호를 다시 읽는다. 이 문서에서 V69 같은 번호를 예약하지 않는다.
2. Store slice migration은 `ordering_policy_version`, `ordering_policy_updated_at`과 purpose-specific command
   ledger/retention index를 추가한다. 기존 Store는 현재 flag를 유지하고 version 0, deterministic timestamp
   policy를 사용한다. 임의의 현재 시각 backfill은 피한다.
3. Menu slice migration은 `trade_version`, `trade_updated_at`, Menu/Option/Configuration의 archive 상태와
   필요한 active uniqueness/index를 추가한다. 기존 row는 ACTIVE, tradeVersion 0으로 backfill한다.
4. normalized option key와 requirement positivity/uniqueness의 기존 constraint를 유지한다. partial unique index가
   필요하면 active row에만 적용한다.
5. 새 FK child/command table이 생기면 모든 PostgreSQL `TRUNCATE` cleaner를 같은 PR에서 갱신한다.
6. migration은 fresh database, pre-migration legacy fixture, checksum/expected-version smoke와 rollback-on-failure
   의미를 검증한다. down migration이나 기존 migration 수정은 하지 않는다.
7. active/archived 데이터 증가율과 cleanup 대상은 command ledger뿐이다. catalogue hard delete retention worker는
   만들지 않는다.

## API and Event Contracts

### Store policy

```text
GET /api/v1/stores/{storeId}/ordering-policy
PUT /api/v1/stores/{storeId}/ordering-policy
  headers: Idempotency-Key, X-BEANFLOW-CSRF
  body: { acceptingOrders, pickupEnabled, expectedVersion }
  response: { storeId, acceptingOrders, pickupEnabled, version, updatedAt }
```

GET도 ACTIVE `OWNER | STAFF` 전용이다. customer response의 `orderingAvailable`은 계속 계산 projection이며
authoring version을 노출하지 않는다.

### Menu authoring

```text
GET /api/v1/stores/{storeId}/menu-catalog?lifecycle=ACTIVE|ARCHIVED&cursor=&limit=
GET /api/v1/stores/{storeId}/menus/{menuId}/trade-content
POST /api/v1/stores/{storeId}/menus
PUT /api/v1/stores/{storeId}/menus/{menuId}/trade-content
POST /api/v1/stores/{storeId}/menus/{menuId}/archive
```

- list 기본 lifecycle은 ACTIVE, 기본 limit 20, 최대 50이다. stable tuple은 `(name, menuId)`이고 signed cursor는
  actor/store/lifecycle/limit scope에 묶는다. active와 archived를 한 무경계 응답으로 합치지 않는다.
- create/full-replace request는 Menu ID, name, basePriceKrw, available, Option ID/name/additionalPriceKrw/
  available, Configuration ID/selectedOptionIds/available/requirements를 포함한다.
- replace/archive는 `expectedVersion`; 모든 mutation은 `Idempotency-Key`와 CSRF를 요구한다.
- response는 normalized full representation, lifecycle, trade `version`, `updatedAt`을 반환한다.
- display category/description과 image는 기존 별도 endpoint/version을 유지하고 trade payload에 넣지 않는다.
- 새 event를 발행하지 않는다. 검색 색인은 같은 local transaction의 port 호출이다.

OpenAPI 원본과 runtime parity, generated TypeScript는 각 계약 PR의 같은 commit에 둔다. deployed client가
없다는 전제라도 기존 public customer catalogue field를 breaking 변경하지 않는다.

## Milestones

### Milestone 0 — docs-only 거래 카탈로그 결정 PR

**Parent/base:** current reviewed customer/merchant combined head. main 또는 중간 PR head를 추측하지 않고
실행 시 exact SHA와 ancestry를 기록한다.

**User value:** 구현 전에 권한, 수명주기, lock, version, idempotency, 상한과 failure 의미를 리뷰할 수 있다.

**Changes:**

- 이 ExecPlan과 ADR-118을 첫 docs-only PR로 올린다.
- `business-policy-decisions.md`에 BR-52를 추가한다: ACTIVE `OWNER | STAFF`, create/replace/archive, no hard
  delete/restore, 100/500/50 상한, Audit/idempotency/search atomicity.
- ADR-116의 final Merchant lock과 fingerprint version material, ADR-076 write bounds, ADR-103 Menu writer
  index atomicity를 amendment한다.
- `docs/adr/README.md`, capability/design map, transaction boundaries, authorization matrix를 갱신한다.
- predecessor review blockers와 이 plan이 해결하는 범위를 구분한다. notification opt-out race와 nearby
  EXPLAIN evidence는 이 PR에 구현하지 않는다.

**Acceptance:** production source/OpenAPI/migration/generated/frontend diff 0, docs validation passed,
`Implementation-Ready`는 dependency와 모든 decision record가 실제 충족될 때만 true.

### Milestone 1 — Store 주문 정책과 Merchant shared lock PR

**Parent/base:** Milestone 0 head.

**User value:** OWNER와 STAFF가 주문 접수·픽업 정책을 안전하게 바꾸고 고객 주문이 동시 변경과 직렬화된다.

**Changes:** Store policy migration, command ledger, authenticated GET/PUT, membership/Audit, OpenAPI/generated
type, `/store/catalog` policy panel과 stories/tests를 한 PR에 둔다. Ordering final path는 Store shared lock을
획득하고 Store coarse version을 fingerprint에서 제거한다.

**Acceptance:** writer-first/Order-first Store policy PostgreSQL concurrency, same-store concurrent Order shared
lock, idempotency/no-op/stale version/rollback, customer ordering projection, frontend type/unit/Storybook/a11y.

### Milestone 2 — Menu 거래 카탈로그 수명주기 PR

**Parent/base:** Milestone 1 head.

**User value:** OWNER와 STAFF가 Menu/Option/Configuration/requirement를 생성·교체·보관하고 고객 검색과
메뉴가 원자적으로 바뀐다.

**Changes:** Menu trade version/archive migration, authoring list/detail/create/replace/archive, bound/DB
constraints, search term atomic update, Audit/idempotency, OpenAPI/generated schema, `/store/catalog` menu list/editor,
archive confirmation과 required stories/tests를 같은 PR에 둔다. final fingerprint는 Menu coarse JPA version을
제거하고 trade version/canonical values를 사용한다.

**Acceptance:** create/update/archive/no-op/replay, bounds, cross-store/revoked, search rollback, archived public
exclusion, display/image fingerprint stability, price/option/config writer concurrency를 PostgreSQL과 API/UI에서 통과.

Milestone 2가 reviewer size를 넘으면 domain boundary를 섞지 않고 `(2a) Menu root+Option`, `(2b)
Configuration+requirement`로 나눈다. 단 2a가 customer-visible `available=true`를 허용하기 전에 2b가 필요한
sellable configuration을 같은 PR에 제공해야 하므로, incomplete catalogue를 2xx로 노출하는 분리는 금지한다.
따라서 기본안은 하나의 Menu aggregate PR이다.

### Milestone 3 — combined regression과 completion evidence PR

**Parent/base:** Milestone 2 head.

**User value:** 거래 카탈로그와 quote/cart/order가 하나의 검증된 흐름으로 동작한다.

**Changes:** 앞 PR의 새 계약 구현을 추가하지 않는다. cross-slice fixture, full regression, performance evidence,
runbook/capability/ExecPlan Outcomes만 갱신한다. predecessor customer/merchant completion evidence가 이 finding을
성공으로 잘못 기록했다면 combined head의 실제 결과로 정정한다.

**Acceptance:** 전체 backend/frontend/OpenAPI/Storybook/docs gate, two-interleaving concurrency suite, migration
smoke, search execution plan과 lock-wait evidence. Provider sandbox/deployment를 실행하지 않았다면 Not run으로
남긴다.

## Required Tests

### Domain/Application

- Store flag normalization/no-op/version
- Menu/Option name, money, duplicate IDs, canonical option set
- available Menu의 configuration/requirement invariant
- full replacement archive propagation
- 1,000/5,000/100/500/50 boundary 직전 성공과 초과 거절
- trade version 1회 증가, display/image 변경 시 불변

### PostgreSQL/Testcontainers

- fresh/legacy migration과 existing row ACTIVE/version 0 backfill
- active uniqueness/check constraints와 FK integrity
- Store policy writer-first -> stale; Order-first -> writer wait then commit
- Menu price/Option/Configuration/requirement writer-first -> stale
- Order-first -> writer가 Order commit 전에 owner row/index를 바꾸지 못함
- 같은 Store의 두 Order shared lock 동시성; 다른 Store 독립성
- command/Audit/search/owner atomic commit/rollback
- idempotent concurrent same-key exact replay와 changed payload conflict
- archived catalogue public exclusion과 immutable Order snapshot 유지
- 모든 PostgreSQL cleaner가 새 FK table을 처리

### API/Security

- OWNER/STAFF success, revoked/inactive/cross-store/anonymous/Customer/Operations denial
- CSRF required, Bearer+Session ambiguity 기존 policy 유지
- authoring GET/list cursor binding, invalid cursor/filter/limit
- validation 400, not-found 404 hiding, version/idempotency/state 409, dependency 503
- Entity, Audit detail, internal version, search internals을 customer API에 노출하지 않음
- target/runtime OpenAPI와 controller response parity

### Frontend/Storybook

- loading, no-store, empty catalogue, active list, archived list
- create draft, validation error, saving, saved/replayed
- version conflict는 server current reload/review를 요구하고 자동 overwrite하지 않음
- dependency error, revoked/permission loss, idempotency-key reused
- Store policy on/off combinations과 Menu archive confirmation/focus return
- long Korean name, 0원/large KRW, 100-option boundary summary, mobile/desktop
- keyboard labels, fieldset/legend, validation association, async status announcement, destructive archive focus

UI 구현 전 반드시 running Storybook MCP에서 `list-all-documentation`, candidate `get-documentation`, story
instructions를 호출하고 `REUSE | COMPOSE | EXTEND | NEW`를 work report에 기록한다. 현재 세션의 파일
inspection만으로 component prop을 추정하지 않는다.

## Validation Commands

실제 package task 이름은 branch의 `package.json`/Gradle inventory로 재확인한다. 기본 검증은 다음이다.

```text
./scripts/verify-docs.sh
./gradlew test --tests '*Merchant*Catalog*' --tests '*OrderQuote*' --tests '*CreateOrderConcurrency*'
./gradlew test
./gradlew build
frontend: npm run check:design
frontend: npm run typecheck
frontend: npm run test:unit
frontend: npm run build-storybook
frontend: npm run build
frontend: npm run test:sites
Storybook MCP: get-changed-stories, preview-stories, run-story-tests(a11y=true)
git diff --check
```

성능 evidence는 representative Store에 Menu/Option/Configuration/requirement와 search term을 seed하고 production
query와 동일 SQL shape로 `EXPLAIN (ANALYZE, BUFFERS)`를 실행한다. Store shared lock 전후 throughput과 wait를
같은 fixture/동시성으로 비교한다. 측정값 없이 성능 개선 또는 SLA 충족을 주장하지 않는다.

각 PR 보고는 목적, parent/base, 포함 commit, migration/contract/transaction/failure/idempotency 영향,
Passed/Failed/Not run/Blocked, 다음 PR dependency와 size 판단을 포함한다. remote CI는 local 결과와 분리한다.

## Observability

- command count: operation/outcome/role의 닫힌 tag만 사용
- lock wait: shared/exclusive mode와 outcome; Store/Menu ID 없음
- validation limit rejection: resource kind만 사용
- existing quote stale metric에 owner=`merchant` 같은 낮은 cardinality 분류만 추가 검토
- Audit는 actor ID, role, Store/Menu reference, action, allowlisted before/after와 correlation을 기록하되 raw
  nested payload, 가격 목록 전체, idempotency key를 log/metric으로 복제하지 않는다.

새 metric이 실제 운영 질문에 답하지 못하거나 기존 metric으로 충분하면 추가하지 않는다.

## Documentation Updates

- `docs/product/business-policy-decisions.md` BR-52와 정책 우선순위
- ADR-076, ADR-103, ADR-116 amendment와 ADR-118/index
- `docs/architecture/capability-map.md`
- `docs/architecture/transaction-boundaries.md`
- `docs/product/design-to-capability-map.md`의 점주 `4b 메뉴·가격`
- `docs/security/authorization-matrix.md`
- 관련 API/runbook, migration/Flyway expected version, performance evidence
- 이 ExecPlan Progress/Decision/Outcomes와 predecessor completion evidence 정정

## Progress

- [x] 2026-08-26: 기존 policy/ADR/ExecPlan/source/OpenAPI/frontend route를 read-only로 조사했다.
- [x] 2026-08-26: 제품 결정으로 ACTIVE same-store `OWNER | STAFF` 권한을 확정했다.
- [x] 2026-08-26: Menu/Option 생성·수정·archive, hard delete 없음으로 확정했다.
- [x] 2026-08-26: 초기 bound를 Menu당 Option 100, Configuration 500, Configuration당 requirement 50으로 확정했다.
- [x] 2026-08-26: ADR-118과 추가 ExecPlan 초안을 작성했다.
- [x] 2026-08-27: Milestone 0 ADR·Business Policy와 completed dependency/readiness gate를 PR #116에 정리했다.
- [x] 2026-08-27: authoring 권한과 Store commerce lock 순서 충돌을 membership FOR SHARE 선취로 해소하고
  commit `c3932ef`, PR #117(`main <- feature/merchant-ordering-policy`)로 게시했다.
- [x] 2026-08-27: stale expected version은 기존 점주 콘텐츠 writer와 같은
  `409 MERCHANT_CONTENT_STALE`를 재사용하기로 확정했다.
- [x] 2026-08-27: Milestone 1 Store policy vertical slice를 commits `98e0e8d`, `de0a9dc`,
  PR #118(`feature/merchant-ordering-policy <- feature/merchant-store-ordering-policy`)로 게시했다.
- [x] 2026-08-27: Milestone 2 Menu catalogue vertical slice를 commits `d404eb1`, `b713580`,
  PR #119(`feature/merchant-store-ordering-policy <- feature/merchant-menu-catalog-lifecycle`)로 게시했다.
- [ ] Milestone 3 combined verification과 completion evidence 완료.

## Surprises & Discoveries

- 현재 production Kotlin 경로에는 price/availability/configuration writer가 없어 review finding을 public API로
  즉시 재현하기 어렵다. 그러나 Accepted ADR-116은 실제 writer가 생기기 전에 final serialization point를
  요구하고 design map은 점주 `메뉴·가격` write를 명시하므로 contract gap은 실재한다.
- Store/Menu coarse JPA version은 display/image write와 공유되면서 child trade write를 대표하지 못한다.
  별도 거래 version이 false stale 제거와 authoring optimistic concurrency를 함께 해결한다.
- existing `StoreSearchIndexOperations`가 caller transaction에 참여하도록 이미 설계돼 있어 새 queue/outbox가
  필요하지 않다.
- frontend 규칙상 필요했던 Storybook MCP inventory는 2026-08-27 running HTTP transport에서 보완했다.
  Store policy/catalogue page는 기존 Store console composition을 `COMPOSE`, `FeedbackState`와 `Button`을
  `REUSE`한다. dev server는 `EMFILE` watcher와 기존 story indexing warning을 보고했으므로 변경 story의
  focused preview/test와 broad handoff test 결과를 각각 기록한다.
- ExecPlan은 membership 확인 뒤 Store lock, ADR-118은 Store lock 뒤 membership 확인을 서술했고 기존
  `StoreAccessService`는 membership을 잠그지 않아 revoke 경쟁도 직렬화하지 못했다. 2026-08-27 결정으로
  catalogue 전용 membership shared lock을 Store lock보다 먼저 획득하고 404/403을 분리하도록 통일했다.
- 2026-08-27 running Storybook MCP에서 inventory와 Store page/FeedbackState/Button 문서, story 작성 지침을
  확인했다. policy panel은 기존 Store console page composition과 FeedbackState/Button을 재사용·조합한다.
- 계획의 failure table은 공용 오류 계약에 없는 `VERSION_CONFLICT`를 요구했지만 production
  `FailureCode`와 기존 Store/Menu 콘텐츠 writer는 `MERCHANT_CONTENT_STALE`를 사용했다. 2026-08-27 결정으로
  공용 오류 표면을 늘리지 않고 기존 409 코드를 Store/Menu 거래 writer에도 재사용한다.

## Decision Log

| Date | Decision | Reason | Record |
| --- | --- | --- | --- |
| 2026-08-26 | Store 주문 정책과 Menu 거래 catalogue는 ACTIVE same-store `OWNER | STAFF`가 관리 | 일상 매장 운영을 STAFF까지 허용하고 실행 시 membership을 재검증 | ADR-118, BR-52 |
| 2026-08-26 | update-only가 아니라 create/replace/archive를 제공하고 hard delete/restore는 v1 제외 | 실제 메뉴·가격 화면 완성과 과거 snapshot/Audit 보존 | ADR-118 |
| 2026-08-26 | 100 Option/Menu, 500 Configuration/Menu, 50 requirement/Configuration | 무경계 request/quote load를 막으면서 카페 catalogue에는 넉넉한 초기치 | ADR-118, ADR-076 amendment 예정 |
| 2026-08-26 | Store shared Order lock / exclusive writer lock | 같은 Store Order concurrency를 유지하면서 coherent Merchant snapshot 보장 | ADR-118 |
| 2026-08-26 | Store policy/Menu trade version을 display/image JPA version과 분리 | false stale 제거와 child 거래 변경 대표 | ADR-118, ADR-116 amendment 예정 |
| 2026-08-26 | 모든 mutation은 command-transaction idempotency | 기존 Store root, local atomic commit, Provider 호출 없음 | ADR-064, ADR-118 |
| 2026-08-27 | authoring은 membership FOR SHARE 뒤 Store FOR UPDATE 순서 | revoke 경쟁을 직렬화하고 cross-store/없는 Store를 같은 404로 숨기며 ExecPlan/ADR 충돌 해소 | BR-52, ADR-118 |
| 2026-08-27 | stale expected version은 `MERCHANT_CONTENT_STALE` 재사용 | 기존 점주 콘텐츠 writer와 공용 오류 계약을 유지하고 불필요한 새 failure code를 만들지 않음 | ADR-118, 이 ExecPlan |

## Outcomes & Retrospective

Milestone 1은 PR #118에서 V69 Store ordering policy version과 90일 command replay 원장, authenticated GET/PUT,
membership FOR SHARE 뒤 Store FOR UPDATE, Audit와 quote fingerprint v3를 구현했다. PostgreSQL writer-first와
Order-first, same-Store shared Order lock, membership revoke 경합, rollback, customer ordering projection을 검증했다.
frontend는 `/store/catalog` policy panel과 11개 catalogue state story를 추가했고 live Storybook MCP에서 Store shell을
포함한 12개 interaction+a11y가 통과했다. local docs/OpenAPI, focused backend, frontend type/unit/design/static docs/build와
Sites가 통과했고 PR #118 remote CI는 게시 직후 대기 중이다. 전체 repository와 전체 Storybook suite는 Milestone 3에서
실행한다. `COMPLETED`는 combined head에서 required 검증이 실제 통과한 뒤에만 선언한다.

Milestone 2는 PR #119에서 V70 Menu ACTIVE/ARCHIVED 수명주기, 별도 `trade_version`, 활성 판매 구성 유일성,
90일 command replay 원장과 create/replace/archive API를 구현했다. mutation은 membership FOR SHARE 뒤 Store
FOR UPDATE를 획득하고 Menu Aggregate, 검색어, Audit, command ledger를 같은 transaction에서 반영한다. 주문과 같은
Store lock 순서를 사용하며 A→B→A 거래 상태 복귀도 version으로 기존 quote를 stale 처리한다. 실제 1,000 Menu,
5,000 Option과 Menu당 100 Option/500 Configuration, Configuration당 50 requirement 경계, 동일 command 동시 replay,
검색 동기화 실패 rollback을 PostgreSQL integration test로 검증했다. frontend는 signed keyset pagination과 전체
Aggregate 편집, archive, stale/idempotency/permission/dependency 상태를 소비하며 focused 26개 Storybook
interaction+a11y와 static Storybook build가 통과했다. local docs/OpenAPI, focused backend, frontend design/type/unit도
통과했고 PR #119 remote CI는 preflight 성공 후 나머지 job이 진행 중이다. 전체 회귀·성능·lock-wait 증거는 계약을
변경하지 않는 Milestone 3 child PR에서 완료한다.

## Revision Notes

- 2026-08-26: review finding 검증과 사용자 결정을 바탕으로 최초 작성. vertical slice와 docs-only first PR,
  Store shared/exclusive lock, 별도 거래 version, create/replace/archive와 catalogue bound를 명시했다.
- 2026-08-27: 선행 계약 계획의 completed evidence를 반영해 dependency를 completed path로 바꾸고,
  ADR-118·BR-52 결정 기록과 함께 implementation readiness를 true로 전환했다.
- 2026-08-27: membership/Store lock 순서와 404/403 의미를 확정하고 Storybook MCP prerequisite를 충족했다.
- 2026-08-27: Store/Menu stale expected version을 기존 `MERCHANT_CONTENT_STALE` 409로 통일했다.
- 2026-08-27: V69 Store 주문 정책 vertical slice와 `/store/catalog` 소비자를 stacked PR #118로 게시했다.
- 2026-08-27: V70 Menu catalogue 거래 계약과 점주 소비 UI를 stacked PR #119로 게시했다.
