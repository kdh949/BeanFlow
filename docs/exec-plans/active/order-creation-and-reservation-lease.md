# 주문을 생성하고 5분 예약 lease를 원자적으로 관리한다

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 이 문서는 living document이며 구현 중
`Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`를
계속 갱신한다.

## Purpose / Big Picture

인증된 고객이 `POST /api/v1/orders`를 호출하면 서버가 요청 당시 메뉴·옵션·가격과
혜택 배분을 Order에 고정하고, 픽업 슬롯·판매 재고·쿠폰·포인트를 하나의 PostgreSQL
트랜잭션에서 예약한다. 외부 결제가 필요한 주문은 `PENDING_PAYMENT`와 정확히 5분 뒤의
`reservationExpiresAt`을 반환한다. 경계 시각까지 결제가 승인되지 않으면 Order와
모든 예약 자원을 원자적으로 만료·해제하며, 동일 요청 재시도와 동시에 들어온 마지막
자원 경쟁은 중복 주문이나 초과 예약을 만들지 않는다.

완료 후 관찰 가능한 결과는 다음과 같다.

- 같은 고객·operation·`Idempotency-Key`와 같은 payload는 하나의 Order만 만든다.
- 주문 생성 성공은 Order와 필요한 모든 예약이 함께 존재한다는 뜻이다.
- 어느 하나라도 검증·예약에 실패하면 Order와 부분 예약은 하나도 커밋되지 않는다.
- `now < reservationExpiresAt`인 동안만 결제 전 예약이 유효하고,
  `now >= reservationExpiresAt`이면 결제 명령은 즉시 거부된다.
- 만료 worker가 재실행되거나 여러 인스턴스에서 동시에 실행돼도 자원은 한 번만
  해제되고 수량은 원래 값보다 커지지 않는다.
- 실패는 409/503, 영속 상태, metric, structured log 또는 AuditRecord로 드러나며
  빈 성공 응답이나 in-memory fallback으로 바뀌지 않는다.

이 문서는 구현 전에 해결한 8개 결정 게이트와 그 결과를 포함한다. Milestone 0의
결정 기록과 문서 검증이 완료된 뒤에만 Milestone 1 이후의 기능 코드를 작성한다.

## Current State

2026-07-28 현재 저장소는 Kotlin 2.3, Java 21, Spring Boot 4.1, Spring Modulith 2.1,
Spring Data JPA, Flyway, PostgreSQL과 Testcontainers 의존성을 가진 애플리케이션
shell이다.

현재 존재하는 구현은 다음뿐이다.

- `src/main/kotlin/io/github/kdh949/beanflow/BeanflowApplication.kt`
- `src/main/resources/application.yaml`의 애플리케이션 이름
- PostgreSQL Testcontainer를 시작하는 context load test

아직 다음은 없다.

- Context별 Spring Modulith module과 공개 Application API
- Order·예약·멱등·감사 도메인 모델
- Flyway migration
- Controller, 인증 actor 변환, API 오류 매핑
- 예약 만료 worker와 runtime observability
- 실제 주문 생성 테스트

현재 계약 원본은 다음 문서다.

- `docs/product/business-policy-decisions.md`: BR-01~05, BR-08~12, BR-25~26,
  BR-30
- `docs/adr/ADR-003-aggregate-reference-by-id.md`
- `docs/adr/ADR-004-order-price-snapshot.md`
- `docs/adr/ADR-005-reservation-transaction-strategy.md`
- `docs/adr/ADR-007-payment-idempotency-reconciliation.md`
- `docs/adr/ADR-009-explicit-failure-semantics.md`
- `docs/adr/ADR-011-point-lot-ledger.md`
- `docs/adr/ADR-013-payment-unknown-reservation-expiry.md`
- `docs/adr/ADR-014-money-allocation-and-partial-refund.md`
- `docs/adr/ADR-016-benefit-only-payment.md`
- `docs/adr/ADR-022-audit-record.md`
- `docs/architecture/context-map.md`
- `docs/architecture/aggregate-invariants.md`
- `docs/architecture/state-machines.md`
- `docs/architecture/transaction-boundaries.md`
- `docs/architecture/failure-semantics.md`
- `openapi/beanflow-v1.yaml`

### Confirmed alignment

다음은 문서 간에 일치하며 새 결정이 필요하지 않다.

- lease 길이는 5분이고 주문 생성 트랜잭션에서 한 번 고정한다.
- 결제 가능 구간은 `[createdAt, reservationExpiresAt)`이다. 정확한 경계 시각부터
  만료로 취급한다.
- lease 연장 API는 MVP에 없다.
- Order, 픽업 슬롯, 재고, 쿠폰과 포인트 예약은 공개 Application API를 통해 하나의
  로컬 PostgreSQL 트랜잭션에서 변경한다.
- 일부 실패는 전체 트랜잭션을 롤백한다.
- Payment가 `UNKNOWN`이어도 lease를 연장하지 않는다.
- 만료 후 뒤늦은 승인은 Order나 예약을 되살리지 않고 Payment의 void/refund
  reconciliation으로 보낸다. 해당 외부 결제 구현은 후속 ExecPlan 범위다.

### Conflicts and decision gates

아래 항목은 코드 작성 전에 지정된 기록 위치에서 해결하고 이 ExecPlan의
`Decision Log`를 갱신해야 한다.

1. **0원 주문과 Feature 범위 충돌 — Resolved**

   `docs/exec-plans/active/foundation-domain-model.md`는 Payment를 첫 Feature의
   non-goal로 두지만, BR-11과 ADR-016은 payable이 0원이면 외부 PG 호출 없이
   `BENEFIT_ONLY Payment(APPROVED)`를 만들고 Order를 `PAID`로 처리하도록 요구한다.
   0원 주문을 거부하거나 5분 `PENDING_PAYMENT`로 두는 선택은 Accepted 정책과
   충돌한다.

   확정안은 Payment 모듈의 최소 `BENEFIT_ONLY` 로컬 경로만 이 Feature에 포함하고,
   외부 PG 승인·UNKNOWN·reconciliation은 계속 제외하는 것이다. 이 경우 같은
   트랜잭션에서 Payment를 승인하고 네 예약을 확정한 뒤 Order를 `PAID`로 전환한다.
   BR-11, ADR-016, 상태 머신과 transaction boundary에 기록했다.

2. **쿠폰 계산 모델 부재 — Resolved**

   BR-08/09는 쿠폰 우선순위와 주문당 최대 한 개를 정하지만 Campaign이 정액인지
   정률인지, 최소 주문금액·최대 할인액·대상 메뉴·자동 할인과의 중복을 어떤 필드와
   규칙으로 표현하는지 정하지 않는다. `couponIssuanceId`가 있는 요청의
   `couponDiscountKrw`를 재현할 수 없다.

   확정안은 MVP Campaign에 `FIXED_KRW`와 `RATE_BPS`를 사용하고, 대상 메뉴 line의
   할인 전 합계만 minimum과 할인 계산 기준으로 삼는다. 정액은 대상 합계를 상한으로,
   정률은 basis point 버림 후 선택적 maximum을 적용한다. 대상 범위 생략은 store의
   모든 line, 빈 대상 집합은 invalid Campaign이다. BR-08과 ADR-024에 기록했다.

3. **재고 sellable unit 매핑 부재 — Resolved**

   요청은 `menuId + optionIds + quantity`를 전달하지만 Inventory 문서는
   `menuOptionId` 또는 추상적인 sellable unit 수량을 말한다. 기본 메뉴와 옵션 조합이
   어느 재고 단위를 몇 개 소비하는지 계약이 없다.

   확정안은 Merchant가 주문 가능한 메뉴 구성마다 정규화된
   `sellableUnitRequirements(sellableUnitId, quantityPerLineUnit)`를 반환하고
   Inventory는 그 ID만 소유·예약하는 것이다. 이 선택은 Merchant/Inventory 공개
   Application API와 schema에 반영한다. option ID 집합은 정렬해 구성 lookup에
   사용하고 OrderLine 순서는 유지하며, 같은 sellable unit 요구량은 주문 전체에서
   합산한다. 구성 부재는 400, 판매 불가는
   `409 MENU_CONFIGURATION_NOT_AVAILABLE`, 수량 부족은
   `409 STOCK_NOT_AVAILABLE`로 구분한다. ADR-026, Context Map, Aggregate
   Invariants, Ubiquitous Language, transaction boundary와 API 문서에 기록했다.

