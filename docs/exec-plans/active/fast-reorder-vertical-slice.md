# 빠른 재주문으로 현재 조건의 새 Order를 원자적으로 생성한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/order-creation-and-reservation-lease.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 이 문서는 living document이며 구현 중
`Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`를 계속
갱신한다.

## Purpose / Big Picture

인증된 고객이 자신이 소유한 terminal Order를 source로
`POST /api/v1/orders/{sourceOrderId}/reorders`를 호출하면, BeanFlow는 과거 주문을 복제하지
않고 현재 판매 조건으로 새 Order를 즉시 만든다. source에서는 메뉴 ID, 검증된 정규화 option
ID와 수량만 가져오고, 새 pickup slot·선택적 coupon·point 사용은 새 request에서 받는다.
Merchant 가격과 판매 가능성, Fulfillment capacity, Inventory 수량, Promotion coupon과 Loyalty
point는 일반 주문 생성과 같은 원자적 workflow에서 다시 검증·예약한다.

완료 후 관찰 가능한 결과는 다음과 같다.

- 허용된 terminal source의 소유 고객만 재주문할 수 있다.
- 성공은 별도 draft나 Reorder Aggregate가 아니라 새 `PENDING_PAYMENT` 또는 benefit-only
  `PAID` Order의 `201 Created`다.
- source line 하나라도 검증된 option ID가 없거나 현재 주문할 수 없으면 item별 stable reason과
  함께 전체 409이고, 부분 Order·부분 reservation은 없다.
- 과거 가격·쿠폰·point allocation·결제·환불·pickup slot·정산 snapshot을 자동 재사용하지 않는다.
- 성공 body는 source/current 혜택 전 가격 차이를 재현 가능하게 고정한다.
- 같은 고객, `REORDER_ORDER_V1`, Idempotency-Key와 canonical payload는 새 Order를 하나만
  만들고 최초 terminal status/body를 그대로 재생한다.
- 실패한 owner dependency를 빈 결과, 현재 이름 추론, stale snapshot 또는 in-memory fallback으로
  대체하지 않는다.

`Implementation-Ready: true`의 근거는 BR-03/25/26 amendment, ADR-077, target OpenAPI,
오류 카탈로그와 권한 매트릭스가 제품 동작, identity, transaction, 멱등성, legacy 실패와 공개
응답을 모두 확정했고 direct dependency가 completed이기 때문이다. 구현 시작 전 새 사용자 결정은
필요하지 않다. 다만 이 plan은 migration을 쓰므로 `.agent/PLANS.md`의 repository-wide
migration-writer lease를 확보하지 못하면 시작할 수 없다.

## Current State

구현 기준은 2026-08-09의 최신 `main`, HEAD `c7370a8`이다. 깨끗한 worktree에서
`feature/fast-reorder-vertical-slice`를 만들었고 Flyway inventory는 `V35`까지다. 다른 최신-main
migration writer가 없음을 확인해 이 branch가 repository-wide writer lease를 보유하고 다음 번호
`V36`을 사용한다. detached 과거 discovery worktree의 V33/V34 변경은 현재 main의 후속 migration과
commit으로 대체된 잔여물이며 수정하거나 lease holder로 간주하지 않는다.

현재 구현 증거는 다음과 같다.

- `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderController.kt`는
  `POST /api/v1/orders`, customer GET, cancellation, payment-confirmation을 제공하지만 재주문
  mapping은 없다.
- `CreateOrderService`가 Tx I1과 Tx I2를 조정하고 `OrderCreationTransaction.create`가 Tx O에서
  current Merchant quote, pickup/stock/coupon/point reservation, benefit-only Payment,
  Order/OrderLine, settlement/point-accrual snapshot, Audit와 최초 201 idempotency completion을
  원자적으로 저장한다.
- `OrderIdempotencyService`는 `CREATE_ORDER` operation을 내부 상수로 고정한다. operation을
  인자로 분리하지 않으면 direct create와 `REORDER_ORDER_V1` scope를 안전하게 공유할 수 없다.
- `OrderEntity`는 `pickupSlotId`와 customer ownership을 보유하고 `OrderJpaRepository`에는
  pessimistic write 조회가 있다. customer GET projection은 재주문 입력 원천이 아니며 내부 owner
  model을 transaction 안에서 읽어야 한다.
- `OrderLineEntity`는 `menuId`, `menuName`, `optionNamesJson`,
  `sellableRequirementsJson`, 가격과 수량을 저장하지만 option ID를 저장하지 않는다. 따라서 기존
  row에서 이름이나 sellable unit으로 option ID를 안전하게 복구할 수 없다.
- Merchant `MenuQuoteUseCase`와 `MenuQuoteCalculator`는 current quote와 option ID가 포함된
  `OptionSnapshot`을 반환하지만 첫 실패에서 generic `DomainFailure`를 던진다. 빠른 재주문이 모든
  unavailable source line의 stable reason을 반환하려면 같은 owner validation을 쓰는 typed batch
  결과가 필요하다.
- `ordering_idempotency_record`는 `PROCESSING`, `COMPLETED`, `FAILED`, `MANUAL_REVIEW`와 최초
  response를 저장하지만 `retention_expires_at`과 이 table의 terminal cleanup 경로가 없다.
  `OrderingIdempotencyRetentionWorker`는 store/cancellation command table만 정리한다. BR-26의
  주문 생성 terminal 90일 보존이 아직 source에 구현되지 않은 drift다.
- target `openapi/beanflow-v1.yaml`에는 재주문 operation이 있고 runtime
  `openapi/beanflow-v1-runtime.yaml`에는 없다. 계획 작성 시 target은 28 paths/30 operations,
  runtime은 27 paths/29 operations다. 구현·계약·보안 테스트 완료 전 runtime에 추가하지 않는다.
- 별도 Reorder Aggregate, production cache, Redis, Kafka와 재주문 migration은 없다.

기준 계약은 BR-03, BR-25, BR-26, ADR-004, ADR-005, ADR-025, ADR-026, ADR-057,
ADR-064, ADR-077, `docs/api/api-conventions.md`, `docs/api/error-catalog.md`,
`docs/security/authorization-matrix.md`와 target OpenAPI다. 충돌이 발견되면 코드로 우회하지 않고
정책 또는 ADR을 먼저 갱신한다.

## Definitions

- **Fast Reorder:** terminal source Order의 최소 품목 identity를 사용해 현재 조건의 새 Order를
  즉시 생성하는 Ordering 명령. draft, quote resource 또는 Aggregate가 아니다.
- **Source Order:** 호출자가 소유하고 상태가 `COMPLETED`, `CANCELLED`, `REJECTED`, `EXPIRED` 중
  하나인 immutable 입력 Order.
- **Normalized option ID snapshot:** source line이 주문될 때 ID 오름차순, 중복 없는 형태로
  검증·저장한 option ID 배열. 빈 배열은 검증된 무옵션 선택이다.
- **Legacy unavailable snapshot:** migration 전 OrderLine처럼 검증된 option ID가 없음을 명시하는
  상태. 빈 배열과 다르고 이름·현재 Merchant·sellable requirement로 추론하지 않는다.
- **Current quote:** Tx O 안에서 Merchant owner contract가 반환한 현재 메뉴명, 옵션명, 단가와
  sellable requirement. 새 Order snapshot의 원천이다.
- **Price comparison:** source/current의 coupon·point 적용 전 가격을 비교한 재주문 201 전용
  snapshot. signed difference는 `current - source`다.
- **Tx I1 / Tx O / Tx I2:** 각각 멱등 `PROCESSING` 사전등록, 원자적 주문 생성, 확정 실패 응답
  저장 transaction이다.
- **Canonical payload:** hash 입력인 `sourceOrderId`, `pickupSlotId`, nullable
  `couponIssuanceId`, `pointsToUseKrw`의 고정 표현이다.
- **Owner validation:** 데이터를 소유한 Context의 공개 Application API가 현재 상태를 판정하는
  것. Ordering이 다른 Context Repository를 직접 읽는 방식이 아니다.

## Scope

### In Scope

- `POST /api/v1/orders/{sourceOrderId}/reorders` Controller와 `CUSTOMER` role gate
- Ordering public `ReorderOrderUseCase` command boundary와 internal orchestration
- source Order pessimistic lock, ownership·terminal-state·legacy snapshot validation
- 새 OrderLine의 normalized option ID snapshot 영속화와 legacy state migration
- Merchant current batch revalidation의 typed item outcome와 기존 quote 계산 재사용
- direct create와 reorder가 공유하는 transaction-mandatory order creation core 추출
- 일반 주문 생성의 observable request/status/body/idempotency/event/Audit 동작 회귀 방지
- `REORDER_ORDER_V1` Tx I1/Tx O/Tx I2, canonical payload hash와 exact replay
- `ordering_idempotency_record` terminal 90일 retention column, index와 Ordering worker 확장
- required price comparison 201 response와 typed item failure detail
- error mapping, authorization, metrics, structured logs와 PostgreSQL 기반 테스트
- target 구현 완료 뒤 runtime OpenAPI parity와 capability/evidence 문서 갱신

### Non-goals

- draft, quote 또는 장바구니 API
- 별도 Reorder Aggregate, table, Repository 또는 event
- 과거 가격·coupon·point allocation·PaymentMethod·Payment·Refund·pickup slot·reservation·
  accrual·settlement snapshot의 복제
- source note 복사 또는 새 note 계약
- unavailable line 자동 삭제, 부분 재주문 또는 client-side merge
- current promotion/coupon/point의 자동 선택
- 자동 외부 결제 승인; 1원 이상은 기존 payment-confirmation API를 사용한다.
- 추천·개인화·주문 빈도 분석
- 새 cache, Redis, Kafka, scheduler 제품 또는 production dependency
- 이름·현재 Merchant·sellable requirement를 이용한 legacy option ID backfill
- 측정 없는 성능 개선 주장

## Business Rules and Invariants

### Source and authorization

- JWT subject에서 customer ID를 얻고 request body로 customer/store/menu/line identity를 받지 않는다.
- source 부재는 404, 타 고객 소유는 403이다. ownership 검증 전 source line과 금액을 응답·log에
  노출하지 않는다.
- 허용 source 상태는 `COMPLETED`, `CANCELLED`, `REJECTED`, `EXPIRED`뿐이다. 나머지는
  `REORDER_SOURCE_STATE_INVALID` 409다.
- source 상태는 refund, compensation, settlement 후속 처리 완료를 의미하지 않는다. 이 후속
  snapshot을 복사하지 않으므로 unresolved 후속 상태는 재주문을 막지 않는다.

### Copied and fresh inputs

- source 각 line에서 `menuId`, normalized option IDs, `quantity`만 가져온다. line 순서는 source
  `lineSequence` 오름차순으로 보존한다.
- 새 request는 `pickupSlotId`와 non-negative `pointsToUseKrw`를 필수로,
  `couponIssuanceId`를 nullable 선택으로 받는다.
- source store ID는 owner Order에서 가져오고 request에 받지 않는다.
- 새 OrderLine은 current Merchant가 반환한 menu/option 이름·단가·sellable requirement와 검증된
  normalized option IDs를 새 immutable snapshot으로 저장한다.
- source의 menu/option ID와 quantity 외 필드는 새 주문 계산의 입력이 아니다. source unit price와
  gross는 price comparison에만 사용한다.
- coupon과 point는 request에서 명시한 값만 적용한다. payment method는 재주문 request에 없고 새
  Order가 외부 결제를 요구하면 기존 payment-confirmation 명령에서 명시한다.

### All-or-nothing and money