4. **포인트 예약의 영속 표현 부재 — Resolved**

   ADR-011은 PointLot과 PointTransaction을 정하지만 결제 전 예약이 어느 Lot의 얼마를
   점유하는지, 가용 잔액과 Lot 잔여액을 언제 줄이는지 명시하지 않는다. 금액만
   PointAccount에서 차감하면 결제 확정 시 발급 주체와 만료 순서를 재현하지 못한다.

   확정안은 주문 생성 시 만료가 빠른 PointLot부터 구체 allocation을 잠그고
   주문별 `PointReservation`과 하위 allocation을 영속화하며, Account의
   `availablePointsKrw`와 각 Lot의 `availableAmountKrw`를 줄인다. 결제 승인 시
   reservation을 USE 원장으로 확정하고, 만료 시 같은 allocation으로 복원한다.
   예약 시점에 유효한 allocation은 주문 lease 동안 사용을 보장한다. 해제 시 이미
   만료된 allocation은 available로 복원하지 않고 EXPIRATION 원장으로 확정한다.
   BR-03, ADR-011과 Loyalty 아키텍처 문서에 기록했다.

5. **주문 생성 실패의 멱등 결과 보존 방식 부재 — Resolved**

   BR-25는 정규화 payload hash, 처리 상태와 응답을 저장하라고 하지만 ADR-005는
   부분 예약 실패 시 전체 주문 트랜잭션을 롤백하라고 한다. 멱등 레코드를 같은
   트랜잭션에 넣으면 409 도메인 실패 시 레코드도 사라지고, 별도 선행
   트랜잭션에 넣으면 주문 커밋과 멱등 응답 완료 사이 crash 상태를 복구해야 한다.

   확정안은 주문 생성의 `IdempotencyRecord(PROCESSING)`을 짧은 선행 트랜잭션에서
   insert-first로 확정하고, 주문·예약 트랜잭션이 Order ID와 성공 응답을
   `COMPLETED`로 연결하도록 하는 것이다. 확정된 도메인 거절은 별도 짧은
   트랜잭션에서 `FAILED` 응답으로 저장한다. `PROCESSING`에서 멈춘 레코드는 Order ID
   존재 여부를 확인하는 reconciliation으로만 완료하며 새 Order를 자동 생성하지
   않는다. 같은 key/hash replay는 최초 201/4xx/503을 그대로 반환하고 처리 중이면
   `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 반환한다. BR-25,
   ADR-025, transaction boundary, API 문서와 OpenAPI에 기록했다.

6. **lease의 논리 만료와 물리 해제 시점 — Resolved**

   worker, Order 조회와 결제 명령은 `now >= reservationExpiresAt`이면 같은
   idempotent expiry transaction을 먼저 실행한다. 조회는 Order와 네 자원 해제가
   commit된 뒤 `EXPIRED`를 반환하고, 결제는 409로 거부한다. expiry 실패는 stale
   성공 대신 503이며 worker 또는 다음 요청이 재시도한다. BR-03, ADR-013, 상태
   머신, transaction boundary, API 문서와 OpenAPI에 기록했다.

7. **AuditRecord 범위 누락 — Resolved**

   이 Feature는 주문 생성과 각 예약 결과, BENEFIT_ONLY 승인·확정, Order `EXPIRED`와
   각 자원 해제를 변경 target별 AuditRecord로 남기고 correlation/source로 묶는다.
   고객 생성은 Customer actor와 `CUSTOMER_ORDER_CREATION`, deadline 만료는 SYSTEM
   actor와 `LEASE_DEADLINE_REACHED`를 사용하며 자유 입력 reason은 수동 명령에만
   필수다. record는 `occurredAt`의 `Asia/Seoul` 달력 5주년까지 보존하고 내부
   retention worker가 due record만 chunk 삭제한다. BR-30, ADR-022와 transaction
   boundary에 기록했다.

8. **OpenAPI의 lease 필드가 선택 사항 — Resolved**

   `POST /orders`의 201은 `{order, payment?}` envelope의 `oneOf`으로 확정했다.
   외부 결제 variant는 `PENDING_PAYMENT`와 required `reservationExpiresAt`,
   payment 없음이다. 0원 variant는 `PAID`, payable 0, active deadline 없음과
   required `BENEFIT_ONLY Payment(APPROVED)`다. API conventions와 OpenAPI에
   반영했다.

## Definitions

- **Reservation lease:** 결제 전 자원을 독점적으로 점유할 수 있는 유한 시간 구간.
- **Logical expiry:** 현재 시각이 deadline에 도달해 더 이상 결제·확정할 수 없는 의미.
- **Physical release:** DB에서 Order와 각 예약 상태·수량을 실제 만료·복원하는 작업.
- **Sellable unit:** Inventory가 수량을 소유하는 최소 판매 재고 식별자. Menu나 Option
  객체 자체와 동일하다고 가정하지 않는다.
- **Price snapshot:** 주문 이후 Menu·Campaign이 바뀌어도 재현 가능한 이름, 옵션,
  단가, 할인, 포인트, 현금 배분 값.
- **Idempotency scope:** `actorId + API operation + Idempotency-Key`.
- **Payload hash:** 계약에서 정한 canonical payload의 SHA-256. 객체 key 순서는
  제거하되 배열 순서는 유지한다. Order line 배열 순서는 BR-12 잔여 1원 배분의
  tie-breaker이므로 의미가 있다. `optionIds` 순서의 의미는 Milestone 0에서
  정규화 규칙으로 확정한다.
- **Guarded transition:** 현재 상태, version과 deadline 조건을 DB update 또는 잠금
  안에서 함께 검사해 경쟁 요청 중 하나만 상태를 바꾸게 하는 전이.
- **Owner Context:** 해당 write data와 불변식을 소유하고 공개 Application API를
  제공하는 모듈.
- **AuditRecord:** 중요 상태·금액·자원 변경을 actor, action, target, 전후 요약,
  correlation로 남기는 append-only 기록.

## Scope

### In Scope

- Milestone 0의 정책·ADR·OpenAPI 결정 보완
- Merchant, Ordering, Fulfillment, Inventory, Promotion, Loyalty, Operations의
  Spring Modulith 경계와 필요한 최소 공개 Application API
- Payment의 `BENEFIT_ONLY` 최소 로컬 경로
- 테스트 fixture가 사용할 최소 Store, Menu, MenuOption과 자원 owner write model
- Order, OrderLine, 주문 생성 IdempotencyRecord
- 픽업 슬롯, 재고, 선택적 쿠폰과 선택적 포인트의 5분 예약
- 정수 KRW 계산과 BR-12의 결정적 OrderLine 배분 snapshot
- `POST /api/v1/orders`와 안정적인 오류 코드
- 실제 PostgreSQL Flyway schema, Unique/Check/FK/Index와 concurrency control
- 고정 `Clock`, `reservationExpiresAt = createdAt + 5 minutes`
- 논리 만료 guard와 재실행 가능한 물리 만료 worker
- 주문 생성·예약·만료 AuditRecord, metric와 structured log
- 도메인, Application, Repository, API 계약, Modulith, 동시성, 멱등성, 시간 경계
  테스트

### Non-goals

- 외부 PG Adapter, 1원 이상 결제 승인, Payment `UNKNOWN`과 reconciliation 구현
- 만료 후 late approval void/refund 구현
- 고객 주문 취소
- 매장 수락·거절·3분 timeout
- 주문 제조·준비·픽업 완료
- 결제 후 재고·슬롯·쿠폰·포인트 확정 경로. 단, ADR-016의 `BENEFIT_ONLY` 확정만
  예외다.
- 포인트 적립, 환불, 정산, 이의제기, 알림, Analytics
- 메뉴·캠페인·포인트 발급을 관리하는 외부 CRUD API
- Redis, Kafka, Kubernetes, 별도 scheduler 제품 또는 in-memory repository
- 측정하지 않은 처리량·지연 개선 주장
- 기존 production data backfill. 현재 production schema와 data는 없다.

## Business Rules and Invariants

### Order and money

- 고객 actor ID는 인증 정보에서만 가져오며 request body로 받지 않는다.
- Store는 영업·픽업 가능 상태이고 모든 Menu와 PickupSlot은 같은 store에 속해야 한다.
- 요청 line은 한 개 이상이고 quantity는 1 이상이다.
- Menu와 Option은 주문 가능 상태여야 하고 Option은 해당 Menu에 속해야 한다.
- OrderLine은 request 배열 순서를 0부터 증가하는 `lineSequence`로 고정한다.
- `unitPriceKrw = basePriceKrw + selected option additionalPriceKrw`이고 모든 금액은
  non-negative signed 64-bit integer KRW다. 더하기·곱하기 overflow는
  `INVALID_REQUEST`로 실패하고 값을 wrap하지 않는다.
- `lineGrossKrw = unitPriceKrw * quantity`,
  `subtotalKrw = sum(lineGrossKrw)`다.
- 쿠폰 할인 후 포인트를 적용한다.
- `0 <= couponDiscountKrw <= subtotalKrw`.
- `0 <= pointsAppliedKrw <= subtotalKrw - couponDiscountKrw`.
- `payableKrw = subtotalKrw - couponDiscountKrw - pointsAppliedKrw`.
- 쿠폰은 eligible line의 할인 전 gross만 기준으로 eligible line에 배분하고 비대상
  line에는 0을 배분한다.
- 포인트는 coupon 적용 후 각 line의 잔액 비율로 모든 line에 배분한다.
- 현금은 각 line의 `gross - coupon - points` 잔액이다.
- 각 비율 배분 단계에서 원 미만을 버린 뒤 잔여 원은 해당 단계 기준 금액이 큰 line,
  동률이면 `lineSequence`가 작은 line부터 1원씩 배분한다.
- 각 배분 합계는 Order 합계와 정확히 같고 각 line에서
  `coupon + points + cash = lineGross`다.
- Order 생성 뒤 가격·항목·배분 snapshot은 수정하지 않는다.

### Reservation

- 외부 결제가 필요한 Order는 생성 시 `PENDING_PAYMENT`다.
- 생성 트랜잭션의 단일 `Clock.instant()` 값을 `createdAt`으로 사용하고
  `reservationExpiresAt = createdAt + Duration.ofMinutes(5)`로 고정한다.
- 유효 구간은 `now < reservationExpiresAt`; 경계와 이후는 expired다.
- Order마다 active PickupReservation은 하나뿐이다.
- PickupSlot의 `reservedCount + confirmedCount`는 capacity를 넘지 않는다.
- Order와 sellable unit마다 active StockReservation은 하나뿐이며 합산 수량은
  stock의 available 수량을 넘지 않는다.
- CouponIssuance는 동시에 한 Order에서만 RESERVED/USED일 수 있다.
- 포인트 예약은 PointAccount와 선택된 PointLot의 available 금액을 음수로 만들지
  않는다.
- 동일 Order source reference의 예약·해제·확정은 한 번만 적용된다.
- Order와 필요한 네 자원 예약은 모두 커밋되거나 모두 롤백된다.
- 만료는 `PENDING_PAYMENT -> EXPIRED`와 네 자원 해제를 한 로컬 트랜잭션에서
  수행한다.
- 만료 worker 재실행은 이미 terminal인 예약 수량을 다시 복원하지 않는다.

### Idempotency

- scope unique key는 `(actor_id, operation, idempotency_key)`다.
- payload hash가 같으면 기존 Order의 현재 허용 representation을 반환하고 새 Order나
  예약을 만들지 않는다.
- payload hash가 다르면 자원을 조회·잠그기 전에
  `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.