- legacy option snapshot line 또는 current unavailable line이 하나라도 있으면 새 Order와 모든
  reservation은 0건이다. item을 지우거나 quantity를 줄이지 않는다.
- Merchant quote, pickup, stock, coupon, point, settlement input, point accrual snapshot 또는 Audit
  하나라도 실패하면 기존 주문 생성 원자성에 따라 전체 rollback한다.
- 새 Order 가격·coupon·point·payable·allocation은 기존 KRW 계산과 tie-out 규칙을 그대로 쓴다.
- payable이 0이면 기존 benefit-only branch가 같은 Tx O에서 Payment 승인과 reservation confirm을
  수행한다. 1원 이상이면 `PENDING_PAYMENT`이고 effective reservation deadline은 Accepted BR-03과
  현재 Fulfillment owner 계약대로 `min(createdAt + 5분, pickupSlot.startsAt)`이다.
- price comparison은 source line gross 합과 current line gross 합을 사용한다. `hasPriceChanges`는
  line unit price 또는 line gross가 하나라도 달라졌을 때 true다. no-change는 difference 0과 빈
  `items`다.
- `items`에는 바뀐 line만 source line 순서로 넣고 source/current unit price·gross와 signed
  difference를 저장한다. coupon·point·payment 차이는 제외한다.

### Idempotency

- scope unique는 `(customerId, REORDER_ORDER_V1, Idempotency-Key)`다. direct create의
  `CREATE_ORDER`와 분리한다.
- canonical payload는 field 순서를 고정한 sourceOrderId, pickupSlotId,
  couponIssuanceId(null 포함), pointsToUseKrw다. UUID는 표준 lowercase 문자열, 금액은 정수 10진수로
  표현하고 SHA-256을 사용한다.
- source line은 sourceOrderId가 식별하는 immutable snapshot이므로 hash에 중복하지 않는다.
- same key/same payload terminal record는 최초 status/body를 exact replay한다. 가격과 availability를
  다시 계산하지 않고 replay indicator를 넣지 않는다.
- same key/different source 또는 request는 owner 작업 전에 `IDEMPOTENCY_KEY_REUSED` 409다.
- same key/same payload `PROCESSING`은 새 Order를 실행하지 않고
  `IDEMPOTENCY_REQUEST_IN_PROGRESS` 409와 `Retry-After`다.
- `COMPLETED`와 `FAILED`는 terminal이 된 시각부터 서울 달력이 아닌 정확한 duration 90일 동안
  보존한다. `PROCESSING`과 `MANUAL_REVIEW`는 자동 삭제하지 않는다.

## Architecture and Transaction Boundaries

Controller는 `ReorderOrderUseCase`만 호출한다. use case command는 customer ID,
sourceOrderId, pickupSlotId, nullable couponIssuanceId와 pointsToUseKrw를 가진다. source line,
store와 past snapshot을 Controller DTO에 넣지 않는다.

### Shared order creation boundary

현재 `OrderCreationTransaction`의 owner orchestration을
`OrderCreationWorkflow` 같은 internal component로 추출하고
`@Transactional(propagation = MANDATORY)`를 부여한다. 이 boundary는 이미 검증된
`CreateOrderCommand`를 받아 다음을 수행하고, idempotency나 HTTP shape를 모르는
`OrderCreationOutcome(order, benefitOnlyPayment?)`을 반환한다.

1. current Merchant quote와 applicable settlement terms 조회
2. Pickup, Stock, optional Coupon, optional Point reserve
3. 기존 money/allocation 계산과 benefit-only confirm
4. Order/OrderLine 및 normalized option snapshot 저장
5. settlement input과 point accrual snapshot 저장
6. target별 Audit 저장

`OrderCreationTransaction.create`는 기존 direct-create Tx O wrapper로 남아 이 workflow를 호출하고
기존 `OrderCreationResponseFactory` body를 만든 뒤 `CREATE_ORDER` record를 같은 transaction에서
complete한다. public `CreateOrderUseCase`, request, 최초 body, metric와 failure mapping은 바꾸지 않는다.

재주문은 `FastReorderTransaction.create` 같은 별도 `@Transactional` Tx O wrapper를 둔다. source
Order를 write lock으로 읽고, 존재→ownership→state→line snapshot 순서로 검증한다. source line을
`CreateOrderCommand`로 변환해 shared workflow를 호출하고 `FastReorderResponseFactory`가 price
comparison을 포함한 body를 만든 뒤 `REORDER_ORDER_V1` record를 같은 transaction에서 complete한다.
shared workflow는 새 transaction을 열지 않으므로 새 Order, 네 owner reservation, immutable
snapshots, Audit, price comparison과 최초 201 idempotency response가 기존 원자성 안에서 함께
commit한다.

### Merchant item revalidation

Merchant public API에 source line ID/sequence를 포함하지 않는 current catalogue batch 검증 DTO를
추가하거나 기존 `MenuQuoteUseCase`를 typed batch result로 확장한다. Ordering은 반환 위치를 source
line identity와 결합한다. 구현은 `MenuQuoteCalculator`의 가격·구성 계산 규칙을 하나만 유지하며 direct
create는 기존 first-failure 공개 의미를 보존한다. reorder batch 결과는 성공 quote 또는 다음 owner
reason을 line별로 제공한다.

- `MENU_REMOVED`
- `MENU_NOT_AVAILABLE`
- `OPTION_REMOVED`
- `OPTION_NOT_AVAILABLE`
- `MENU_CONFIGURATION_NOT_AVAILABLE`

legacy `SOURCE_OPTION_SELECTION_UNAVAILABLE`은 Ordering source snapshot 검증에서 만든다. store가
현재 pickup order를 받지 않는 경우와 owner setup 손상은 item reason으로 왜곡하지 않고 기존
`MENU_CONFIGURATION_NOT_AVAILABLE` 또는 `DEPENDENCY_UNAVAILABLE` top-level 의미를 유지한다.
failure details는 source line 순서, 같은 line에서 위 reason 순서, option ID 순서로 정렬한다. menu
상위 failure가 있으면 그 line의 option/config 하위 failure는 추가하지 않는다.

### Time changes and linearization

- Tx I1은 source 내용을 읽지 않고 intended new Order ID와 canonical payload만 사전등록한다.
- Tx O source lock 시점의 owner/state/line snapshot이 source 입력의 기준이다. source가 Tx I1 뒤
  비허용 상태였다면 409 failure를 저장한다.
- Merchant current quote call이 새 Order 가격·catalogue snapshot의 선형화 지점이다. quote 뒤
  Merchant가 바뀌어도 같은 Tx O가 고정한 current quote를 새 Order snapshot으로 사용하며 이는 direct
  create와 같은 의미다.
- Fulfillment/Inventory/Promotion/Loyalty는 각 owner의 기존 lock·conditional write가 예약 시점을
  직렬화한다. 사전 catalogue 조회 성공은 capacity·stock·benefit을 보장하지 않는다.
- Tx O rollback 뒤 Tx I2가 확정 실패를 저장한다. Tx I2까지 실패해 `PROCESSING`이 남으면 기존
  reconciliation을 operation-aware하게 확장해 intended Order와 owner sources를 검사한다. 새 재주문을
  자동 실행하지 않는다.

### Concurrency and lock order

- 순서는 idempotency Tx I1 commit 후 Tx O의 source Order lock, Merchant quote, Fulfillment,
  Inventory, Promotion, Loyalty, Payment benefit-only, Ordering snapshots/Audit/idempotency completion이다.
- 같은 source를 다른 key로 동시에 재주문하는 것은 허용되며 capacity/stock/coupon/point owner guard가
  실제 경쟁을 결정한다. source당 한 번이라는 제약은 없다.
- source Order lock 대기가 timeout 또는 DB 장애로 끝나면 business conflict가 아닌 503이다.
- owner lock 순서는 기존 direct create와 같아야 한다. 재주문을 이유로 역순 lock이나 source
  Aggregate cascade를 추가하지 않는다.

## Alternatives Considered

### Draft 또는 quote를 먼저 생성

- 장점: 고객이 가격 변경을 확인한 뒤 별도 승인할 수 있다.
- 단점: draft lifecycle, expiry, 별도 idempotency와 현재값 재검증 시점을 새로 결정해야 한다.
- 선택하지 않은 이유: 확정 정책은 기존 create와 같은 즉시 새 Order이며 가격 변경은 201에 명시한다.

### `POST /orders`에 sourceOrderId 추가

- 장점: 주문 생성 endpoint가 하나다.
- 단점: direct create의 request/hash/validation 의미를 바꾸고 operation별 replay·관측을 섞는다.
- 선택하지 않은 이유: source identity가 path에 드러나는 nested command와 별도 operation이 안정적이다.

### source Order를 명령 트랜잭션의 멱등 직렬화 root로 사용

- 장점: 별도 `PROCESSING` 창을 줄일 수 있다.
- 단점: 같은 source로 여러 새 Order를 만들 수 있는 정책과 충돌하고 source lock은 결과 new root 생성
  경쟁의 identity가 아니다.
- 선택하지 않은 이유: ADR-064에 따라 결과 root가 없는 생성 명령은 Tx I1 사전등록을 쓴다.

### 과거 option 이름 또는 sellable requirement로 option ID 복구

- 장점: 기존 주문의 재주문 성공 범위가 넓다.
- 단점: 이름 중복·변경과 구성 변경 때문에 다른 상품을 주문할 수 있고 검증 가능한 provenance가 없다.
- 선택하지 않은 이유: legacy row는 명시적으로 실패하고 이후 새 Order만 option ID snapshot을 가진다.

### unavailable line을 빼고 부분 생성

- 장점: 일부 주문 전환율이 높을 수 있다.
- 단점: 고객이 확인하지 않은 주문 구성·금액을 만들고 idempotency payload와 price comparison을
  불안정하게 한다.
- 선택하지 않은 이유: all-or-nothing 409와 item detail이 확정 정책이다.

### 별도 Reorder Aggregate와 table

- 장점: 시도 이력과 draft를 독립적으로 저장할 수 있다.
- 단점: 제품 resource가 아닌 orchestration에 lifecycle·retention·권한을 새로 만든다.
- 선택하지 않은 이유: Order와 operation-specific IdempotencyRecord가 필요한 durable 결과를 소유한다.

## Failure Semantics