- 동시에 같은 scope가 들어오면 DB Unique Constraint의 승자만 명령을 실행한다.
- `PROCESSING` 상태를 성공이나 빈 결과로 반환하지 않는다. 확정된 응답이 없으면
  명시적 pending/conflict 계약 또는 reconciliation 상태를 사용한다. 정확한 HTTP
  표현은 Milestone 0에서 OpenAPI에 확정한다.

### Security and failure

- Customer만 자신의 Order를 생성한다.
- Controller는 Repository를 직접 호출하지 않는다.
- DB·필수 owner API 실패는 503 또는 transaction failure이며 부분 성공을 반환하지
  않는다.
- 운영 profile에서 fake owner Adapter나 in-memory repository를 선택하지 않는다.
- 오류 응답에는 stable code와 correlation ID가 있고 SQL, stack trace, secret,
  point lot 내부 정보를 노출하지 않는다.

## Architecture and Transaction Boundaries

### Module shape

각 최상위 package는 Spring Modulith module이다. 다른 모듈은 `api` package만 참조하고
`internal` package와 JPA Repository를 참조하지 않는다.

```text
io.github.kdh949.beanflow
├── merchant.api
├── merchant.internal
├── ordering.api
├── ordering.internal
├── fulfillment.api
├── fulfillment.internal
├── inventory.api
├── inventory.internal
├── promotion.api
├── promotion.internal
├── loyalty.api
├── loyalty.internal
├── payment.api                 # BENEFIT_ONLY 최소 공개 API
├── payment.internal            # BENEFIT_ONLY 최소 로컬 구현
└── operations.api / internal
```

`Ordering`의 `CreateOrderService`가 orchestration과 transaction boundary를 소유한다.
각 owner API는 전달된 Order ID, actor ID, `reservationExpiresAt`, source reference를
검증하고 자신의 Aggregate만 변경한다.

### Create flow

ADR-025의 멱등 transaction을 기준으로 성공 흐름은 다음 순서를 사용한다.

1. Security principal을 Customer actor로 변환하고 correlation ID를 확보한다.
2. idempotency scope와 canonical payload hash를 등록하거나 기존 결과를 반환한다.
3. UUID supplier로 Order ID와 OrderLine ID를 생성하고 `Clock.instant()`를 한 번 읽는다.
4. Merchant 공개 API로 store, menu, option, unit price와 sellable unit 요구량을
   snapshot한다.
5. Merchant quote의 sellable requirement를 주문 전체에서 합산한다.
6. 모든 요청이 같은 global lock order를 사용해 Fulfillment와 Inventory 예약을
   먼저 영속화한다. PickupSlot ID 한 개 뒤 정렬된 sellable unit ID 순서다.
7. Promotion 공개 API로 선택적 CouponIssuance를 잠가 검증하고 할인 금액·대상 line·
   비용 부담 snapshot을 계산해 예약한다.
8. Ordering의 순수 allocator로 line별 coupon, points, cash 배분을 계산한 뒤
   Loyalty가 PointAccount ID와 `(expiresAt, pointLotId)` 순서로 선택적 포인트
   reservation을 영속화한다. 선택하지 않은 쿠폰과 0 point에는 reservation을 만들지
   않는다.
9. Ordering이 immutable snapshot과 `PENDING_PAYMENT` Order를 저장한다.
10. Operations 공개 API가 Order와 각 자원 변경의 AuditRecord를 append한다.
11. idempotency record를 Order ID와 201 response snapshot에 연결하고 커밋한다.

payable이 0이면 8번까지 임시 예약한 뒤 같은 transaction에서
`BENEFIT_ONLY Payment(APPROVED)`를 만들고 owner API로 예약을 확정한 다음 Order를
`PAID`로 저장한다. 이 Order는 active lease 대상이 아니며 create 응답은 상태별
OpenAPI schema를 따른다.

### Expiry flow

만료 후보 조회와 실제 처리를 분리한다.

1. indexed query가 `state=PENDING_PAYMENT AND reservation_expires_at <= now`인 Order
   ID를 `(reservation_expires_at, order_id)` 순서로 제한된 chunk만 조회한다.