| Condition | HTTP / code | Durable result | Retry rule |
|---|---|---|---|
| malformed UUID/body/key, negative points | 400 `INVALID_REQUEST` | Tx I1 전이면 없음 | corrected request/new key |
| source 없음 | 404 `RESOURCE_NOT_FOUND` | 최초 실패 record | same key exact replay; source가 생기지 않으므로 보통 재시도 불필요 |
| source 타 고객 소유 | 403 `ACCESS_DENIED` | 최초 실패 record | 권한 변경 전 재시도 불필요 |
| source non-terminal | 409 `REORDER_SOURCE_STATE_INVALID` | 최초 실패 record | terminal 전이 후 새 key |
| legacy/current unavailable line | 409 `REORDER_ITEMS_UNAVAILABLE` + typed details | 최초 실패 record | source/current catalogue가 달라졌으면 새 key |
| slot/stock/coupon/point conflict | 기존 stable 409 | 최초 실패 record | owner 상태 변경 뒤 새 key |
| same key, different source/request | 409 `IDEMPOTENCY_KEY_REUSED` | 기존 record 불변 | 새 key |
| same key/payload PROCESSING | 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` + `Retry-After` | PROCESSING 유지 | same key after delay |
| owner/DB/Audit/snapshot dependency failure | 503 `DEPENDENCY_UNAVAILABLE` 또는 `SETTLEMENT_INPUT_UNAVAILABLE` | Tx O rollback, 가능하면 Tx I2 최초 실패 | Tx I2가 저장했으면 same key는 exact replay이므로 원인 복구 뒤 새 key |
| Tx I2 저장도 실패 | 503 `DEPENDENCY_UNAVAILABLE` | PROCESSING 가능 | reconciliation; 자동 재실행 금지 |
| benefit-only local confirmation 불완전 | 503 `DEPENDENCY_UNAVAILABLE` | 전체 rollback | owner 상태 조사 후 재시도 |

item 409는 가능한 line을 부분 commit했다는 뜻이 아니다. 503을 빈 details, 409, stale quote 또는
0원 가격으로 변환하지 않는다. 응답 message에는 source의 과거 menu/option 이름, 혜택, 결제수단,
환불·정산 정보와 내부 SQL을 포함하지 않는다.

## Data and Migration

이 plan은 두 schema drift를 닫으므로 `Writes-Migration: true`다. 구현자는 최신 `main`에서
migration-writer lease를 얻고 현재 마지막 번호를 확인한 뒤
`src/main/resources/db/migration/V<next>__add_fast_reorder_snapshots_and_idempotency_retention.sql`
같은 단일 Ordering-owned migration을 만든다. 번호와 실제 파일명은 구현 시작 시 `Progress`에
기록한다.

### OrderLine option identity

`ordering_order_line`에 다음을 추가한다.

- `option_selection_snapshot_state varchar(32)`
- `normalized_option_ids_json jsonb`

migration 순서는 다음과 같다.

1. 두 column을 nullable로 추가한다.
2. 기존 모든 row를 `option_selection_snapshot_state='LEGACY_UNAVAILABLE'`, JSON null로 갱신한다.
3. state를 NOT NULL로 만들고 default는 남기지 않는다.
4. state는 `LEGACY_UNAVAILABLE`, `SNAPSHOTTED`만 허용한다.
5. `LEGACY_UNAVAILABLE`이면 JSON null, `SNAPSHOTTED`이면 JSON array라는 CHECK를 추가한다.

새 application write는 항상 `SNAPSHOTTED`와 ID 오름차순·중복 없는 UUID string JSON array를
저장한다. 검증된 무옵션은 `[]`이다. JSON element UUID 형식, 정렬과 uniqueness는 domain/assembler와
PostgreSQL integration test에서 함께 검증한다. 기존 row를 이름이나 현재 Merchant 값으로 backfill하지
않는다. 새 write가 state/JSON을 생략하면 DB NOT NULL/CHECK 또는 application invariant로 실패해야 한다.

### Order creation idempotency retention

`ordering_idempotency_record`에 nullable `retention_expires_at timestamptz`를 추가한다.

1. terminal row 중 `completed_at`이 null인 corruption precheck가 있으면 migration을 실패시킨다.
2. 기존 `COMPLETED`/`FAILED`는 `completed_at + interval '90 days'`로 backfill한다.
3. `COMPLETED`/`FAILED`는 `completed_at`과 `retention_expires_at`이 모두 non-null이고 후자가 정확히
   90일 뒤이며, `PROCESSING`/`MANUAL_REVIEW`는 retention null이라는 CHECK를 추가한다.
4. due keyset deletion을 위한 partial index
   `(retention_expires_at, id) WHERE retention_expires_at IS NOT NULL`을 추가한다.

`OrderIdempotencyService.complete/fail`은 같은 terminal 시각에서 90일을 계산해 저장한다.
`IdempotencyRecordJpaRepository`에 due ID keyset query와 due count를 추가하고
`OrderingIdempotencyRetentionWorker`가 store/cancellation table과 독립된 short transaction으로 이
table도 purge한다. 한 table 장애를 다른 table의 성공으로 숨기지 않고 table별 metric/log를 남긴다.
PROCESSING/MANUAL_REVIEW, due 경계 전과 조회한 chunk 밖 row는 삭제하지 않는다.

rollback rehearsal은 migration 직전 schema snapshot 또는 disposable Testcontainers DB에서 수행한다.
production data가 존재한다는 주장이나 손실 없는 downgrade를 가정하지 않는다. Flyway forward-only
정책에 따라 실패 시 원 migration을 수정해 checksum을 바꾸지 않고 새 corrective migration을 쓴다.

## API and Event Contracts

### Request

```http
POST /api/v1/orders/{sourceOrderId}/reorders
Authorization: Bearer <customer-token>
Idempotency-Key: <8..128 characters>
Content-Type: application/json