2. 각 Order ID는 별도 transaction에서 다시 로드·잠금한다.
3. 이미 `PAID`, `EXPIRED`, `CANCELLED`이면 no-op 성공으로 가장하지 않고
   `not eligible` 결과와 metric을 남긴다. 자원 수량은 바꾸지 않는다.
4. 여전히 `PENDING_PAYMENT`이고 deadline에 도달했으면 Order를 `EXPIRED`로 전이하고
   Fulfillment, Inventory, Promotion, Loyalty 공개 API로 같은 source reference를
   해제한다.
5. Order와 각 해제 AuditRecord를 append하고 한 transaction으로 commit한다.
6. 한 owner release가 실패하면 Order 전이와 다른 release도 롤백된다. 다음 worker
   실행이 같은 Order를 다시 시도한다.

worker의 schedule delay, chunk size와 instance 수는 configuration property로
노출하되 5분 비즈니스 deadline 자체는 설정으로 바꾸지 않는다. 기본값을 정할 때는
측정값으로 표현하지 않고 운영상 초기 `Assumption`으로 기록한다.

### Concurrency control

- Order와 stateful reservation row는 optimistic `version` 또는 명시적 row lock과
  guarded update를 사용한다.
- capacity·available 수량 변경은 읽고 저장하는 application check만 사용하지 않고
  row lock 또는 `... WHERE available >= requested` conditional update로 보호한다.
- global owner/ID lock 순서를 모든 create 경로에서 동일하게 유지한다.
- Unique Constraint는 active order reservation, stock order/unit,
  coupon active owner, point order reservation과 idempotency scope를 최종 방어한다.
- 동시성 테스트는 `Thread.sleep` 순서에 기대지 않고 barrier/latch와 별도 transaction을
  사용한다.

## Alternatives Considered

### Context별 transaction과 Saga

예약마다 commit하고 실패 시 보상하는 방법은 향후 서비스 분리에는 유리하지만 현재
Accepted ADR-005와 충돌하고 부분 보상·재시도 상태가 크게 늘어난다. 이번 Feature에서는
사용하지 않는다.

### Redis lock 또는 queue

DB 원본과 별도 lock의 lease·장애 의미가 추가된다. 현재 PostgreSQL constraint와 row
lock으로 검증하기 전에는 도입 근거가 없다.

### 만료 worker만 믿고 command-path deadline을 검사하지 않음

worker가 늦으면 5분이 지난 예약이 결제 가능한 것처럼 보인다. 논리 deadline guard와
물리 release를 분리하는 추천안보다 정책을 약하게 만든다.

### 멱등 레코드를 주문 transaction에서만 저장

성공은 원자적으로 단순하지만 domain failure rollback 뒤 같은 key의 결과를 재사용할
수 없고 insert-first arbitration의 crash 상태를 설명하지 못한다. Milestone 0에서
실패 보존 의미와 함께 최종 선택한다.

### 포인트를 Account 총액으로만 예약

PointLot 만료 순서와 발급 주체 비용을 결제 시 재현하지 못해 ADR-011/017의 후속
정합성을 해친다.

### 0원 주문을 이번 Feature에서 거부

범위는 작아지지만 Accepted BR-11/ADR-016과 공개 API의 points 사용 계약을 위반한다.
추천하지 않는다.

## Failure Semantics

| Failure | HTTP / state | Persisted result | Recovery |
|---|---|---|---|
| invalid field, overflow, invalid menu option | 400 `INVALID_REQUEST` | 결정된 멱등 정책에 따른 failed response | payload 수정 |
| store/menu unavailable | 409 또는 404의 Milestone 0 계약 | 부분 예약 없음 | 새 주문 요청 |
| slot capacity exhausted | 409 `PICKUP_SLOT_FULL` | 부분 예약 없음 | 다른 slot/key |
| stock insufficient | 409 `STOCK_NOT_AVAILABLE` | 부분 예약 없음 | 수량/메뉴 변경 |
| coupon invalid or contended | 409 `COUPON_NOT_AVAILABLE` | 부분 예약 없음 | 쿠폰 제거/변경 |
| points insufficient or contended | 409 `POINT_BALANCE_INSUFFICIENT` | 부분 예약 없음 | point 금액 변경 |
| same key, different payload | 409 `IDEMPOTENCY_KEY_REUSED` | 기존 record 유지 | 새 key |
| DB unavailable before commit | 503 `DEPENDENCY_UNAVAILABLE` | 성공 Order 없음 | 같은 key 안전 재시도 |
| owner reservation exception | request failure | 전체 create transaction rollback | 같은 key 정책에 따른 재시도 |
| expiry owner release exception | Order remains `PENDING_PAYMENT` physically, logically expired | rollback + failure metric/log | worker retry |
| worker process restart | no fabricated success | due Order remains queryable | chunk 재실행 |

DB 장애를 in-memory repository로 바꾸지 않는다. 만료 release 실패를 `EXPIRED` 성공으로
커밋하지 않는다. worker가 늦는 동안 결제는 deadline guard로 거부하되 물리 release
완료 여부를 metric과 log에서 구분한다.

## Data and Migration

Milestone 0의 결정으로 이름이 바뀔 수 있지만 owner와 constraint는 다음을 만족해야
한다. Aggregate 간 JPA 객체 연관관계 대신 ID를 저장하고, 같은 DB에 존재해 유효한
owner 참조만 필요한 곳은 FK를 검토한다.

### Core tables

- `merchant_store`: ID, 상태, pickup 가능 여부, version
- `merchant_menu`: ID, store ID, name snapshot source, base price KRW, 상태, version
- `merchant_menu_option`: ID, menu ID, name, additional price KRW, 상태
- `merchant_menu_configuration`과 sellable unit requirement mapping
- `fulfillment_pickup_slot`: ID, store ID, starts/ends at, capacity, reserved count,
  confirmed count, version
- `fulfillment_pickup_reservation`: ID, order ID, slot ID, state, expires at,
  source reference, timestamps, version
- `inventory_sellable_stock`: ID, store ID, available/reserved/confirmed quantity,
  version
- `inventory_stock_reservation`: ID, order ID, sellable unit ID, quantity, state,
  expires at, source reference, version
- ADR-024의 `promotion_campaign`
- `promotion_coupon_issuance`: ID, campaign ID, member ID, state, reserved order ID,
  expires at, version
- `loyalty_point_account`, `loyalty_point_lot`, `loyalty_point_reservation`과 allocation,
  `loyalty_point_transaction`
- `ordering_order`: ID, customer ID, store ID, state, subtotal/discount/points/payable
  KRW, currency, created/updated/reservation expiry, version
- `ordering_order_line`: ID, order ID, line sequence, menu ID, menu/option snapshot,
  unit price, quantity, gross/coupon/points/cash KRW
- `ordering_idempotency_record`: actor ID, operation, key, payload hash, status,
  order ID, stored HTTP status/body or typed response fields, timestamps, version
- 최소 `payment_payment`의 BENEFIT_ONLY record
- `operations_audit_record`: append-only action/target/actor/time/reason,
  before/after summary, correlation ID, retention expiry

### Required DB reinforcement

- 모든 금액·수량·capacity에 non-negative CHECK
- `reserved_count + confirmed_count <= capacity` CHECK
- Order line `(order_id, line_sequence)` UNIQUE
- Order number를 노출한다면 별도 UNIQUE; 이 Feature는 임의 형식을 만들지 않는다.
- active pickup reservation의 `order_id` UNIQUE
- stock reservation `(order_id, sellable_unit_id)` UNIQUE
- coupon의 한 active order를 보장하는 partial UNIQUE 또는 guarded state update
- point reservation의 source order UNIQUE와 allocation amount CHECK
- idempotency `(actor_id, operation, idempotency_key)` UNIQUE
- source reference UNIQUE
- due query용 `(state, reservation_expires_at, id)` index
- audit 조회용 `(target_type, target_id, occurred_at)`와 correlation index
- timestamps는 PostgreSQL `timestamptz`, 금액은 `bigint`, ID는 UUID를 기본 후보로
  사용한다. Identifier 공개 형식 변경이 필요하면 OpenAPI와 함께 결정한다.

Flyway는 owner별로 읽을 수 있는 migration으로 나누고 Hibernate는 production과
test 모두 schema를 자동 생성하지 않고 `validate`만 수행한다. H2는 사용하지 않는다.
기존 production data가 없으므로 backfill과 dual-write는 없다.

## API and Event Contracts

### `POST /api/v1/orders`

- Bearer 인증과 `Idempotency-Key` header가 필수다.
- request는 현재 `CreateOrderRequest`의 store ID, pickup slot ID, line 배열, 선택적
  coupon issuance ID와 `pointsToUseKrw`를 유지한다.
- 201은 `{order, payment?}` 생성 결과 envelope이며 실제 Order와 모든 필요한
  예약·해당 Payment가 commit된 뒤에만 반환한다.
- 외부 결제 필요 branch는 `state=PENDING_PAYMENT`와 required
  `reservationExpiresAt`을 반환한다.
- 0원 branch는 `state=PAID`, payable 0, active lease 없음과 필수
  `BENEFIT_ONLY Payment(APPROVED)`를 상태별 schema로 반환한다.
- 같은 key/payload 재시도는 새 생성이 아니라 기존 Order의 현재 representation을
  반환한다. status code를 항상 최초 201로 재사용할지 200으로 바꿀지는 Milestone 0
  멱등 계약에서 확정한다.

Milestone 0에서 `openapi/beanflow-v1.yaml`, `docs/api/api-conventions.md`,
`docs/api/error-catalog.md`를 같은 변경으로 갱신한다. generic 409 response만 두지 않고
위 stable code가 contract test에서 검증되게 한다.

### Events

이번 Feature의 원자적 reservation orchestration은 동기 Application API다.
`OrderPlaced`를 발행하더라도 예약 성공을 event delivery에 맡기지 않는다. 후속
소비자가 생기기 전에는 불필요한 persistent event를 만들지 않는다.

`OrderExpired`가 후속 Payment/Operations 소비자를 가지기 시작하면 원본 Order
transaction과 함께 영속 publication을 기록한다. 이번 Feature에서 event를 실제
발행한다면 Spring Modulith Event Publication Registry 재시작·중복 소비 테스트를
같이 포함하고, 아니면 Event Catalog에 “계약만 정의, producer 미구현” 상태를
명시한다.

## Milestones

### Milestone 0: 결정 게이트와 계약을 먼저 닫는다

**Description:** 위 8개 conflict/gap을 사용자와 한 번에 한 초점씩 결정하고 정책,
ADR, 아키텍처, OpenAPI에 먼저 기록한다. transcript가 아니라 결정·근거·결과만 남긴다.

**Acceptance criteria:**

- [x] 0원 주문의 이번 Feature 포함 여부와 transaction이 ADR-016 및 scope에 일치한다.
- [x] coupon model, sellable unit mapping, point reservation model이 owner API와
      schema를 구현할 수 있을 만큼 구체적이다.
- [x] 주문 생성 IdempotencyRecord의 success/domain failure/crash 상태와 HTTP 재사용
      의미가 ADR-007, state machine, transaction boundary, OpenAPI에 일치한다.
- [x] logical expiry, physical release, AuditRecord와 create response의
      `reservationExpiresAt` 계약이 문서화된다.

**Verification:**

- [x] `bash scripts/verify-docs.sh`
- [x] 관련 BR, ADR, OpenAPI, architecture diff의 용어·상태·경계 수동 대조

**Dependencies:** None

**Files likely touched:**

- `docs/product/business-policy-decisions.md`
- `docs/adr/ADR-007-payment-idempotency-reconciliation.md`
- `docs/adr/ADR-011-point-lot-ledger.md`
- `docs/adr/ADR-016-benefit-only-payment.md`
- 필요한 새 ADR 또는 관련 ADR amendment
- `docs/architecture/aggregate-invariants.md`
- `docs/architecture/state-machines.md`
- `docs/architecture/transaction-boundaries.md`
- `docs/api/api-conventions.md`
- `docs/api/error-catalog.md`
- `openapi/beanflow-v1.yaml`
- 이 ExecPlan

**Estimated scope:** Documentation L; 결정을 나눠 각 변경을 M 이하로 수행한다.

**Status:** Completed on 2026-07-28. 8개 게이트를 모두 Accepted 결정으로 기록했고
`bash scripts/verify-docs.sh`는 32개 정책, 26개 ADR, 60개 Markdown 파일과 OpenAPI
13개 path/43개 schema를 검증했다. 별도 상태별 OpenAPI assertion도 통과했다.

### Milestone 1: 모듈 경계와 공통 테스트 기반을 만든다

**Description:** owner package와 공개 API/internal 경계를 만들고 Clock, UUID,
correlation port, PostgreSQL integration test fixture와 Spring Modulith 구조 검증을
준비한다.

**Acceptance criteria:**

- [ ] 다른 module internal package 접근과 Repository 직접 호출이 구조 테스트에서
      실패한다.
- [ ] production profile은 DB 설정이 없거나 잘못되면 시작 실패하고 fake repository로
      전환하지 않는다.
- [ ] 모든 시간 의존 서비스가 주입된 `Clock`을 사용한다.

**Verification:**

- [ ] `./gradlew test --tests '*ModularityTests'`
- [ ] `./gradlew test --tests '*ApplicationContextTests'`

**Dependencies:** Milestone 0

**Files likely touched:**

- `src/main/kotlin/io/github/kdh949/beanflow/*/api/`
- `src/main/kotlin/io/github/kdh949/beanflow/*/internal/`
- module declaration files
- `src/test/kotlin/io/github/kdh949/beanflow/architecture/`
- `src/main/resources/application.yaml`

**Estimated scope:** M per module declaration slice

### Milestone 2: 가격 snapshot과 혜택 배분을 순수 도메인으로 만든다

**Description:** Merchant quote와 Order/OrderLine, 정수 KRW value, overflow guard,
쿠폰→포인트 계산과 BR-12 deterministic allocator를 구현한다.

**Acceptance criteria:**

- [ ] 메뉴/옵션 변경 후에도 생성된 OrderLine snapshot 값은 변하지 않는다.
- [ ] line·Order의 coupon/points/cash 합계가 모든 rounding case에서 tie-out한다.
- [ ] invalid option ownership, 음수·overflow와 payable 초과 points가 명시적으로
      실패한다.

**Verification:**

- [ ] `./gradlew test --tests '*OrderPricing*' --tests '*OrderTest'`

**Dependencies:** Milestone 1

**Files likely touched:**

- `src/main/kotlin/io/github/kdh949/beanflow/merchant/`
- `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/domain/`
- `src/test/kotlin/io/github/kdh949/beanflow/ordering/`

**Estimated scope:** M per Merchant quote / allocator slice

### Milestone 3: 픽업 슬롯과 재고 예약을 PostgreSQL로 보호한다

**Description:** Fulfillment와 Inventory owner API, Aggregate, JPA adapter, migration을
구현하고 capacity/quantity contention을 DB에서 방어한다.

**Acceptance criteria:**

- [ ] 마지막 slot capacity에 동시 주문이 와도 성공 합계가 capacity를 넘지 않는다.
- [ ] 마지막 stock에 동시 주문이 와도 available/reserved/confirmed 합계가 깨지지
      않는다.
- [ ] 같은 Order source를 재요청해도 예약 행과 수량 증가가 한 번뿐이다.

**Verification:**

- [ ] `./gradlew test --tests '*PickupReservationRepositoryTest'`
- [ ] `./gradlew test --tests '*StockReservationRepositoryTest'`
- [ ] Testcontainers PostgreSQL에서 constraint와 lock 경합 확인

**Dependencies:** Milestones 0, 1, 2

**Files likely touched:**