{
  "pickupSlotId": "uuid",
  "couponIssuanceId": "uuid-or-omitted",
  "pointsToUseKrw": 0
}
```

request는 additional properties를 거부한다. `pickupSlotId`, `pointsToUseKrw`는 필수이고
coupon null/omitted는 canonical payload에서 같은 null로 표현한다.

### Success

201은 target OpenAPI의 `ReorderOrderResult`다. 기존 `CreateOrderResult`처럼 외부 결제 variant는
`order`만, benefit-only variant는 `order`와 `payment`를 가지며 둘 다 `priceComparison`이 필수다.

`priceComparison`은 다음을 보장한다.

- `sourceSubtotalKrw`, `currentSubtotalKrw`, signed `subtotalDifferenceKrw`
- `hasPriceChanges`
- changed line만 포함한 `items`
- 각 item의 sourceOrderLineId, zero-based lineSequence, menuId, quantity, source/current unit price,
  source/current gross와 signed line difference

409 `REORDER_ITEMS_UNAVAILABLE`은 target OpenAPI의 `ReorderItemsUnavailableError`로 item detail을
반환한다. 나머지 오류는 공통 Error envelope를 사용한다. `Retry-After`는
`IDEMPOTENCY_REQUEST_IN_PROGRESS`에만 필수로 설정한다.

### Runtime promotion and events

구현 시작 때 target OpenAPI를 바꾸는 새 정책 판단을 하지 않는다. Controller, contract/security/
failure tests와 Spring mapping이 모두 통과한 같은 변경에서
`openapi/beanflow-v1-runtime.yaml`에 path와 필요한 schema reference를 반영하고 contract date를
갱신한다. target과 runtime operation 집합은 그 시점 28 paths/30 operations로 일치해야 한다.

새 Reorder event는 없다. 새 Order는 일반 주문 생성과 같은 OrderPlaced, Audit, settlement/accrual
snapshot 및 이후 lifecycle을 사용한다. event payload에 sourceOrderId를 추가하는 것은 이 plan의
정책이 아니며 필요성이 발견되면 event/개인정보 영향을 별도 결정한다.

## Milestones

### Milestone 0 — implementation baseline과 migration lease

- 최신 `main`에서 시작 명령, HEAD, worktree와 Flyway inventory를 기록한다.
- 이 plan이 여전히 ACTIVE/ready이고 direct dependency가 completed인지 확인한다.
- repository-wide migration-writer lease를 확보하고 새 migration 번호를 Progress에 기록한다.
- source, tests, target/runtime OpenAPI와 ADR-077의 drift를 다시 비교한다.
- 중요 충돌이 있으면 구현을 중단하고 AGENTS.md 질문 절차를 따른다.

### Milestone 1 — schema와 immutable source identity

- OrderLine option snapshot state/JSON과 ordering idempotency retention migration을 작성한다.
- `OrderLineEntity`, domain snapshot, `OrderSnapshotAssembler`가 새 Order에 normalized IDs를 항상
  저장하도록 한다.
- legacy row와 verified no-option row를 구분해 읽는 mapper를 추가한다.
- idempotency entity/service/repository/worker를 terminal retention에 맞춘다.
- migration, JPA constraint, exact 90-day boundary와 retention worker integration tests를 통과시킨다.

### Milestone 2 — shared creation core와 direct-create 회귀 보호

- `OrderCreationTransaction`의 owner orchestration을 transaction-mandatory shared workflow로 추출한다.
- idempotency service가 `CREATE_ORDER`와 `REORDER_ORDER_V1`을 명시적으로 받되 허용 operation을 enum/
  sealed value로 제한한다.
- direct create wrapper가 기존 response factory와 동일한 Tx O completion을 유지하도록 한다.
- 기존 create unit/integration/concurrency/benefit-only/settlement/accrual tests를 먼저 통과시킨다.
- shared workflow에 `REQUIRES_NEW`, Controller repository 접근 또는 fake fallback이 없는지 Modulith/
  ArchUnit review를 수행한다.

### Milestone 3 — source validation과 current item quote

- Ordering `ReorderOrderUseCase`, canonical payload, source lock/ownership/state/legacy validation을 구현한다.
- Merchant current batch validation이 기존 quote calculator 규칙을 재사용하면서 item stable reason을
  반환하게 한다.
- error detail order와 menu-over-option cause suppression을 고정한다.
- source read 뒤 owner state 변화, same source/different keys와 unavailable item all-or-nothing을
  PostgreSQL에서 검증한다.

### Milestone 4 — API, response와 idempotency

- Controller request/mapping/role gate와 `REORDER_ORDER_V1` Tx I1/Tx O/Tx I2를 연결한다.
- source/current price comparison과 pending/benefit-only response factories를 구현한다.
- exact success/failure replay, changed source/request conflict, PROCESSING Retry-After와 Tx I2 failure/
  reconciliation을 검증한다.
- 개인정보·past snapshot이 response, error와 log에 없는지 contract/security test로 확인한다.

### Milestone 5 — observability, runtime contract와 completion

- bounded outcome metric, duration, replay/key-reuse/processing/item-reason/retention metric과 structured log를
  추가한다.
- runtime OpenAPI와 capability/evidence 문서를 구현 사실로 갱신한다.
- 전체 validation과 clean rerun을 실행하고 diff에 범위 밖 변경·secret·fallback이 없는지 검토한다.
- plan을 `docs/exec-plans/completed/fast-reorder-vertical-slice.md`로 이동하고 Status/Completed-At,
  Progress, Outcomes, actual commands/results를 같은 completion commit에서 갱신한다.

## Required Tests

### Domain and application

- normalized option IDs가 UUID 오름차순·unique이고 verified empty와 legacy null이 구분됨
- source 허용 네 상태와 비허용 다섯 상태
- source menu/option/quantity만 command로 변환되고 과거 name/price/benefit/payment/slot은 제외됨
- source/current subtotal과 line signed difference, changed-lines-only, no-change empty list
- direct create가 shared workflow 추출 전후 동일한 pending/benefit-only body와 failure code를 반환함

### Merchant owner contract

- menu removed/unavailable, option removed/unavailable, configuration unavailable의 typed reason
- menu 상위 failure가 option/config 하위 failure를 중복 생성하지 않음
- 여러 line failure의 deterministic order
- store not accepting과 corrupted configuration이 item reason으로 잘못 변환되지 않음
- direct create의 기존 invalid/unavailable 의미 회귀 없음

### PostgreSQL repository and migration

- 기존 OrderLine은 `LEGACY_UNAVAILABLE/null`, 새 line은 `SNAPSHOTTED/[]` 또는 sorted ID JSON
- invalid state/JSON 조합, 새 write 누락, malformed/duplicate/unsorted application input 거부
- existing terminal idempotency row의 exact 90-day backfill과 corruption precheck
- terminal 직전 보존, exact due boundary 삭제, 이후 삭제
- PROCESSING/MANUAL_REVIEW 보존, keyset chunk 중단·재실행과 다른 table 독립성
- migration을 빈 DB와 pre-migration fixture DB에 각각 적용

### API, security and privacy

- CUSTOMER owner success 201 pending/benefit-only variants와 required priceComparison
- unauthenticated 401, non-CUSTOMER 403, missing source 404, other-customer source 403
- ownership 확인 전 source detail 미노출
- non-terminal source 409 `REORDER_SOURCE_STATE_INVALID`
- typed `REORDER_ITEMS_UNAVAILABLE` details와 부분 Order/reservation 0건
- past price/coupon/points/payment/refund/slot/settlement와 raw key가 response/log에 없음
- request additional property, negative point, invalid key 400

### Transaction, concurrency and idempotency

- 새 Order와 Pickup/Stock/Coupon/Point, benefit-only Payment, settlement/accrual snapshot, Audit와 최초 201
  response의 단일 Tx O commit
- owner 각 단계 실패에서 전체 rollback과 Tx I2 failure replay
- same key/payload 순차·동시 요청이 Order 하나와 exact first status/body를 반환
- same key/different source, pickupSlot, coupon null/value, points conflict
- same source/different keys는 정책상 허용하되 owner capacity/stock/benefit constraint가 초과를 막음
- PROCESSING은 Retry-After 409이고 재실행하지 않음
- Tx I1 후 crash, Tx O commit 전/후 crash, Tx I2 failure의 reconciliation이 자동 재주문하지 않음
- source lock과 기존 owner lock ordering에서 deadlock/timeout이 business 409로 위장되지 않음

### Architecture and contract

- Controller가 Repository를 직접 호출하지 않음
- Ordering이 Merchant/Fulfillment/Inventory/Promotion/Loyalty Repository를 참조하지 않음
- shared workflow가 transaction mandatory이고 direct/reorder Tx O 밖에서 호출되지 않음
- Spring Modulith와 ArchUnit 통과
- target/runtime OpenAPI schema validation과 runtime handler exact parity
- Kotlin/SQL 변경에 대응하는 ADR/Business Policy/ExecPlan drift 없음

## Validation Commands

구현 중 좁은 테스트를 먼저 실행하고 완료 전 아래를 repository root에서 그대로 실행한다.

```bash
pwd
git status --short
git branch --show-current
git log -5 --oneline
find src/main/resources/db/migration -maxdepth 1 -type f -name 'V*.sql' | sort -V | tail -5
./gradlew test --tests '*FastReorder*'
./gradlew test --tests '*CreateOrder*' --tests '*BenefitOnlyOrderCreationTest' --tests '*OrderControllerContractTest'
./gradlew test --tests '*OrderingIdempotencyRetention*' --tests '*ModularityTests' --tests '*RuntimeOpenApiParityTest'
./gradlew clean test
bash scripts/verify-docs.sh
git diff --check
git diff --name-only -- '*.kt' '*.kts' '*.sql'
```

마지막 `git diff --name-only`은 이 구현 plan에서는 Kotlin/SQL이 존재하는 것이 정상이며 승인된
Ordering/Merchant/API/test/migration 범위만 나와야 한다. 문서 전용 정책 task에서는 같은 명령의
출력이 0이어야 한다. 명령을 실행하지 않았거나 실패했으면 통과로 기록하지 않는다.

## Observability

- `beanflow.order.reorder.attempts{outcome}`: success, replay와 stable top-level failure code
- `beanflow.order.reorder.duration`: application command duration
- `beanflow.order.reorder.item_unavailable{reason}`: bounded six reason만 tag로 사용
- 기존 `beanflow.order.idempotency.events` 또는 operation-aware equivalent에 reorder replay,
  key_reused, in_progress, reconciliation outcome 추가
- ordering idempotency retention의 table, deleted count, oldest due age와 remaining due count
- structured log는 correlation ID, operation, outcome, source line count, changed-price line count와
  retry/reconciliation state를 포함한다.
- raw Idempotency-Key, customer/order/source/menu/option ID, 과거 이름, 결제·환불·정산 snapshot은 metric
  tag나 일반 log에 넣지 않는다. 필요한 target identity는 기존 Audit whitelist와 access policy를 따른다.
- Alert 후보는 stuck PROCESSING age/개수, reconciliation MANUAL_REVIEW, repeated retention failure와
  owner dependency 503 비율이다. threshold는 운영 측정 전 임의로 확정하지 않는다.
- **Not measured:** 재주문 빈도, 성공률, p95/p99 latency, source age 분포, item-unavailable 비율,
  lock wait와 retention 처리량. 구현 전 성능 개선을 주장하지 않는다.

## Documentation Updates

정책 결정 단계에서 다음을 갱신했다.

- `docs/product/business-policy-decisions.md`
- `docs/adr/ADR-004-order-price-snapshot.md`
- `docs/adr/ADR-064-risk-based-idempotency-model-selection.md`
- `docs/adr/ADR-077-fast-reorder-order-creation-api-identity.md`
- `docs/adr/README.md`
- `docs/api/api-conventions.md`
- `docs/api/error-catalog.md`
- `docs/security/authorization-matrix.md`
- `docs/architecture/context-map.md`
- `docs/architecture/capability-map.md`
- `docs/architecture/aggregate-invariants.md`
- `docs/architecture/transaction-boundaries.md`
- `docs/architecture/policy-traceability.md`
- `docs/architecture/ubiquitous-language.md`
- `openapi/beanflow-v1.yaml`
- `docs/index.md`

구현 완료 때 runtime OpenAPI contract date/path/schema, capability map의 runtime/target 수치, 실제 source/
test/migration evidence와 이 ExecPlan의 Outcomes를 갱신한다. 운영 threshold 또는 실제 성능 수치는 측정
증거가 있을 때만 별도 quality evidence에 기록한다.

## Progress

- [x] 2026-08-09: 현재 HEAD/source/OpenAPI/ownership/transaction drift 조사.
- [x] 2026-08-09: 사용자 질문으로 결과, endpoint/operation, copy fields, price comparison,
  source states 확정.
- [x] 2026-08-09: BR amendment, ADR-077, ADR-004/064 amendment와 target OpenAPI 작성.
- [x] 2026-08-09: error catalog, authorization matrix, architecture/capability/index와 이 plan 작성.
- [x] 2026-08-09: Milestone 0 완료. latest main `c7370a8`, clean worktree, completed direct dependency,
  target/runtime drift와 Flyway `V35`를 재확인하고 단독 migration-writer lease로 `V36`을 선택.
- [ ] Milestone 1: schema와 immutable source identity.
- [ ] Milestone 2: shared creation core와 direct-create 회귀 보호.
- [ ] Milestone 3: source/current item revalidation.
- [ ] Milestone 4: API, price response와 idempotency.
- [ ] Milestone 5: observability, runtime promotion과 completion evidence.

## Surprises & Discoveries

- 2026-08-09: 현재 Order read projection과 `ordering_order_line` 모두 option 이름은 보존하지만 option
  ID는 보존하지 않는다. source option을 이름이나 sellable requirement로 복원하면 상품 identity를
  바꿀 수 있으므로 legacy explicit failure와 future immutable ID snapshot이 필요하다.
- 2026-08-09: BR-26은 주문 생성 terminal idempotency 90일 보존을 Accepted로 두지만
  `ordering_idempotency_record`에는 retention column과 purge worker 경로가 없다. 빠른 재주문만 같은
  table에 추가하고 drift를 방치하면 Accepted retention을 더 확대하므로 이 plan에서 함께 닫는다.
- 2026-08-09: runtime OpenAPI는 target schema를 재사용하지만 operation inventory는 구현 mapping만
  가져야 한다. 따라서 정책 단계에서는 target만 28/30으로 늘리고 runtime은 27/29로 유지했다.
- 2026-08-09: source Order가 기존 root여도 새 Order 생성 경쟁을 직렬화하는 target root는 아니다.
  ADR-064에 따라 command-transaction 모델이 아니라 preregistration 모델을 사용한다.
- 2026-08-09: 계획의 “5분 lease” 문구가 BR-03의 pickup-start effective deadline amendment를
  생략하고 있었다. 현재 생성 구현은 Fulfillment reserve 결과의 clamp된 `expiresAt`을 downstream
  owner와 Order에 사용하므로 코드 conflict는 없고, 재주문도 같은 shared workflow를 재사용하도록
  계획 문구를 `min(createdAt + 5분, pickupSlot.startsAt)`으로 정정했다.
- 2026-08-09: migration inventory는 main의 V35가 최신이다. detached 과거 discovery worktree의
  V33/V34 변경은 main의 현재 V33~V35와 후속 commit에 의해 대체된 잔여물이라 건드리지 않았고,
  현재 branch를 유일한 latest-main migration writer로 기록했다.

## Decision Log

- 2026-08-09: 빠른 재주문은 draft/quote 없이 새 Order를 즉시 만들고 201을 반환한다. 별도 Reorder
  Aggregate는 없다. 기록: BR-03, ADR-077.
- 2026-08-09: endpoint는 `POST /api/v1/orders/{sourceOrderId}/reorders`, idempotency operation은
  `REORDER_ORDER_V1`이다. 기록: ADR-077, target OpenAPI.
- 2026-08-09: source에서 menu ID, normalized option IDs, quantity만 사용하고 note는 복사하지 않는다.
  legacy option identity는 추론하지 않고 실패한다. 기록: BR-03, ADR-004, ADR-077.
- 2026-08-09: source 허용 상태는 `COMPLETED`, `CANCELLED`, `REJECTED`, `EXPIRED`다. 기록:
  BR-03, ADR-077.
- 2026-08-09: 새 pickupSlotId와 points 값은 request 필수, coupon은 명시적 선택이며 payment selection은
  기존 후속 API로 분리한다. 과거 snapshot과 benefit을 자동 재사용하지 않는다. 기록: BR-03,
  ADR-077.
- 2026-08-09: unavailable source line 하나라도 있으면 typed item reason의 전체 409이고 부분 생성은
  없다. 기록: BR-03, ADR-077, error catalog.
- 2026-08-09: 201은 source/current 혜택 전 subtotal과 changed line만 담은 required price comparison을
  가진다. 차이는 current-source다. 기록: BR-03, ADR-077, target OpenAPI.
- 2026-08-09: source ownership은 기존 customer Order 정책과 같은 missing 404/other owner 403이며 과거
  개인정보·결제 정보는 노출하지 않는다. 기록: authorization matrix, ADR-077.
- 2026-08-09: 재주문은 direct create와 별도 operation을 쓰되 Tx I1/Tx O/Tx I2와 기존 reservation
  atomicity를 재사용한다. 기록: ADR-064, ADR-077, transaction boundaries.
- 2026-08-09: `Implementation-Ready=true`, `Writes-Migration=true`로 판정한다. 정책 미결정은 없지만
  option ID provenance와 Accepted idempotency retention drift를 닫는 migration이 필수이기 때문이다.

## Outcomes & Retrospective

아직 구현하지 않았다. 완료 시 실제 migration filename, 새/수정 source와 test, runtime operation 수,
실행한 모든 validation의 정확한 결과, 측정값과 미측정 항목을 기록한다. 성공 경로만 통과하거나
runtime OpenAPI가 target과 일치하지 않거나 legacy/item/idempotency/rollback test가 남아 있으면 이
plan을 completed로 이동하지 않는다.

## Revision Notes

- 2026-08-09: 공개 제품 문맥과 사용자 승인으로 빠른 재주문 정책·API·data revalidation boundary·
  failure semantics를 닫고 최초 implementation-ready plan 작성.
- 2026-08-09: 구현 baseline을 latest main `c7370a8`로 갱신하고 V36 migration-writer lease, stale
  detached worktree 비간섭과 pickup-start effective lease drift 정정 근거를 기록.