- `src/main/kotlin/io/github/kdh949/beanflow/fulfillment/`
- `src/main/kotlin/io/github/kdh949/beanflow/inventory/`
- `src/main/resources/db/migration/`
- 대응 test package

**Estimated scope:** M per owner Context

### Milestone 4: 쿠폰과 포인트 예약을 PostgreSQL로 보호한다

**Description:** 결정된 Campaign과 Point reservation model을 Promotion/Loyalty owner
API와 실제 schema로 구현한다.

**Acceptance criteria:**

- [ ] 한 CouponIssuance를 두 주문이 동시에 예약할 수 없다.
- [ ] 두 주문의 동시 point 예약 합계가 Account와 Lot의 available 금액을 넘지 않는다.
- [ ] PointLot 선택은 `(expiresAt, pointLotId)`로 결정적이고 reservation allocation
      합계가 요청 금액과 같다.

**Verification:**

- [ ] `./gradlew test --tests '*CouponReservationRepositoryTest'`
- [ ] `./gradlew test --tests '*PointReservationRepositoryTest'`
- [ ] 원장·Account·Lot reservation tie-out

**Dependencies:** Milestones 0, 1, 2

**Files likely touched:**

- `src/main/kotlin/io/github/kdh949/beanflow/promotion/`
- `src/main/kotlin/io/github/kdh949/beanflow/loyalty/`
- `src/main/resources/db/migration/`
- 대응 test package

**Estimated scope:** M per owner Context

### Milestone 5: 주문 생성을 한 transaction과 멱등 계약으로 연결한다

**Description:** CreateOrderService, Order persistence, IdempotencyRecord arbitration,
Controller, security ownership과 API error mapping을 연결한다.

**Acceptance criteria:**

- [ ] 각 reservation 단계 뒤 fault injection에서 Order와 모든 owner 변경이 0건이다.
- [ ] 같은 key/payload 순차·동시 요청은 Order 한 건과 owner reservation 한 세트만
      만든다.
- [ ] 같은 key/different payload는 owner lock 전에 409를 반환한다.
- [ ] 201 body와 OpenAPI schema가 snapshot 금액, state와 deadline을 정확히 표현한다.

**Verification:**

- [ ] `./gradlew test --tests '*CreateOrderServiceTest'`
- [ ] `./gradlew test --tests '*CreateOrderConcurrencyTest'`
- [ ] `./gradlew test --tests '*OrderControllerContractTest'`

**Dependencies:** Milestones 0, 2, 3, 4

**Files likely touched:**

- `src/main/kotlin/io/github/kdh949/beanflow/ordering/`
- `src/main/kotlin/io/github/kdh949/beanflow/shared/web/` 또는 기존 error package
- `src/main/resources/db/migration/`
- `src/test/kotlin/io/github/kdh949/beanflow/ordering/`

**Estimated scope:** 여러 M slice로 분리

### Milestone 6: 5분 만료·해제와 감사를 구현한다

**Description:** command-path deadline guard, due query, per-Order expiry transaction,
owner release API, AuditRecord와 observability를 구현한다.

**Acceptance criteria:**

- [ ] `expiresAt - 1ns`에는 유효하고 정확한 경계와 이후에는 결제가 거부된다.
- [ ] expiry transaction의 어느 release 단계가 실패해도 Order와 다른 release가
      commit되지 않는다.
- [ ] worker 중복·재시작·두 instance 경쟁에서 각 수량 복원은 한 번뿐이다.
- [ ] terminal Order와 자원 변경 AuditRecord가 같은 transaction에서 존재한다.

**Verification:**

- [ ] `./gradlew test --tests '*ReservationExpiry*'`
- [ ] 고정 Clock 경계 테스트
- [ ] worker 재실행 및 concurrent worker Testcontainers test
- [ ] audit masking/append-only test

**Dependencies:** Milestones 0, 5

**Files likely touched:**

- `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/application/`
- 각 owner의 release API/implementation
- `src/main/kotlin/io/github/kdh949/beanflow/operations/`
- `src/main/resources/application.yaml`
- `src/main/resources/db/migration/`
- 대응 test package

**Estimated scope:** 여러 M slice로 분리

### Milestone 7: 0원 BENEFIT_ONLY branch를 닫는다

**Description:** ADR-016에 따라 최소 Payment record와 같은 transaction의
reservation confirmation, Order `PAID` 전이를 구현한다.

**Acceptance criteria:**

- [ ] payable 0은 Provider port 호출 없이 Payment 한 건, Order `PAID`, confirmed
      resource를 만든다.
- [ ] payable 1원 이상은 BENEFIT_ONLY 경로에 진입할 수 없다.
- [ ] 같은 key 동시 요청에서 Payment와 확정 부수효과가 한 번뿐이다.

**Verification:**

- [ ] `./gradlew test --tests '*BenefitOnlyOrderCreationTest'`
- [ ] Provider 호출 0회와 금액·예약 tie-out

**Dependencies:** Milestones 0, 5, 6

**Files likely touched:**

- `src/main/kotlin/io/github/kdh949/beanflow/payment/`
- Ordering과 owner confirmation API
- `src/main/resources/db/migration/`
- 대응 test package

**Estimated scope:** M

### Checkpoint: complete feature verification

- [ ] `./gradlew clean test`
- [ ] `bash scripts/verify-docs.sh`
- [ ] OpenAPI와 MockMvc contract 일치
- [ ] Spring Modulith verify와 module dependency 문서 통과
- [ ] PostgreSQL constraint, rollback, concurrency, idempotency, time 경계 통과
- [ ] 미실행 검증은 `Not run`으로 기록
- [ ] 측정하지 않은 성능 결과를 주장하지 않음
- [ ] 최종 diff에 secret, 개인 문맥, fixture credential과 범위 밖 리팩터링이 없음

## Required Tests

### Domain unit

- Money add/multiply overflow와 non-negative invariant
- option price 합산
- coupon→points 순서
- 다품목 비율 배분, 1원 잔여, 동률 line sequence
- Order `PENDING_PAYMENT -> EXPIRED` 허용 상태와 terminal 중복 전이
- 정확한 5분 경계

### Application

- 정상 create와 owner API 호출 순서
- coupon 없음, points 0, 둘 다 있음
- 각 owner 실패 지점의 전체 rollback
- 잘못된 store/menu/slot 교차 참조
- 같은 key same/different payload
- 인증 actor와 Order owner
- 결정되면 BENEFIT_ONLY branch

### PostgreSQL repository

- 모든 CHECK/UNIQUE/FK/partial index
- slot·stock·coupon·point 마지막 자원 contention
- lock 순서와 deadlock 없는 mixed request
- due-order index query와 정렬
- expiration 중복 release
- Flyway migration + Hibernate validate

### API contract and security

- 201 create body, required deadline, integer KRW, ISO-8601 offset/UTC
- 400/401/403/404/409/503 error envelope와 correlation ID
- 각 stable resource conflict code
- same key replay response
- Customer 외 role 주문 생성 거부
- 다른 actor ID를 body로 주입할 수 없음

### Architecture and resilience

- Spring Modulith verify
- Controller→Repository, cross-module internal 접근 금지
- DB 장애가 empty/success/in-memory fallback으로 바뀌지 않음
- worker 실패와 restart
- AuditRecord append-only와 민감정보 absence

## Validation Commands

구현 완료 시 저장소 root에서 다음을 실행한다.

```bash
bash scripts/verify-docs.sh
./gradlew clean test
./gradlew test --tests '*ModularityTests'
./gradlew test --tests '*CreateOrder*' --tests '*ReservationExpiry*'
```

현재 별도 Kotlin lint/static-analysis task와 full OpenAPI semantic validator는 구성되지
않았다. 새 production dependency를 이 Feature 편의만으로 추가하지 않는다. 구성하지
않으면 최종 결과에 각각 `Not configured`로 보고한다.

동시성 테스트는 PostgreSQL Testcontainer가 필요하다. Docker를 사용할 수 없어
실행하지 못하면 unit test 성공으로 대체하지 않고 해당 검증을 `Not run`으로 보고한다.

## Observability

Actuator/Micrometer의 기존 의존성을 사용하고 최소 다음을 노출한다. tag에는 customer ID,
Idempotency-Key, coupon ID 같은 high-cardinality·민감 값을 넣지 않는다.

- order create attempts/success/failure count by stable outcome code
- create transaction duration
- owner별 reservation conflict count
- idempotency replay, key-reuse conflict, stuck processing count
- due reservation count
- expiry processed/failed/retried count
- `now - reservationExpiresAt` expiry lag
- worker chunk duration

structured log는 correlation ID, order ID, action, outcome code, deadline, duration을
포함하고 payload 원문, token, point lot 상세와 stack trace를 API 응답에 노출하지
않는다. transaction rollback과 worker failure는 error log와 metric을 모두 남긴다.

AuditRecord는 최소 다음 action을 구분한다.

- `ORDER_CREATED`
- `PICKUP_RESERVED`, `STOCK_RESERVED`, `COUPON_RESERVED`, `POINTS_RESERVED`
- `ORDER_EXPIRED`
- `PICKUP_EXPIRED`, `STOCK_EXPIRED`, `COUPON_RELEASED`, `POINTS_RELEASED`
- 결정되면 `BENEFIT_ONLY_PAYMENT_APPROVED`와 각 reservation confirmation

## Documentation Updates

Milestone 0과 구현 중 현실이 달라질 때 코드보다 계획과 결정 문서를 먼저 갱신한다.

- Business Policy: 새 제품 숫자나 고객에게 보이는 동작만 amendment
- ADR: coupon model, sellable unit ownership, point reservation, idempotency
  transaction, BENEFIT_ONLY 범위, audit/expiry 의미
- Architecture: Context Map, Aggregate/constraint, state machine, transaction boundary
- OpenAPI/API docs: create request/response, 상태별 deadline, idempotency replay,
  stable errors
- Testing/quality evidence: 실제 추가된 test class와 검증 evidence
- Runbook: worker backlog, expiry lag, stuck idempotency record의 탐지·재처리 방법
- 이 ExecPlan: 매 milestone 직후 Progress, discovery, decision, actual command result

## Progress

- [x] 필수 AGENTS/PLANS/Business Policy/ADR/OpenAPI/아키텍처 문서 대조
- [x] 현재 application shell과 build/test 기반 확인
- [x] conflict와 중요한 decision gate 식별
- [x] 첫 self-contained ExecPlan 작성
- [x] Milestone 0 결정과 문서 기록
- [x] Milestone 1 모듈·테스트 기반
- [x] Milestone 2 가격 snapshot·배분
- [x] Milestone 3 슬롯·재고 예약
- [x] Milestone 4 쿠폰·포인트 예약
- [x] Milestone 5 atomic create·idempotency·API
- [ ] Milestone 6 expiry·release·audit
- [ ] Milestone 7 BENEFIT_ONLY branch
- [ ] 전체 검증과 문서 handoff

## Surprises & Discoveries

- 5분 lease와 Payment `UNKNOWN` 경계는 이미 BR-03과 ADR-013에서 해결되어 있었다.
  초기 핵심 미결정은 lease 숫자가 아니라 생성·만료를 실제 schema와 idempotency
  transaction으로 옮기는 방법이었다.
- Accepted BR-11 때문에 “Payment는 전부 후속 Feature”라는 foundation handoff 범위는
  payable 0 주문에서 성립하지 않는다.
- OpenAPI는 coupon과 points를 주문 생성 입력으로 공개하지만 Campaign 계산 모델과
  PointLot reservation 표현은 아직 계약하지 않는다.
- Inventory owner는 문서에 있지만 메뉴·옵션 조합에서 sellable unit으로 번역하는
  경계가 정의되지 않았다.
- BR-30은 이 Feature의 거의 모든 자원 변경을 감사 대상으로 만들지만 기존 first
  implementation handoff에는 Operations/Audit scope가 빠져 있었다.
- 초기 OpenAPI의 create 설명과 달리 `reservationExpiresAt`은 schema상 선택
  필드였다. Milestone 0에서 PENDING_PAYMENT variant의 필수 필드로 수정했다.
- Milestone 1에서 기존 `spring-modulith-starter-jpa`가 실제 event producer 없이도
  `event_publication` Entity를 활성화해 Hibernate `validate` 시작을 실패시켰다.
  ADR-010과 이 Feature의 event non-goal에 맞춰 starter를 비활성화했고
  `MD-2026-001`에 재도입 조건을 기록했다.
- 첫 PostgreSQL context 재검증 중 Docker Desktop engine이 중지되어 container가
  시작 직후 연결 거부를 반환했다. Docker Desktop을 시작한 뒤 같은 명령을 다시
  실행해 통과했으며 application fallback으로 대체하지 않았다.
- Milestone 2 대조에서 BR-08/ADR-024의 “비대상 line은 쿠폰 할인을 받지 않음”과
  BR-12/ADR-014/초기 ExecPlan의 “모든 혜택을 할인 전 gross 비율로 배분”이
  충돌했다. 구현을 중단하고 순차 잔액 기준을 결정한 뒤 관련 정책과 ADR을 먼저
  amendment했다.
- 정수 비율 배분의 `total * lineBasis`는 최종 KRW 값이 `Long` 범위여도 중간 곱이
  overflow할 수 있다. 배분 중간 계산만 JDK `BigInteger`를 사용하고 저장·API 결과는
  계속 non-negative signed 64-bit KRW로 제한했다.
- Testcontainers 검증은 Docker Desktop engine이 중지된 상태에서 provider 탐색
  실패로 한 차례 중단됐다. engine 준비를 확인한 뒤 동일 PostgreSQL 테스트를
  재실행해 통과했으며 테스트 DB나 저장소를 다른 구현으로 대체하지 않았다.
- Point allocation은 Account lock으로 고객 단위 동시 합계를 직렬화한 뒤 Lot을
  `(expiresAt, pointLotId)`로 잠근다. Account의 available 합계만 검사하면 만료
  Lot과 요약 불일치를 놓칠 수 있어 실제 unexpired Lot 합계도 같은 transaction에서
  다시 검증하도록 구현했다.
- 초기 Create flow의 coupon 계산 순서와 global lock order가 충돌했다. Merchant
  quote에서 stock 요구량을 먼저 계산하고 Tx O가 slot → sorted stock → coupon →
  point 순서로 잠근 뒤 coupon 결과로 가격을 계산하도록 계획과 구현을 맞췄다.
- PostgreSQL `char(64)` payload hash는 Hibernate의 String/varchar validation과
  타입이 달랐다. hash 길이는 `varchar(64) CHECK (length(payload_hash) = 64)`로
  보호해 Flyway schema와 Hibernate `validate`를 모두 통과시켰다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-28 | Accepted existing | 외부 결제 필요 Order의 lease는 생성 시각부터 5분, 연장 없음 | BR-03 | Business Policy |
| 2026-07-28 | Accepted existing | Order와 네 자원 예약은 한 로컬 PostgreSQL transaction | 강한 일관성과 현재 modular monolith 조건 | ADR-005 |
| 2026-07-28 | Accepted existing | 정확한 deadline부터 Payment UNKNOWN이어도 만료하고 late approval은 Order를 복구하지 않음 | 무기한 점유와 oversell 방지 | ADR-013 |
| 2026-07-28 | Accepted | BENEFIT_ONLY 최소 Payment를 이 Feature에 포함하고 주문 생성 tx에서 Payment 승인·예약 확정·Order PAID를 원자적으로 커밋 | BR-11과 foundation scope 충돌 해결 | BR-11, ADR-016 |
| 2026-07-28 | Accepted | FIXED_KRW/RATE_BPS Coupon은 대상 line 합계로 minimum과 할인을 계산 | 비대상 품목이 쿠폰을 활성화하거나 할인받지 않도록 함 | BR-08, ADR-024 |
| 2026-07-28 | Accepted | Merchant가 정규화한 menu/options를 sellable requirements로 번역하고 Inventory는 수량만 소유 | Inventory ownership과 수량 불변식 | ADR-026, Context Map |
| 2026-07-28 | Accepted | PointReservation/Allocation이 생성 시 유효한 Lot을 선만료순으로 예약하고 주문 lease까지 사용을 보장 | 만료 순서·비용 주체 재현과 lease 보장 | BR-03, ADR-011 |
| 2026-07-28 | Accepted | Tx I1 선행 PROCESSING, Tx O 성공 원자 완료, Tx I2 실패 저장과 최초 HTTP response 재생 | atomic reservation과 replay 의미 연결 | BR-25, ADR-025 |
| 2026-07-28 | Accepted | worker·조회·결제 명령이 같은 expiry tx를 사용하고 성공 후에만 EXPIRED/409, 실패는 503 | API와 DB 상태 일치, stale 성공 방지 | BR-03, ADR-013 |
| 2026-07-28 | Accepted | 주문 생성·만료의 변경 target별 AuditRecord, 표준 reason, 서울 달력 5년 보존 | BR-30 누락 범위, target 추적성과 retention 해소 | BR-30, ADR-022 |
| 2026-07-28 | Accepted | POST /orders 201은 상태별 `{order, payment?}` envelope | lease deadline과 BENEFIT_ONLY Payment를 모호하지 않게 표현 | API conventions, OpenAPI |
| 2026-07-28 | Minor | 실제 영속 event producer가 없는 동안 Spring Modulith JPA publication starter를 비활성화 | 사용하지 않는 publication schema를 자동 생성하거나 Hibernate validation을 우회하지 않음 | MD-2026-001 |
| 2026-07-28 | Accepted | Coupon은 eligible line에만 gross 비율로 배분하고 Points는 coupon 적용 후 line 잔액 비율로 배분 | 대상 제한을 지키면서 line benefit이 gross를 초과하지 않고 쿠폰→포인트 순서를 재현 | BR-12, ADR-014, ADR-024 |
| 2026-07-28 | Minor | Tx O의 잠금 순서를 slot → sorted stock → coupon → point로 고정하고 coupon 결과 뒤 가격을 계산 | Create flow의 초기 서술과 global lock order 충돌을 제거하고 같은 고객 결과로 deadlock 위험을 줄임 | ExecPlan |

## Outcomes & Retrospective

아직 구현하지 않았다.

현재 결과:

- 정책·ADR·OpenAPI·아키텍처의 일치 영역과 구현 차단 결정을 구분했다.
- Feature 구현 순서, owner, transaction, DB constraint, 실패 의미, test와 운영
  evidence를 한 문서에 모았다.
- 추천안만으로 하나가 되지 않는 제품·HTTP 의미는 사용자 질문으로 확정했다.
- 8개 결정 게이트를 모두 닫고 BR-03/08/11/25/30, ADR-011/013/016/022/024/025/026,
  architecture, API conventions, error catalog와 OpenAPI에 반영했다.
- `bash scripts/verify-docs.sh`와 상태별 OpenAPI 추가 assertion이 통과했다.
- 기능 코드, migration, runtime configuration과 production dependency는 변경하지
  않았다.

Milestone 완료 시 여기에 실제 관찰 가능한 동작, 실행한 command와 결과, 남은 위험,
후속 Payment ExecPlan handoff를 기록한다.

## Revision Notes

- 2026-07-28: 관련 정책·ADR·OpenAPI·아키텍처·현재 코드 대조 후 초기 ExecPlan 작성.
  0원 주문, coupon model, sellable unit, point reservation, idempotency transaction,
  logical/physical expiry, audit와 OpenAPI deadline을 구현 전 decision gate로 기록.
- 2026-07-28: Milestone 0 착수. 승인된 추천안에 따라 BENEFIT_ONLY 주문 생성 범위와
  Merchant 소유 sellable requirement 번역을 결정 기록에 반영. 기능 코드는 미변경.
- 2026-07-28: Coupon minimum과 할인 기준을 대상 line 합계로 확정하고
  FIXED_KRW/RATE_BPS 계산 모델을 BR-08과 ADR-024에 기록.
- 2026-07-28: PointReservation/Allocation과 예약 시점 유효성 보장을 확정. lease 중
  Lot 만료 후 해제된 allocation은 복원하지 않고 expiration 원장으로 처리.
- 2026-07-28: 주문 생성 idempotency를 선행 PROCESSING registration과 최초 HTTP
  response 재생으로 확정. 처리 중 replay는 409 + Retry-After.
- 2026-07-28: due Order 조회가 동일 expiry transaction을 먼저 materialize하고
  성공 후 EXPIRED, 실패 시 503을 반환하도록 확정.
- 2026-07-28: 주문 생성·만료 AuditRecord를 변경 target별로 기록하고 Customer/SYSTEM
  actor와 표준 reason code를 사용하도록 확정.
- 2026-07-28: AuditRecord를 occurredAt의 Asia/Seoul 달력 5주년까지 보존하고
  retention worker만 due record를 chunk 삭제하도록 확정.
- 2026-07-28: 주문 생성 201을 PENDING_PAYMENT/필수 deadline과
  PAID/필수 BENEFIT_ONLY Payment의 envelope oneOf으로 확정.
- 2026-07-28: Merchant MenuConfiguration과 Inventory SellableUnit 번역 경계를
  ADR-026으로 승격하고 configuration/availability/stock 오류를 구분.
- 2026-07-28: Milestone 0 acceptance criteria를 모두 충족하고 문서·OpenAPI 검증
  통과 결과를 기록. 기능 구현 전에 중단.
- 2026-07-28: Milestone 1 완료. 8개 owner/shared 모듈의 공개 `api`와 `internal`
  경계를 선언하고 주입 가능한 Clock/UUID/correlation source, PostgreSQL fail-fast
  설정과 공통 Testcontainers 기반을 추가. `./gradlew test --tests
  '*ModularityTests'`와 `./gradlew test --tests '*ApplicationContextTests'` 통과.
- 2026-07-28: Milestone 2 착수 전 coupon 대상 제한과 공통 배분 기준의 충돌을 발견.
  Coupon은 eligible line에만, Points는 coupon 적용 후 line 잔액 기준으로 순차
  배분하도록 BR-12와 ADR-014/024를 amendment한 뒤 구현 재개.
- 2026-07-28: Milestone 2 완료. Merchant menu/option/configuration quote snapshot,
  sellable requirement 정규화, KRW add/multiply overflow guard와 순차 benefit
  allocator, immutable OrderLine snapshot을 구현. `./gradlew test --tests
  '*OrderPricing*' --tests '*OrderTest'` 통과.
- 2026-07-28: Milestone 3 완료. PickupSlot과 SellableStock row lock, DB
  CHECK/UNIQUE/FK, order/source idempotency와 reserve/confirm/expire owner API를
  구현. `./gradlew test --tests '*PickupReservationRepositoryTest'`,
  `./gradlew test --tests '*StockReservationRepositoryTest'` 및 두 class 동시
  재실행, `./gradlew test --tests '*ModularityTests'` 통과.
- 2026-07-28: Milestone 4 완료. Campaign 대상 line 계산과 CouponIssuance row lock,
  최초 coupon quote snapshot, PointAccount/Lot 잠금과 구체 allocation,
  USE/EXPIRATION 원장 경로를 구현. `./gradlew test --tests
  '*CouponReservationRepositoryTest'`, `./gradlew test --tests
  '*PointReservationRepositoryTest'`, `./gradlew test --tests
  '*ModularityTests'` 통과.
- 2026-07-28: Milestone 5 완료. Merchant JPA quote adapter, Tx I1/T O/T I2
  IdempotencyRecord, atomic owner orchestration, Order snapshot persistence, JWT Customer
  ownership과 stable error envelope Controller를 구현. `./gradlew test --tests
  '*CreateOrderServiceTest'`, `./gradlew test --tests
  '*CreateOrderConcurrencyTest'`, `./gradlew test --tests
  '*OrderControllerContractTest'`, `./gradlew test --tests '*ModularityTests'` 통과.
